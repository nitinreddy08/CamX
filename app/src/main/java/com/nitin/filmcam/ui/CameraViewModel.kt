package com.nitin.filmcam.ui

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.nitin.filmcam.camera.BindConfig
import com.nitin.filmcam.camera.CameraController
import com.nitin.filmcam.camera.CaptureMode
import com.nitin.filmcam.camera.Flash
import com.nitin.filmcam.camera.Ratio
import com.nitin.filmcam.filter.AppSettings
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.filter.SettingsRepository
import com.nitin.filmcam.gl.FilmSurfaceProcessor
import com.nitin.filmcam.gl.PhotoFilterRenderer
import com.nitin.filmcam.storage.FileNames
import com.nitin.filmcam.storage.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Everything the camera screen shows, in one immutable snapshot. */
data class CameraUiState(
    val config: BindConfig = BindConfig(),
    val processingCount: Int = 0,
    val lastMedia: Uri? = null,
    val lastMediaIsVideo: Boolean = false,
    val zoomRatio: Float = 1f,
    val message: String? = null,
    val comparing: Boolean = false,
    val hasFrontCamera: Boolean = true,
    val recording: Boolean = false,
    val recordSeconds: Long = 0,
)

/**
 * The camera screen's brain. Lives across recompositions and navigation to settings, and owns
 * the two GL renderers so their threads are created once and released exactly once
 * ([onCleared]).
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {
    val processor = FilmSurfaceProcessor(app)
    val photoRenderer = PhotoFilterRenderer(app)
    val controller = CameraController(app, processor)
    private val photoProcessor = PhotoProcessor(app, photoRenderer)
    val repository = SettingsRepository(app)

    private val _params = MutableStateFlow(FilmParams.DEFAULT)
    val params: StateFlow<FilmParams> = _params.asStateFlow()

    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui.asStateFlow()

    // CameraX writes the unfiltered JPEG on this thread.
    private val captureExecutor = Executors.newSingleThreadExecutor()
    // One photo filtered at a time keeps peak memory at ~1 photo (important for 50 MP).
    private val photoMutex = Mutex()

    private var activeRecording: Recording? = null
    private var previewView: PreviewView? = null
    private var owner: LifecycleOwner? = null

    private var saveOriginal = false
    private var saveParamsJob: Job? = null

    val settings: Flow<AppSettings> = repository.settings
    val presets: StateFlow<Map<String, FilmParams>> =
        repository.presets.stateIn(viewModelScope, SharingStarted.Eagerly, mapOf(FilmParams.DEFAULT_PRESET_NAME to FilmParams.DEFAULT))

    private val _supports4k = MutableStateFlow(false)
    val supports4k: StateFlow<Boolean> = _supports4k.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { findLastMedia() }
        // Restore the last-used look and settings (persisted across app restarts).
        viewModelScope.launch {
            val p = repository.loadParams()
            _params.value = p
            processor.params = p
            val s = repository.loadSettings()
            saveOriginal = s.saveOriginal
            updateConfig { it.copy(ratio = if (s.ratio43) Ratio.R4_3 else Ratio.R16_9, video4k = s.video4k) }
        }
    }

    // ------------------------------------------------------------------ params

    /** Applies new filter settings instantly (next preview frame) and saves them shortly after. */
    fun updateParams(p: FilmParams) {
        _params.value = p
        processor.params = p
        // Debounce: dragging a slider fires many updates; write to disk once it settles.
        saveParamsJob?.cancel()
        saveParamsJob = viewModelScope.launch {
            delay(400)
            repository.saveParams(p)
        }
    }

    fun resetParams() = updateParams(FilmParams.DEFAULT)

    // ------------------------------------------------------------------ presets + settings

    fun applyPreset(p: FilmParams) = updateParams(p)

    fun savePreset(name: String) {
        val p = _params.value
        viewModelScope.launch { repository.savePreset(name, p) }
    }

    fun deletePreset(name: String) {
        viewModelScope.launch { repository.deletePreset(name) }
    }

    fun setSaveOriginal(on: Boolean) {
        saveOriginal = on
        viewModelScope.launch { repository.updateSettings { it.copy(saveOriginal = on) } }
    }

    fun setVideo4k(on: Boolean) {
        viewModelScope.launch { repository.updateSettings { it.copy(video4k = on) } }
        updateConfig { it.copy(video4k = on) }
    }

    fun setComparing(on: Boolean) {
        processor.compareOriginal = on
        _ui.update { it.copy(comparing = on) }
    }

    // ------------------------------------------------------------------ binding

    fun attach(owner: LifecycleOwner, view: PreviewView) {
        this.owner = owner
        this.previewView = view
        rebind()
    }

    fun detach() {
        stopRecording()
        controller.unbindAll()
        previewView = null
        owner = null
    }

    private fun rebind() {
        val o = owner ?: return
        val v = previewView ?: return
        controller.bind(o, v, _ui.value.config,
            onBound = {
                _supports4k.value = controller.supports4k()
                _ui.update { s ->
                    s.copy(
                        zoomRatio = 1f,
                        hasFrontCamera = controller.hasCamera(CameraSelector.LENS_FACING_FRONT),
                    )
                }
            },
            onError = { e -> showMessage("Camera error: ${e.message}") },
        )
    }

    private fun updateConfig(transform: (BindConfig) -> BindConfig) {
        if (_ui.value.recording) return  // never rebind mid-recording
        val old = _ui.value.config
        val new = transform(old)
        if (new == old) return
        _ui.update { it.copy(config = new) }
        rebind()
    }

    fun setMode(mode: CaptureMode) = updateConfig { it.copy(mode = mode) }

    fun flipCamera() = updateConfig {
        it.copy(
            lensFacing = if (it.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
        )
    }

    fun setRatio(ratio: Ratio) {
        viewModelScope.launch { repository.updateSettings { it.copy(ratio43 = ratio == Ratio.R4_3) } }
        updateConfig { it.copy(ratio = ratio) }
    }

    fun cycleFlash() {
        val c = _ui.value.config
        val next = when (c.mode) {
            CaptureMode.PHOTO -> when (c.flash) { Flash.OFF -> Flash.AUTO; Flash.AUTO -> Flash.ON; Flash.ON -> Flash.OFF }
            CaptureMode.VIDEO -> if (c.flash == Flash.ON) Flash.OFF else Flash.ON  // torch: on/off only
        }
        _ui.update { it.copy(config = c.copy(flash = next)) }
        controller.setFlash(next, c.mode)
    }

    fun setDeviceRotation(rotation: Int) = controller.setTargetRotation(rotation)

    fun focusAt(x: Float, y: Float) {
        previewView?.let { controller.focusAt(it, x, y) }
    }

    fun zoomBy(scale: Float) {
        controller.zoomBy(scale)?.let { z -> _ui.update { it.copy(zoomRatio = z) } }
    }

    // ------------------------------------------------------------------ photo

    fun takePhoto() {
        val app = getApplication<Application>()
        val captured = File(app.cacheDir, "capture_${System.nanoTime()}.jpg")
        val paramsAtShutter = _params.value  // the look you saw when you pressed the shutter
        val keepOriginal = saveOriginal
        _ui.update { it.copy(processingCount = it.processingCount + 1) }
        controller.takePhoto(
            captured, captureExecutor,
            onSaved = {
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        val result = photoMutex.withLock { photoProcessor.process(captured, paramsAtShutter, keepOriginal) }
                        Log.i(TAG, "Photo ${result.width}x${result.height} processed in ${result.millis} ms")
                        _ui.update { it.copy(lastMedia = result.uri, lastMediaIsVideo = false) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "photo processing failed", t)
                        showMessage("Could not save photo: ${t.message}")
                    } finally {
                        captured.delete()
                        _ui.update { it.copy(processingCount = it.processingCount - 1) }
                    }
                }
            },
            onError = { e ->
                captured.delete()
                _ui.update { it.copy(processingCount = it.processingCount - 1) }
                showMessage("Capture failed: ${e.message}")
            },
        )
    }

    // ------------------------------------------------------------------ video

    fun toggleRecording() {
        if (activeRecording != null) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val app = getApplication<Application>()
        val withAudio = Permissions.hasAudio(app)
        val rec = controller.startRecording(FileNames.video(), withAudio) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    processor.recording = true
                    _ui.update { it.copy(recording = true, recordSeconds = 0) }
                }
                is VideoRecordEvent.Status -> {
                    val secs = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    if (secs != _ui.value.recordSeconds) _ui.update { it.copy(recordSeconds = secs) }
                }
                is VideoRecordEvent.Finalize -> {
                    processor.recording = false
                    activeRecording = null
                    val uri = event.outputResults.outputUri
                    val saved = uri != Uri.EMPTY
                    _ui.update {
                        it.copy(
                            recording = false,
                            recordSeconds = 0,
                            lastMedia = if (saved) uri else it.lastMedia,
                            lastMediaIsVideo = if (saved) true else it.lastMediaIsVideo,
                        )
                    }
                    if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        Log.w(TAG, "recording finished with error ${event.error}", event.cause)
                        showMessage(if (saved) "Video saved (stopped early: error ${event.error})" else "Video failed (error ${event.error})")
                    }
                }
                else -> Unit
            }
        }
        if (rec == null) {
            showMessage("Switch to VIDEO to record")
            return
        }
        activeRecording = rec
        if (!withAudio) showMessage("Recording without sound (microphone not allowed)")
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    // ------------------------------------------------------------------ misc

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    /** Finds our newest photo or video so the thumbnail isn't empty after a restart. */
    private suspend fun findLastMedia() = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        fun newest(collection: Uri, relPath: String): Pair<Uri, Long>? = try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$relPath%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                if (c.moveToFirst()) Pair(ContentUris.withAppendedId(collection, c.getLong(0)), c.getLong(1)) else null
            }
        } catch (_: Exception) {
            null
        }
        val img = newest(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), FileNames.PHOTO_DIR)
        val vid = newest(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), FileNames.VIDEO_DIR)
        val pick = listOfNotNull(img?.let { Triple(it.first, it.second, false) }, vid?.let { Triple(it.first, it.second, true) })
            .maxByOrNull { it.second }
        if (pick != null && _ui.value.lastMedia == null) {
            _ui.update { it.copy(lastMedia = pick.first, lastMediaIsVideo = pick.third) }
        }
    }

    override fun onCleared() {
        stopRecording()
        controller.unbindAll()
        processor.release()
        photoRenderer.release()
        captureExecutor.shutdown()
    }

    private companion object {
        const val TAG = "CameraViewModel"
    }
}
