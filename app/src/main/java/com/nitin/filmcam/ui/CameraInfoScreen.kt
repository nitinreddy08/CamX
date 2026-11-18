package com.nitin.filmcam.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitin.filmcam.camera.CameraReport
import com.nitin.filmcam.ui.theme.Cream
import com.nitin.filmcam.ui.theme.InkSoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings → Camera info: what each camera allows this app to do (frame rates, lenses). */
@Composable
fun CameraInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val report by produceState("Reading cameras…") {
        value = withContext(Dispatchers.IO) { runCatching { CameraReport.build(context) }.getOrElse { "Error: ${it.message}" } }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Cream)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("‹ Back", onClick = onBack)
            Pill("Copy", onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("FilmCam camera info", report))
            })
        }
        Spacer(Modifier.height(12.dp))
        Text("Camera info", style = MaterialTheme.typography.headlineSmall)
        Text(
            "What each camera lets apps use. \"60 fps for apps: YES\" on any back camera means FilmCam can " +
                "record 60 fps with it (it switches automatically). Tap Copy and send this to whoever is helping you.",
            style = MaterialTheme.typography.bodySmall, color = InkSoft,
        )
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(report, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}
