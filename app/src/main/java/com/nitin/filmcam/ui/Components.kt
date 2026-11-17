package com.nitin.filmcam.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitin.filmcam.ui.theme.Ink
import com.nitin.filmcam.ui.theme.InkSoft
import com.nitin.filmcam.ui.theme.Paper
import com.nitin.filmcam.ui.theme.RecordRed
import com.nitin.filmcam.ui.theme.Sand
import com.nitin.filmcam.ui.theme.Terracotta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Small rounded text button used in the top bar ("Flash off", "4:3", "Settings"). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val bg = if (selected) Terracotta else Paper
    val fg = when {
        !enabled -> InkSoft.copy(alpha = 0.5f)
        selected -> Color.White
        else -> Ink
    }
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, Sand, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Shutter button drawn with Canvas.
 * Photo: dark ring + terracotta disc. Video: red disc; while recording a red rounded square.
 */
@Composable
fun ShutterButton(isVideo: Boolean, recording: Boolean, enabled: Boolean, onClick: () -> Unit, size: Dp = 76.dp) {
    Canvas(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        val r = this.size.minDimension / 2
        drawCircle(Ink, radius = r - 3.dp.toPx(), style = Stroke(width = 4.dp.toPx()))
        val inner = if (isVideo) RecordRed else Terracotta
        val alpha = if (enabled) 1f else 0.4f
        if (recording) {
            val s = r * 0.8f
            drawRoundRect(
                inner,
                topLeft = Offset(center.x - s / 2, center.y - s / 2),
                size = GSize(s, s),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
        } else {
            drawCircle(inner.copy(alpha = alpha), radius = r - 10.dp.toPx())
        }
    }
}

/** Thumbnail of the last photo/video; loads asynchronously from MediaStore. */
@Composable
fun MediaThumbnail(uri: Uri?, onClick: () -> Unit, size: Dp = 52.dp) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Sand)
            .border(1.dp, Ink.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .clickable(enabled = uri != null, onClick = onClick),
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "Last capture", contentScale = ContentScale.Crop,
                modifier = Modifier.size(size))
        }
    }
}

/** A labelled slider with the current value shown on the right. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    decimals: Int = 2,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "%.${decimals}f", value), style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Terracotta,
                activeTrackColor = Terracotta,
                inactiveTrackColor = Sand,
            ),
        )
    }
}
