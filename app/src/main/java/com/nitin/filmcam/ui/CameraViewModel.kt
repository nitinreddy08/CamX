package com.nitin.filmcam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.nitin.filmcam.camera.CameraController
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.gl.FilmSurfaceProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lives as long as the camera screen (survives recomposition). Owns the GL processor so the
 * GL thread is created once and released exactly once, in [onCleared].
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {
    val processor = FilmSurfaceProcessor(app)
    val controller = CameraController(app, processor)

    private val _params = MutableStateFlow(FilmParams.DEFAULT)
    val params: StateFlow<FilmParams> = _params.asStateFlow()

    fun updateParams(p: FilmParams) {
        _params.value = p
        processor.params = p
    }

    override fun onCleared() {
        processor.release()
    }
}
