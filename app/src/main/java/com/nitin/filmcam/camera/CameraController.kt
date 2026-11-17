package com.nitin.filmcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import com.nitin.filmcam.gl.FilmEffect
import com.nitin.filmcam.gl.FilmSurfaceProcessor
import com.nitin.filmcam.storage.MediaStoreSaver
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

enum class CaptureMode { PHOTO, VIDEO }
enum class Ratio(val label: String) { R4_3("4:3"), R16_9("16:9") }
enum class Flash(val label: String) { OFF("Flash off"), AUTO("Flash auto"), ON("Flash on") }

/** Everything that requires re-binding the camera when it changes. */
data class BindConfig(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val ratio: Ratio = Ratio.R4_3,
    val video4k: Boolean = false,
    val flash: Flash = Flash.OFF,
)

/**
 * Owns the CameraX use cases and binds them to the screen's lifecycle.
 * Binding to a lifecycle means CameraX opens the camera when the app is visible and closes it
 * automatically when it goes to the background: no manual open/close bookkeeping.
 *
 * Two binding layouts:
 *  - PHOTO: Preview (filtered by our effect) + ImageCapture (unfiltered, full resolution;
 *           filtered afterwards by PhotoFilterRenderer).
 *  - VIDEO: Preview + VideoCapture, BOTH filtered by one effect. CameraX shares a single camera
 *           stream between them ("stream sharing"), so the shader runs once per frame and what
 *           you see is exactly what gets recorded.
 * Switching mode re-binds (a short blink), which keeps each layout simple and well supported.
 */
class CameraController(
    private val context: Context,
    private val processor: FilmSurfaceProcessor,
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null

    var camera: Camera? = null
        private set
    var imageCapture: ImageCapture? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    /** Current device rotation (Surface.ROTATION_*), so photos/videos are saved upright. */
    private var targetRotation: Int = android.view.Surface.ROTATION_0

    fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        config: BindConfig,
        onBound: (Camera) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get().also { provider = it }
            provider.unbindAll()
            imageCapture = null
            videoCapture = null

            val ratio = if (config.mode == CaptureMode.VIDEO) Ratio.R16_9 else config.ratio
            val aspect = if (ratio == Ratio.R4_3) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY

            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                .build()
            preview.surfaceProvider = previewView.surfaceProvider

            val group = UseCaseGroup.Builder().addUseCase(preview)
            when (config.mode) {
                CaptureMode.PHOTO -> {
                    val ic = ImageCapture.Builder()
                        // Quality first: lets the camera use its slower, better processing.
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(aspect)
                                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                                .build()
                        )
                        // Max JPEG quality for the intermediate file; we re-encode at 95 after filtering.
                        .setJpegQuality(100)
                        .setFlashMode(config.flash.toImageCaptureFlash())
                        .setTargetRotation(targetRotation)
                        .build()
                    imageCapture = ic
                    group.addUseCase(ic)
                    // Only the preview is filtered live; photos are filtered after capture.
                    group.addEffect(FilmEffect(CameraEffect.PREVIEW, processor))
                }
                CaptureMode.VIDEO -> {
                    val quality = if (config.video4k) {
                        QualitySelector.from(Quality.UHD, FallbackStrategy.lowerQualityThan(Quality.UHD))
                    } else {
                        QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))
                    }
                    val recorder = Recorder.Builder().setQualitySelector(quality).build()
                    val vc = VideoCapture.Builder(recorder)
                        .setTargetFrameRate(Range(30, 30))
                        .setTargetRotation(targetRotation)
                        .build()
                    videoCapture = vc
                    group.addUseCase(vc)
                    // One effect for both: the recording gets exactly the preview's look.
                    group.addEffect(FilmEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, processor))
                }
            }

            try {
                val selector = CameraSelector.Builder().requireLensFacing(config.lensFacing).build()
                val cam = provider.bindToLifecycle(owner, selector, group.build())
                camera = cam
                requestedZoom = null
                if (config.mode == CaptureMode.VIDEO && cam.cameraInfo.hasFlashUnit()) {
                    cam.cameraControl.enableTorch(config.flash == Flash.ON)
                }
                onBound(cam)
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                onError(e)
            }
        }, mainExecutor)
    }

    fun unbindAll() {
        provider?.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null
    }

    fun hasCamera(lensFacing: Int): Boolean {
        val p = provider ?: return lensFacing == CameraSelector.LENS_FACING_BACK
        return try {
            p.hasCamera(CameraSelector.Builder().requireLensFacing(lensFacing).build())
        } catch (_: Exception) {
            false
        }
    }

    /** Called when the phone is physically rotated; the UI itself stays portrait. */
    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    fun setFlash(flash: Flash, mode: CaptureMode) {
        if (mode == CaptureMode.PHOTO) {
            imageCapture?.flashMode = flash.toImageCaptureFlash()
        } else {
            camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(flash == Flash.ON) }
        }
    }

    /** Tap-to-focus/expose at a point in PreviewView coordinates. */
    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    /** Multiplies the current zoom by [scale] (pinch gesture). Returns the new zoom ratio. */
    fun zoomBy(scale: Float): Float? {
        val cam = camera ?: return null
        val state = cam.cameraInfo.zoomState.value ?: return null
        // Accumulate on our own last request: the camera's reported zoom lags a few frames
        // behind, and multiplying that stale value would make pinching feel sluggish.
        val base = requestedZoom ?: state.zoomRatio
        val ratio = (base * scale).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        requestedZoom = ratio
        cam.cameraControl.setZoomRatio(ratio)
        return ratio
    }

    private var requestedZoom: Float? = null

    /** Whether this camera can record 4K (UHD) at all. */
    fun supports4k(): Boolean {
        val cam = camera ?: return false
        return try {
            Recorder.getVideoCapabilities(cam.cameraInfo)
                .getSupportedQualities(DynamicRange.SDR)
                .contains(Quality.UHD)
        } catch (_: Exception) {
            false
        }
    }

    /** Captures a full-resolution, UNFILTERED JPEG into [file]. Filtering happens afterwards. */
    fun takePhoto(file: File, executor: Executor, onSaved: () -> Unit, onError: (Throwable) -> Unit) {
        val ic = imageCapture ?: return onError(IllegalStateException("Not in photo mode"))
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved()
                override fun onError(exception: ImageCaptureException) = onError(exception)
            },
        )
    }

    /**
     * Starts recording the (filtered) video stream to Movies/FilmCam. CameraX's Recorder handles
     * encoding, audio and muxing; we just pick the destination.
     */
    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before passing withAudio = true
    fun startRecording(displayName: String, withAudio: Boolean, listener: Consumer<VideoRecordEvent>): Recording? {
        val vc = videoCapture ?: return null
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(MediaStoreSaver.videoValues(displayName)).build()
        var pending = vc.output.prepareRecording(context, options)
        if (withAudio) pending = pending.withAudioEnabled()
        return pending.start(mainExecutor, listener)
    }

    private fun Flash.toImageCaptureFlash() = when (this) {
        Flash.OFF -> ImageCapture.FLASH_MODE_OFF
        Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
        Flash.ON -> ImageCapture.FLASH_MODE_ON
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
