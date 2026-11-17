package com.nitin.filmcam.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/** M1: binds a plain (unfiltered) preview. */
class CameraController(private val context: Context) {
    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            try {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                Log.e("CameraController", "bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
