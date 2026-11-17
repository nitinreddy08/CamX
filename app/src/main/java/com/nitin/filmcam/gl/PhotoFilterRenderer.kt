package com.nitin.filmcam.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.filter.ShaderSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Runs a full-resolution photo through the SAME film shader as the preview, off-screen.
 *
 * It has its own GL thread + EGL context (separate from the preview's), so processing a
 * 12-50 MP photo never makes the live preview stutter.
 *
 * Steps for one photo:
 *  1. Upload the decoded bitmap as a texture (then the CPU copy can be freed).
 *  2. Render the output in horizontal STRIPS into a small framebuffer. A strip-sized buffer
 *     keeps GPU memory low; the shader still "sees" the whole image because we offset the
 *     viewport so every strip is just a window onto one big virtual render.
 *  3. Read each strip back (glReadPixels) straight into the output bitmap's pixel layout.
 *     The shader writes BGR instead of RGB (uSwapRB) so the bytes are already in Android's
 *     ARGB int order and no per-pixel CPU swizzle is needed.
 */
class PhotoFilterRenderer(private val appContext: Context) {

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "PhotoGL") }
    private var egl: EglCore? = null
    private var pbuffer: EGLSurface? = null
    private var shader: FilmShader? = null
    private var maxTextureSize = 4096

    /** Largest image side the GPU can take; bigger photos must be downscaled when decoding. */
    fun maxTextureSize(): Int = runOnGl { maxTextureSize }

    /**
     * Filters [source] and returns a NEW upright bitmap. [source] is recycled (freed) as soon as
     * it is on the GPU, to keep peak memory at about one photo's worth.
     *
     * @param exifOrientation ExifInterface.ORIENTATION_* of the source; applied while filtering
     * @param timeSeconds grain seed; 0 = the static photo grain pattern
     * @param recycleSource set false if the caller still needs [source] (debug screen)
     */
    fun render(
        source: Bitmap,
        params: FilmParams,
        exifOrientation: Int = ExifTransform.NORMAL,
        timeSeconds: Float = 0f,
        recycleSource: Boolean = true,
    ): Bitmap = runOnGl {
        val film = shader!!
        val inW = source.width
        val inH = source.height
        val swap = ExifTransform.swapsDimensions(exifOrientation)
        val outW = if (swap) inH else inW
        val outH = if (swap) inW else inH

        // 1. Upload.
        val inputTex = GlUtil.create2DTexture()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, source, 0)
        GlUtil.checkError("texImage2D")
        if (recycleSource) source.recycle()

        // 2. Strip framebuffer: ~8 MB per strip regardless of photo size.
        val stripH = max(16, min(outH, (8 * 1024 * 1024) / (outW * 4)))
        val fboTex = IntArray(1)
        GLES30.glGenTextures(1, fboTex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, outW, stripH, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, fboTex[0], 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "photo framebuffer incomplete"
        }

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val stripBytes = ByteBuffer.allocateDirect(outW * stripH * 4).order(ByteOrder.LITTLE_ENDIAN)
        val stripInts = IntArray(outW * stripH)
        val texMatrix = ExifTransform.texMatrix(exifOrientation)

        // 3. Render + read back strip by strip.
        var y0 = 0
        while (y0 < outH) {
            val h = min(stripH, outH - y0)
            // Viewport covers the WHOLE output image, shifted down by y0, so framebuffer row 0
            // holds output row y0. (Framebuffer row 0 = vPos.y 0 = the top of the photo, because
            // texture row 0 is the bitmap's top row; glReadPixels returns rows in that order.)
            GLES30.glViewport(0, -y0, outW, outH)
            film.draw(
                textureId = inputTex,
                texMatrix = texMatrix,
                params = params,
                outWidth = outW,
                outHeight = outH,
                inWidth = inW,
                inHeight = inH,
                timeSeconds = timeSeconds,
                swapRedBlue = true,
            )
            stripBytes.clear()
            GLES30.glReadPixels(0, 0, outW, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, stripBytes)
            // Bytes are B,G,R,A; read as little-endian ints that is 0xAARRGGBB = Android's ARGB.
            stripBytes.position(0)
            stripBytes.asIntBuffer().get(stripInts, 0, outW * h)
            out.setPixels(stripInts, 0, outW, 0, y0, outW, h)
            y0 += h
        }
        GlUtil.checkError("photo render")

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        GLES30.glDeleteTextures(1, fboTex, 0)
        GlUtil.deleteTexture(inputTex)
        out
    }

    fun release() {
        thread.execute {
            shader?.release()
            pbuffer?.let { egl?.releaseSurface(it) }
            egl?.release()
            shader = null; egl = null; pbuffer = null
        }
        thread.shutdown()
    }

    /** Runs [block] on the photo GL thread (initialising GL lazily) and waits for the result. */
    private fun <T> runOnGl(block: () -> T): T = thread.submit<T> {
        if (egl == null) {
            val core = EglCore()
            val pb = core.createPbufferSurface(1, 1)
            core.makeCurrent(pb)
            egl = core; pbuffer = pb
            shader = FilmShader(appContext, ShaderSource.Input.BITMAP_2D)
            val v = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
            maxTextureSize = v[0]
        }
        block()
    }.get()
}
