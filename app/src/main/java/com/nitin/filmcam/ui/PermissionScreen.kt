package com.nitin.filmcam.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

object Permissions {
    val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    fun hasCamera(context: Context) = granted(context, Manifest.permission.CAMERA)
    fun hasAudio(context: Context) = granted(context, Manifest.permission.RECORD_AUDIO)

    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}

/**
 * Friendly first-launch explanation. The camera permission is required; the microphone is
 * optional (without it, videos are recorded silently).
 */
@Composable
fun PermissionScreen(onResult: () -> Unit) {
    val context = LocalContext.current
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askedOnce = true
        onResult()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("FilmCam", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(
            "To show a live film-look preview and take photos, FilmCam needs the camera.\n\n" +
                "The microphone is used only to record sound with your videos.\n\n" +
                "Photos and videos are saved to your gallery (Pictures/FilmCam and Movies/FilmCam). " +
                "Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = { launcher.launch(Permissions.required) }) {
            Text(if (askedOnce) "Ask again" else "Allow camera & microphone")
        }
        if (askedOnce && !Permissions.hasCamera(context)) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Camera access was denied. If Android stopped asking, enable it in the app settings.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Open app settings") }
        }
    }
}
