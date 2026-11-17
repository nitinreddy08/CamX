package com.nitin.filmcam.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.nitin.filmcam.gl.FilmEffect
import com.nitin.filmcam.gl.FilmSurfaceProcessor

/**
 * Owns the CameraX use cases and binds them to the screen's lifecycle.
 * Binding to a lifecycle means CameraX opens the camera when the app is visible and closes it
 * automatically when it goes to the background: no manual open/close bookkeeping.
 */
class CameraController(
    private val context: Context,
    private val processor: FilmSurfaceProcessor,
) {
    var camera: Camera? = null
        private set

    fun bind(owner: LifecycleOwner, previewView: PreviewView, lensFacing: Int = CameraSelector.LENS_FACING_BACK) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            // The effect sits between the camera and the preview: every preview frame goes
            // through FilmSurfaceProcessor (our shader) before reaching the screen.
            val group = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addEffect(FilmEffect(CameraEffect.PREVIEW, processor))
                .build()
            provider.unbindAll()
            try {
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                camera = provider.bindToLifecycle(owner, selector, group)
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
