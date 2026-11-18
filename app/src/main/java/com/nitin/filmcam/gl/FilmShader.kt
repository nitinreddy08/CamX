package com.nitin.filmcam.gl

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.filter.ShaderSource
import kotlin.math.min

/**
 * Per-frame film motion (video/preview only): tiny frame drift ("gate weave") plus a small
 * exposure flicker, like film running through a projector. NONE for photos.
 */
data class Motion(val weaveX: Float = 0f, val weaveY: Float = 0f, val inset: Float = 0f, val flicker: Float = 0f) {
    companion object { val NONE = Motion() }
}

/**
 * The compiled film shader plus the ONE function ([draw]) that sets every uniform.
 * Preview, video and photo all go through [draw], so they always get identical parameters.
 *
 * Must be created and used on a thread with a current EGL context.
 */
class FilmShader(context: Context, private val input: ShaderSource.Input) {

    private val program: Int = GlUtil.createProgram(
        ShaderSource.vertex(context),
        ShaderSource.fragment(context, input),
    )
    private val uniforms = HashMap<String, Int>()

    private val textureTarget =
        if (input == ShaderSource.Input.CAMERA_EXTERNAL) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D

    private val hueArray = FloatArray(8 * 3)

    init {
        for (name in UNIFORM_NAMES) uniforms[name] = GLES30.glGetUniformLocation(program, name)
    }

    /**
     * Draws the full-screen quad with the film look into whatever framebuffer/viewport is
     * currently bound.
     *
     * @param textureId     input texture (OES camera texture or 2D photo texture)
     * @param texMatrix     4x4 column-major transform from output UV to input UV
     * @param outWidth/outHeight  output size in pixels (grain, vignette, halation live there)
     * @param inWidth/inHeight    input texture size in pixels (for the softness tap offset)
     * @param timeSeconds   animates grain; pass 0 for a static (photo) grain pattern
     * @param haloTexture   blurred highlights from [HalationPass] (or its black texture)
     * @param motion        film motion for this frame ([Motion.NONE] for photos)
     * @param bypass        true = output the untouched input (hold-to-compare, "before" images)
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
        haloTexture: Int,
        motion: Motion = Motion.NONE,
        bypass: Boolean = false,
        swapRedBlue: Boolean = false,
    ) {
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, haloTexture)
        GLES30.glUniform1i(u("uHalo"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(textureTarget, textureId)
        GLES30.glUniform1i(u("uTex"), 0)

        setVertexUniforms(program, texMatrix, motion)
        GLES30.glUniform2f(u("uResolution"), outWidth.toFloat(), outHeight.toFloat())

        // Softness tap distance: 0.75 px on a frame whose short side is 1080 px, scaled up for
        // bigger inputs so the *relative* softness is the same everywhere. Expressed in UV units
        // (1.0 = whole texture), which is what texture() expects.
        val radiusPx = 0.75f * min(inWidth, inHeight) / 1080f
        GLES30.glUniform2f(u("uBlurStep"), radiusPx / inWidth, radiusPx / inHeight)

        GLES30.glUniform1f(u("uTime"), timeSeconds)
        GLES30.glUniform1f(u("uSwapRB"), if (swapRedBlue) 1f else 0f)
        GLES30.glUniform1f(u("uBypass"), if (bypass) 1f else 0f)

        // v1
        GLES30.glUniform1f(u("uIntensity"), params.intensity)
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

        // v2. The two "active" flags let the shader skip whole stages when they're neutral,
        // which is also what keeps the Field preset bit-identical to engine v1.
        val linearStage = params.exposure != 0f || params.warmth != 0f || params.halation > 0f || motion.flicker != 0f
        GLES30.glUniform1f(u("uLinearStage"), if (linearStage) 1f else 0f)
        GLES30.glUniform1f(u("uExposure"), params.exposure)
        GLES30.glUniform1f(u("uWarmth"), params.warmth)
        GLES30.glUniform1f(u("uFlicker"), motion.flicker)
        GLES30.glUniform1f(u("uHalation"), params.halation)
        GLES30.glUniform1f(u("uFilmCurve"), params.filmCurve)
        GLES30.glUniform1f(u("uDyeMix"), params.dyeMix)
        GLES30.glUniform1f(u("uPastel"), params.pastel)
        GLES30.glUniform1f(u("uColorDensity"), params.colorDensity)
        GLES30.glUniform1f(u("uGrainDensity"), params.grainDensity)
        GLES30.glUniform1f(u("uGrainColor"), params.grainColor)
        var huesActive = false
        for (i in 0 until 8) {
            val h = params.hues.getOrNull(i) ?: com.nitin.filmcam.filter.HueAdjust()
            if (!h.isNeutral) huesActive = true
            hueArray[i * 3] = h.saturation
            hueArray[i * 3 + 1] = h.brightness
            hueArray[i * 3 + 2] = h.shift
        }
        GLES30.glUniform1f(u("uHueBands"), if (huesActive) 1f else 0f)
        GLES30.glUniform3fv(u("uHueAdj[0]"), 8, hueArray, 0)

        GlUtil.drawQuad(program)
        GLES30.glBindTexture(textureTarget, 0)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
    }

    private fun u(name: String) = uniforms[name] ?: -1

    companion object {
        private val UNIFORM_NAMES = listOf(
            "uTex", "uHalo", "uResolution", "uBlurStep", "uTime", "uSwapRB", "uBypass",
            "uIntensity", "uContrast", "uBlackLift", "uWhiteRoll", "uSaturation", "uGreenDesat",
            "uGreenToOlive", "uShadowTint", "uMidTint", "uHighlightTint", "uGrainAmount",
            "uGrainSize", "uSoftness", "uVignette",
            "uLinearStage", "uExposure", "uWarmth", "uFlicker", "uHalation", "uFilmCurve",
            "uDyeMix", "uPastel", "uColorDensity", "uHueBands", "uHueAdj[0]", "uGrainDensity",
            "uGrainColor",
        )

        val IDENTITY: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        /** Uniforms of the shared vertex shader (film.vert), used by every pass. */
        fun setVertexUniforms(program: Int, texMatrix: FloatArray, motion: Motion) {
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uWeave"), motion.weaveX, motion.weaveY)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uInset"), motion.inset)
        }
    }
}
