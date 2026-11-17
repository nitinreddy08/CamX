package com.nitin.filmcam.gl

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.filter.ShaderSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * The compiled film shader plus the ONE function ([draw]) that sets every uniform.
 * Preview, video and photo all go through [draw], so they always get identical parameters.
 *
 * Must be created and used on a thread with a current EGL context.
 */
class FilmShader(context: Context, private val input: ShaderSource.Input) {

    private val program: Int
    private val aPosition: Int
    private val aTexCoord: Int
    private val uniforms = HashMap<String, Int>()

    private val textureTarget =
        if (input == ShaderSource.Input.CAMERA_EXTERNAL) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D

    init {
        program = GlUtil.createProgram(
            ShaderSource.vertex(context),
            ShaderSource.fragment(context, input),
        )
        aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES30.glGetAttribLocation(program, "aTexCoord")
        for (name in UNIFORM_NAMES) uniforms[name] = GLES30.glGetUniformLocation(program, name)
    }

    /**
     * Draws the full-screen quad with the film look into whatever framebuffer/viewport is
     * currently bound.
     *
     * @param textureId     input texture (OES camera texture or 2D photo texture)
     * @param texMatrix     4x4 column-major transform from output UV to input UV
     * @param outWidth/outHeight  output size in pixels (grain + vignette are defined on it)
     * @param inWidth/inHeight    input texture size in pixels (for the softness tap offset)
     * @param timeSeconds   animates grain; pass 0 for a static (photo) grain pattern
     * @param intensityOverride  e.g. 0 for hold-to-compare; null = use params.intensity
     * @param swapRedBlue   write BGRA instead of RGBA (lets photo readback skip a CPU pass)
     */
    fun draw(
        textureId: Int,
        texMatrix: FloatArray,
        params: FilmParams,
        outWidth: Int,
        outHeight: Int,
        inWidth: Int,
        inHeight: Int,
        timeSeconds: Float,
        intensityOverride: Float? = null,
        swapRedBlue: Boolean = false,
    ) {
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(textureTarget, textureId)
        GLES30.glUniform1i(u("uTex"), 0)

        GLES30.glUniformMatrix4fv(u("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform2f(u("uResolution"), outWidth.toFloat(), outHeight.toFloat())

        // Softness tap distance: 0.75 px on a frame whose short side is 1080 px, scaled up for
        // bigger inputs so the *relative* softness is the same everywhere. Expressed in UV units
        // (1.0 = whole texture), which is what texture() expects.
        val radiusPx = 0.75f * min(inWidth, inHeight) / 1080f
        GLES30.glUniform2f(u("uBlurStep"), radiusPx / inWidth, radiusPx / inHeight)

        GLES30.glUniform1f(u("uTime"), timeSeconds)
        GLES30.glUniform1f(u("uSwapRB"), if (swapRedBlue) 1f else 0f)

        GLES30.glUniform1f(u("uIntensity"), intensityOverride ?: params.intensity)
        GLES30.glUniform1f(u("uContrast"), params.contrast)
        GLES30.glUniform1f(u("uBlackLift"), params.blackLift)
        GLES30.glUniform1f(u("uWhiteRoll"), params.whiteRoll)
        GLES30.glUniform1f(u("uSaturation"), params.saturation)
        GLES30.glUniform1f(u("uGreenDesat"), params.greenDesat)
        GLES30.glUniform1f(u("uGreenToOlive"), params.greenToOlive)
        GLES30.glUniform3fv(u("uShadowTint"), 1, params.shadowTint.toArray(), 0)
        GLES30.glUniform3fv(u("uMidTint"), 1, params.midTint.toArray(), 0)
        GLES30.glUniform3fv(u("uHighlightTint"), 1, params.highlightTint.toArray(), 0)
        GLES30.glUniform1f(u("uGrainAmount"), params.grainAmount)
        GLES30.glUniform1f(u("uGrainSize"), params.grainSize.coerceAtLeast(0.5f))
        GLES30.glUniform1f(u("uSoftness"), params.softness)
        GLES30.glUniform1f(u("uVignette"), params.vignette)

        // Two triangles covering the viewport.
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, QUAD_POS)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 0, QUAD_UV)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)
        GLES30.glBindTexture(textureTarget, 0)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
    }

    private fun u(name: String) = uniforms[name] ?: -1

    companion object {
        private val UNIFORM_NAMES = listOf(
            "uTex", "uTexMatrix", "uResolution", "uBlurStep", "uTime", "uSwapRB",
            "uIntensity", "uContrast", "uBlackLift", "uWhiteRoll", "uSaturation", "uGreenDesat",
            "uGreenToOlive", "uShadowTint", "uMidTint", "uHighlightTint", "uGrainAmount",
            "uGrainSize", "uSoftness", "uVignette",
        )

        // Triangle strip order: bottom-left, bottom-right, top-left, top-right.
        private val QUAD_POS: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val QUAD_UV: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        val IDENTITY: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        private fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v); position(0)
            }
    }
}
