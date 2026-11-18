package com.nitin.filmcam.gl

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.nitin.filmcam.filter.ShaderSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the halation glow texture for one output image, in three cheap passes at low
 * resolution (short side [HALO_SHORT_SIDE] px, so ~0.1 MP whatever the photo size):
 *   1. halo_prefilter.frag: downsample the frame + keep only light near clipping (linear light)
 *   2. halo_blur.frag horizontally
 *   3. halo_blur.frag vertically
 * The film shader then adds this blurred light back, tinted red-orange.
 * Because the buffer size is a fixed fraction of the frame, the glow covers the same fraction of
 * the picture in the 1080p preview and in a 50 MP photo.
 *
 * Must be created and used on a thread with a current EGL context. Leaves framebuffer 0 bound;
 * the caller sets its own framebuffer + viewport afterwards.
 */
class HalationPass(context: Context, private val input: ShaderSource.Input) {

    private val prefilter = GlUtil.createProgram(ShaderSource.vertex(context), ShaderSource.haloPrefilter(context, input))
    private val blur = GlUtil.createProgram(ShaderSource.vertex(context), ShaderSource.haloBlur(context))
    private val inputTarget =
        if (input == ShaderSource.Input.CAMERA_EXTERNAL) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D

    /** Bind this when halation is off: adds nothing. */
    val blackTexture: Int = GlUtil.createSolidTexture(0, 0, 0)

    private val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
    private var texA = 0
    private var texB = 0
    private var width = 0
    private var height = 0

    /** Renders the glow for an output of [outWidth]x[outHeight]; returns the texture to sample. */
    fun render(
        inputTexture: Int,
        texMatrix: FloatArray,
        outWidth: Int,
        outHeight: Int,
        inWidth: Int,
        inHeight: Int,
        motion: Motion,
    ): Int {
        val scale = HALO_SHORT_SIDE / min(outWidth, outHeight).toFloat()
        ensureSize(max(1, (outWidth * scale).roundToInt()), max(1, (outHeight * scale).roundToInt()))

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)

        // Pass 1: input -> A (same texture matrix + motion as the main pass = output orientation)
        attach(texA)
        GLES30.glUseProgram(prefilter)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(inputTarget, inputTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prefilter, "uTex"), 0)
        FilmShader.setVertexUniforms(prefilter, texMatrix, motion)
        // 4x4 taps spread over one halo texel's footprint in the input.
        val f = (min(inWidth, inHeight) / min(width, height).toFloat()) / 4f
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prefilter, "uTapStep"), f / inWidth, f / inHeight)
        GlUtil.drawQuad(prefilter)
        GLES30.glBindTexture(inputTarget, 0)

        // Pass 2 + 3: blur A -> B (horizontal), B -> A (vertical)
        GLES30.glUseProgram(blur)
        FilmShader.setVertexUniforms(blur, FilmShader.IDENTITY, Motion.NONE)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blur, "uSrc"), 0)
        val dir = GLES30.glGetUniformLocation(blur, "uDir")
        attach(texB)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texA)
        GLES30.glUniform2f(dir, BLUR_STEP / width, 0f)
        GlUtil.drawQuad(blur)
        attach(texA)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texB)
        GLES30.glUniform2f(dir, 0f, BLUR_STEP / height)
        GlUtil.drawQuad(blur)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return texA
    }

    private fun attach(tex: Int) {
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
    }

    private fun ensureSize(w: Int, h: Int) {
        if (w == width && h == height && texA != 0) return
        if (texA != 0) GlUtil.deleteTexture(texA)
        if (texB != 0) GlUtil.deleteTexture(texB)
        texA = GlUtil.createRenderTexture(w, h)
        texB = GlUtil.createRenderTexture(w, h)
        width = w
        height = h
    }

    fun release() {
        GLES30.glDeleteProgram(prefilter)
        GLES30.glDeleteProgram(blur)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (texA != 0) GlUtil.deleteTexture(texA)
        if (texB != 0) GlUtil.deleteTexture(texB)
        GlUtil.deleteTexture(blackTexture)
    }

    private companion object {
        const val HALO_SHORT_SIDE = 270f
        const val BLUR_STEP = 1.25f   // texels between blur taps -> glow radius ~1% of the frame
    }
}
