package com.nitin.filmcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nitin.filmcam.filter.AppSettings
import com.nitin.filmcam.ui.theme.Cream
import com.nitin.filmcam.ui.theme.InkSoft
import com.nitin.filmcam.ui.theme.Sand
import com.nitin.filmcam.ui.theme.Terracotta

@Composable
fun SettingsScreen(vm: CameraViewModel, onBack: () -> Unit, onOpenDebug: () -> Unit, onOpenCameraInfo: () -> Unit) {
    val settings by vm.settings.collectAsState(initial = AppSettings())
    val supports4k by vm.supports4k.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Cream)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("‹ Back", onClick = onBack)
            Spacer(Modifier.padding(8.dp))
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(20.dp))

        ToggleRow(
            title = "Also save original photo",
            subtitle = "Keeps an unfiltered copy next to each filtered photo (IMG_…_original.jpg).",
            checked = settings.saveOriginal,
            onChange = vm::setSaveOriginal,
        )
        HorizontalDivider(color = Sand)
        ToggleRow(
            title = "Record 4K video",
            subtitle = if (supports4k) "Sharper but heavier: the filter runs on 4x the pixels. 4K records at 30 fps; for 60 fps use 1080p."
            else "This camera doesn't offer 4K to apps (or open the camera once to check). 1080p is used.",
            checked = settings.video4k && supports4k,
            enabled = supports4k,
            onChange = vm::setVideo4k,
        )
        HorizontalDivider(color = Sand)
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenCameraInfo)
                .padding(vertical = 14.dp)
        ) {
            Text("Camera info (60 fps check)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Lists every camera the phone lets apps use and which frame rates each allows.",
                style = MaterialTheme.typography.bodySmall, color = InkSoft,
            )
        }
        HorizontalDivider(color = Sand)
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDebug)
                .padding(vertical = 14.dp)
        ) {
            Text("Shader test screen", style = MaterialTheme.typography.titleMedium)
            Text(
                "Runs a test image through the photo filter with your current settings: before/after and a grain-size check.",
                style = MaterialTheme.typography.bodySmall, color = InkSoft,
            )
        }
        HorizontalDivider(color = Sand)
        Spacer(Modifier.height(20.dp))
        Text("About", style = MaterialTheme.typography.titleMedium, color = Terracotta)
        Spacer(Modifier.height(6.dp))
        Text(
            "FilmCam applies its film look on the GPU to the live preview, to videos while recording, " +
                "and to full-resolution photos after capture. Photos are saved to Pictures/FilmCam, videos to Movies/FilmCam.\n\n" +
                "This app uses the standard Android camera interface, so it doesn't get the phone maker's own " +
                "multi-frame HDR / night processing, and some lenses may not be available to it. " +
                "Light matters most: golden hour, backlight and close-ups suit the look best.",
            style = MaterialTheme.typography.bodySmall, color = InkSoft,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else InkSoft)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
        Spacer(Modifier.padding(6.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Terracotta),
        )
    }
}
