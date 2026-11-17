package com.nitin.filmcam.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Top-level switch: permission screen until the camera is allowed, then the camera. */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(Permissions.hasCamera(context)) }
    // Re-check when returning from system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasCamera = Permissions.hasCamera(context) }

    if (!hasCamera) {
        PermissionScreen(onResult = { hasCamera = Permissions.hasCamera(context) })
    } else {
        CameraScreen()
    }
}
