package com.nitin.filmcam.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Minimal EGL wrapper.
 *
 * EGL is the "glue" between OpenGL ES and the OS: it creates a GL *context* (all GL state lives
 * in it, and it is only usable from the thread that made it current) and *surfaces* to draw into.
 * Here a surface is either an Android [Surface] (the preview view or the video encoder input)
 * or a tiny off-screen pbuffer used when we only render into textures.
 *
 * Every method must be called on the thread that owns this object.
 */
class EglCore {
    val display: EGLDisplay
    val context: EGLContext
    private val config: EGLConfig

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        // RGBA8888, renderable with GLES 3 (ES3 bit), and RECORDABLE so the same config can draw
        // into a MediaCodec video-encoder surface.
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "no suitable EGL config"
        }
        config = configs[0]!!

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${EGL14.eglGetError()}" }
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
        return s
    }

    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        val s = EGL14.eglCreatePbufferSurface(display, config, attribs, 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed: ${EGL14.eglGetError()}" }
        return s
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "eglMakeCurrent failed: ${EGL14.eglGetError()}"
        }
    }

    /** Tells the video encoder which capture time this frame belongs to (nanoseconds). */
    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    /** Hands the finished frame to the surface's consumer (screen or encoder). */
    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        // No eglTerminate(): the default display is shared by the whole process (the preview
        // processor and the photo renderer each have their own context on it), and terminating
        // it would pull the rug out from under the other one.
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
