package com.nitin.filmcam.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.filter.ShaderSource
import java.util.concurrent.Executor

/**
 * The real-time film filter for the live preview and the video recording.
 *
 * How CameraX effects work:
 *  1. CameraX asks us for an INPUT surface ([onInputSurface]). We create a GL texture, wrap it
 *     in a [SurfaceTexture] + [Surface], and hand that to CameraX. The camera then writes each
 *     frame into our texture.
 *  2. CameraX gives us one or more OUTPUT surfaces ([onOutputSurface]): the preview view's
 *     surface and, while in video mode, the video encoder's surface.
 *  3. For every camera frame ([onFrameAvailable]) we draw the texture through the film shader
 *     into each output surface.
 *
 * All GL work happens on one dedicated thread ("FilmGL"). OpenGL contexts are single-threaded,
 * and keeping it off the main thread means the UI never stutters because of rendering.
 * CameraX calls us on [executor], which runs on that same thread.
 */
class FilmSurfaceProcessor(private val appContext: Context) : SurfaceProcessor {

    private val glThread = HandlerThread("FilmGL").apply { start() }
    private val glHandler = Handler(glThread.looper)

    /** Executor that runs tasks on the GL thread. Given to CameraX together with this processor. */
    val executor: Executor = Executor { glHandler.post(it) }

    /** Latest filter settings. Written from the UI thread, read on the GL thread. */
    @Volatile var params: FilmParams = FilmParams.DEFAULT

    /** Hold-to-compare: when true the preview shows the unfiltered image. */
    @Volatile var compareOriginal: Boolean = false

    /** True while a video is being recorded; compare mode never touches the recording. */
    @Volatile var recording: Boolean = false

    // --- GL thread state (only touched on glThread) ---
    private var egl: EglCore? = null
    private var placeholderSurface: EGLSurface? = null
    private var shader: FilmShader? = null
    private var released = false
    private val inputs = HashMap<SurfaceTexture, Input>()
    private val outputs = LinkedHashMap<SurfaceOutput, Output>()
    private val texMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private val startTime = SystemClock.elapsedRealtime()

    private class Input(val textureId: Int, val surface: Surface, val size: Size)
    private class Output(val eglSurface: EGLSurface, val size: Size, val targets: Int)

    init {
        glHandler.post {
            try {
                val core = EglCore()
                // A context needs *some* surface to be current; a 1x1 off-screen buffer is enough
                // when no output surface exists yet.
                val placeholder = core.createPbufferSurface(1, 1)
                core.makeCurrent(placeholder)
                egl = core
                placeholderSurface = placeholder
                shader = FilmShader(appContext, ShaderSource.Input.CAMERA_EXTERNAL)
            } catch (t: Throwable) {
                Log.e(TAG, "GL init failed", t)
            }
        }
    }

    // ---------------------------------------------------------------- CameraX callbacks

    override fun onInputSurface(request: SurfaceRequest) {
        val core = egl
        if (released || core == null) {
            request.willNotProvideSurface()
            return
        }
        core.makeCurrent(placeholderSurface!!)
        val textureId = GlUtil.createExternalTexture()
        val size = request.resolution
        val surfaceTexture = SurfaceTexture(textureId)
        // Camera buffers will be this size.
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(surfaceTexture)
        inputs[surfaceTexture] = Input(textureId, surface, size)

        // Called (on the GL thread) when a frame has been written into the texture.
        surfaceTexture.setOnFrameAvailableListener({ onFrameAvailable(it) }, glHandler)

        request.provideSurface(surface, executor) {
            // CameraX is done with this input (camera closed or rebinding): free everything.
            surfaceTexture.setOnFrameAvailableListener(null)
            inputs.remove(surfaceTexture)
            surfaceTexture.release()
            surface.release()
            if (egl != null && !released) {
                egl?.makeCurrent(placeholderSurface!!)
                GlUtil.deleteTexture(textureId)
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        val core = egl
        if (released || core == null) {
            surfaceOutput.close()
            return
        }
        val surface = surfaceOutput.getSurface(executor) { event ->
            if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                // The consumer (view or encoder) is going away: stop drawing into it first,
                // then tell CameraX we're done with it.
                outputs.remove(surfaceOutput)?.let { egl?.releaseSurface(it.eglSurface) }
                surfaceOutput.close()
            }
        }
        val eglSurface = core.createWindowSurface(surface)
        outputs[surfaceOutput] = Output(eglSurface, surfaceOutput.size, surfaceOutput.targets)
    }

    // ---------------------------------------------------------------- rendering

    private fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        val core = egl ?: return
        val film = shader ?: return
        val input = inputs[surfaceTexture] ?: return
        if (released) return

        // updateTexImage() must run with our context current. It latches the newest camera
        // frame into the texture and gives us the matrix describing its orientation/crop.
        core.makeCurrent(placeholderSurface!!)
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        val timestampNs = surfaceTexture.timestamp
        val params = params
        // Wrap time so float precision stays good in very long sessions (grain only needs
        // "different each frame", not a meaningful clock).
        val seconds = ((SystemClock.elapsedRealtime() - startTime) % 3_600_000L) / 1000f

        for ((surfaceOutput, out) in outputs) {
            // Combine the camera's texture matrix with this output's own rotation/crop/mirror.
            surfaceOutput.updateTransformMatrix(outMatrix, texMatrix)
            try {
                core.makeCurrent(out.eglSurface)
            } catch (t: Throwable) {
                Log.w(TAG, "output surface lost", t)
                continue
            }
            GLES30.glViewport(0, 0, out.size.width, out.size.height)
            val touchesVideo = (out.targets and CameraEffect.VIDEO_CAPTURE) != 0
            val showOriginal = compareOriginal && !(touchesVideo && recording)
            film.draw(
                textureId = input.textureId,
                texMatrix = outMatrix,
                params = params,
                outWidth = out.size.width,
                outHeight = out.size.height,
                inWidth = input.size.width,
                inHeight = input.size.height,
                timeSeconds = seconds,
                intensityOverride = if (showOriginal) 0f else null,
            )
            // The encoder needs the real capture time of each frame for correct A/V sync.
            core.setPresentationTime(out.eglSurface, timestampNs)
            core.swapBuffers(out.eglSurface)
        }
    }

    /** Frees every GL/EGL resource and stops the thread. Call once, when the screen is destroyed. */
    fun release() {
        glHandler.post {
            if (released) return@post
            released = true
            val core = egl
            for ((so, out) in outputs) {
                core?.releaseSurface(out.eglSurface)
                so.close()
            }
            outputs.clear()
            for ((st, input) in inputs) {
                st.setOnFrameAvailableListener(null)
                st.release()
                input.surface.release()
            }
            inputs.clear()
            if (core != null) {
                core.makeCurrent(placeholderSurface!!)
                shader?.release()
                core.releaseSurface(placeholderSurface!!)
                core.release()
            }
            egl = null
            shader = null
            glThread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "FilmSurfaceProcessor"
    }
}

/**
 * Wraps the processor as a CameraX effect. [targets] says which use cases get filtered:
 * PREVIEW in photo mode, PREVIEW | VIDEO_CAPTURE in video mode (CameraX then shares one camera
 * stream between preview and recording and runs our shader once for both).
 */
class FilmEffect(targets: Int, processor: FilmSurfaceProcessor) :
    CameraEffect(targets, processor.executor, processor, { t -> Log.e("FilmEffect", "effect error", t) })
