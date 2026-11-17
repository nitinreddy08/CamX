package com.nitin.filmcam.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CameraScreen(vm: CameraViewModel = viewModel()) {
    val owner = LocalLifecycleOwner.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).also {
                it.scaleType = PreviewView.ScaleType.FIT_CENTER
                vm.controller.bind(owner, it)
            }
        },
    )
}
