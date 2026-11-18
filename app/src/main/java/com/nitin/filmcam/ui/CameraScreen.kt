package com.nitin.filmcam.ui

import android.content.Intent
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nitin.filmcam.camera.CaptureMode
import com.nitin.filmcam.camera.Ratio
import com.nitin.filmcam.ui.theme.Cream
import com.nitin.filmcam.ui.theme.Ink
import com.nitin.filmcam.ui.theme.Paper
import com.nitin.filmcam.ui.theme.RecordRed
import com.nitin.filmcam.ui.theme.Terracotta
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CameraScreen(vm: CameraViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val ui by vm.ui.collectAsState()
    val supports60 by vm.supports60.collectAsState()
    val params by vm.params.collectAsState()
    val presets by vm.presets.collectAsState()
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    val config = ui.config
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    // Real frame rate from the camera, refreshed twice a second (so you can see 60 fps is real).
    var measuredFps by remember { mutableStateOf(0f) }
    LaunchedEffect(config.mode) {
        while (true) {
            measuredFps = vm.measuredFps()
            delay(500)
        }
    }

    // The UI is portrait-locked, so watch the physical orientation ourselves and tell CameraX,
    // which then tags photos/videos with the right rotation.
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val rotation = when (degrees) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                vm.setDeviceRotation(rotation)
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    // Bind the camera while this screen is shown; release it when leaving (e.g. to settings).
    DisposableEffect(previewView) {
        val view = previewView
        if (view != null) vm.attach(owner, view)
        onDispose { if (view != null) vm.detach() }
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) { delay(1200); focusPoint = null }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cream)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // ---------------------------------------------------------------- top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(config.flash.label, onClick = vm::cycleFlash)
            val isVideo = config.mode == CaptureMode.VIDEO
            val currentRatio = if (isVideo) config.videoRatio else config.ratio
            Pill(
                currentRatio.label,
                enabled = !ui.recording,
                onClick = { vm.setRatio(if (currentRatio == Ratio.R4_3) Ratio.R16_9 else Ratio.R4_3) },
            )
            if (isVideo) {
                // Show the rate that will really be used: 60 only if a camera allows it.
                val sixty = config.videoFps == 60 && supports60
                Pill(
                    (if (sixty) 60 else 30).toString() + " fps",
                    enabled = !ui.recording,
                    selected = sixty,
                    onClick = { vm.setVideo60(!sixty) },
                )
            }
            Spacer(Modifier.weight(1f))
            Pill("Settings", onClick = onOpenSettings)
        }

        // ---------------------------------------------------------------- preview
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        // FIT_CENTER shows the whole frame that will be captured (no hidden crop).
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        setBackgroundColor(android.graphics.Color.rgb(0xF4, 0xEF, 0xE6))
                    }.also { previewView = it }
                },
            )
            // Gesture layer on top of the preview: tap = focus, pinch = zoom.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) vm.zoomBy(zoom) }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { off -> focusPoint = off; vm.focusAt(off.x, off.y) },
                            // Hold-to-compare: long-press shows the unfiltered image while held.
                            onLongPress = { vm.setComparing(true) },
                            onPress = {
                                tryAwaitRelease()
                                vm.setComparing(false)
                            },
                        )
                    }
            )
            focusPoint?.let { p ->
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White, radius = 36.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
                }
            }
            // Overlays
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (ui.recording) {
                    StatusChip(
                        "● " + String.format(Locale.US, "%02d:%02d", ui.recordSeconds / 60, ui.recordSeconds % 60),
                        color = RecordRed, textColor = Color.White,
                    )
                }
                if (config.mode == CaptureMode.VIDEO && measuredFps > 0f) {
                    val res = if (config.video4k) "4K" else "1080p"
                    StatusChip(res + " · " + config.videoRatio.label + " · " + measuredFps.roundToInt() + " fps")
                }
                if (ui.comparing) StatusChip("Original")
                if (ui.processingCount > 0) StatusChip("Processing…", spinner = true)
                if (kotlin.math.abs(ui.zoomRatio - 1f) > 0.01f) StatusChip(String.format(Locale.US, "%.1f×", ui.zoomRatio))
            }
        }

        // ---------------------------------------------------------------- filter panel
        AnimatedVisibility(visible = filterOpen) {
            FilterPanel(
                params = params,
                onChange = vm::updateParams,
                onReset = vm::resetParams,
                presetSection = {
                    PresetRow(
                        presets = presets,
                        current = params,
                        onApply = vm::applyPreset,
                        onSave = vm::savePreset,
                        onDelete = vm::deletePreset,
                    )
                },
            )
        }

        // ---------------------------------------------------------------- bottom controls
        Column(
            Modifier
                .fillMaxWidth()
                .background(Paper)
                .padding(top = 10.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // PHOTO · VIDEO toggle
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("PHOTO", selected = config.mode == CaptureMode.PHOTO, enabled = !ui.recording,
                    onClick = { vm.setMode(CaptureMode.PHOTO) })
                Pill("VIDEO", selected = config.mode == CaptureMode.VIDEO, enabled = !ui.recording,
                    onClick = { vm.setMode(CaptureMode.VIDEO) })
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MediaThumbnail(ui.lastMedia, onClick = {
                    ui.lastMedia?.let { uri ->
                        val type = if (ui.lastMediaIsVideo) "video/*" else "image/*"
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { vm.showMessage("No gallery app found") }
                    }
                })
                ShutterButton(
                    isVideo = config.mode == CaptureMode.VIDEO,
                    recording = ui.recording,
                    enabled = true,
                    onClick = { if (config.mode == CaptureMode.PHOTO) vm.takePhoto() else vm.toggleRecording() },
                )
                Pill(
                    if (config.lensFacing == CameraSelector.LENS_FACING_BACK) "Front" else "Back",
                    enabled = ui.hasFrontCamera && !ui.recording,
                    onClick = vm::flipCamera,
                )
            }
            Pill(if (filterOpen) "Filter ▼" else "Filter ▲", selected = filterOpen, onClick = { filterOpen = !filterOpen })
        }
    }
}

@Composable
fun StatusChip(text: String, spinner: Boolean = false, color: Color = Paper, textColor: Color = Ink) {
    Row(
        Modifier
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Terracotta)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
