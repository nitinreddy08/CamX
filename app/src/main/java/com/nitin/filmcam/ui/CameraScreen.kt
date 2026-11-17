package com.nitin.filmcam.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nitin.filmcam.camera.CameraController

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val controller = remember { CameraController(context) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).also {
                it.scaleType = PreviewView.ScaleType.FIT_CENTER
                controller.bind(owner, it)
            }
        },
    )
}
