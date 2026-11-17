package com.nitin.filmcam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel

enum class Screen { CAMERA, SETTINGS, DEBUG }

/** Top-level switch: permission screen until the camera is allowed, then camera / settings. */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(Permissions.hasCamera(context)) }
    // Re-check when returning from system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasCamera = Permissions.hasCamera(context) }

    if (!hasCamera) {
        PermissionScreen(onResult = { hasCamera = Permissions.hasCamera(context) })
        return
    }

    val vm: CameraViewModel = viewModel()
    var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
    BackHandler(enabled = screen != Screen.CAMERA) {
        screen = if (screen == Screen.DEBUG) Screen.SETTINGS else Screen.CAMERA
    }
    when (screen) {
        Screen.CAMERA -> CameraScreen(vm, onOpenSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> CameraScreen(vm, onOpenSettings = { screen = Screen.CAMERA })
        Screen.DEBUG -> CameraScreen(vm, onOpenSettings = { screen = Screen.CAMERA })
    }
}
