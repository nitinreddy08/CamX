package com.nitin.filmcam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.gl.ExifTransform
import com.nitin.filmcam.gl.PhotoFilterRenderer
import com.nitin.filmcam.ui.theme.Cream
import com.nitin.filmcam.ui.theme.InkSoft
import com.nitin.filmcam.ui.theme.Terracotta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class DebugResult(
    val before: Bitmap,
    val after: Bitmap,
    val crop1080: Bitmap,
    val cropFull: Bitmap,
    val fullSize: String,
    val fullMs: Long,
    val smallMs: Long,
)

/**
 * Hidden test screen (Settings → Shader test screen).
 *  1. Before / after: a bundled test image (or one of your photos) through PhotoFilterRenderer
 *     with the CURRENT filter settings, so you can tune the look without pointing the camera.
 *  2. Grain consistency: the same image filtered at 1080p and at full size; the centre crops of
 *     both are shown at the same on-screen size. The grain should look the same size.
 */
@Composable
fun DebugShaderScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val params by vm.params.collectAsState()
    var source by remember { mutableStateOf<Uri?>(null) }
    var result by remember { mutableStateOf<DebugResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) source = uri
    }

    LaunchedEffect(source, params) {
        busy = true
        error = null
        try {
            result = withContext(Dispatchers.Default) { runDebug(context, source, params, vm.photoRenderer) }
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        }
        busy = false
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
            Pill("Test image", selected = source == null, onClick = { source = null })
            Pill("Pick a photo", selected = source != null, onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
        }
        Spacer(Modifier.height(12.dp))
        Text("Shader test", style = MaterialTheme.typography.headlineSmall)
        Text("Uses your current filter settings (change them on the camera screen).",
            style = MaterialTheme.typography.bodySmall, color = InkSoft)
        Spacer(Modifier.height(12.dp))

        if (busy) CircularProgressIndicator(color = Terracotta)
        error?.let { Text("Error: $it", color = Terracotta) }

        result?.let { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledImage("Before", r.before, Modifier.weight(1f))
                LabeledImage("After", r.after, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Full size ${r.fullSize} filtered in ${r.fullMs} ms (1080p version: ${r.smallMs} ms).",
                style = MaterialTheme.typography.bodySmall, color = InkSoft)
            Spacer(Modifier.height(20.dp))
            Text("Grain consistency", style = MaterialTheme.typography.titleMedium, color = Terracotta)
            Text("Same centre region of the frame, filtered at 1080p (left) and at full resolution (right), " +
                "shown at the same size. The grain should look about the same size in both.",
                style = MaterialTheme.typography.bodySmall, color = InkSoft)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledImage("1080p", r.crop1080, Modifier.weight(1f), square = true)
                LabeledImage("Full size", r.cropFull, Modifier.weight(1f), square = true)
            }
        }
    }
}

@Composable
private fun LabeledImage(label: String, bitmap: Bitmap, modifier: Modifier, square: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = InkSoft)
        Image(
            bitmap.asImageBitmap(),
            contentDescription = label,
            contentScale = if (square) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().then(
                if (square) Modifier.aspectRatio(1f) else Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height)
            ),
        )
    }
}

private fun runDebug(context: android.content.Context, source: Uri?, params: FilmParams, renderer: PhotoFilterRenderer): DebugResult {
    // Load the source at full resolution (capped by the GPU's texture limit).
    var orientation = ExifTransform.NORMAL
    val bytes = if (source == null) {
        context.assets.open("test/test_scene.jpg").use { it.readBytes() }
    } else {
        context.contentResolver.openInputStream(source)!!.use { it.readBytes() }
    }
    if (source != null) {
        orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifTransform.NORMAL)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val maxTex = renderer.maxTextureSize()
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxTex) sample *= 2
    val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: error("cannot decode image")

    // Same image scaled so its short side is 1080 px, like a 1080p preview frame.
    val s = 1080f / min(full.width, full.height)
    val small = Bitmap.createScaledBitmap(full, (full.width * s).roundToInt(), (full.height * s).roundToInt(), true)

    var t = System.currentTimeMillis()
    val outFull = renderer.render(full, params, orientation, recycleSource = false)
    val fullMs = System.currentTimeMillis() - t
    t = System.currentTimeMillis()
    val outSmall = renderer.render(small, params, orientation, recycleSource = false)
    val smallMs = System.currentTimeMillis() - t

    // Display copies, ~1080 px wide max.
    val beforeUpright = renderer.render(small, params, orientation, recycleSource = true, bypass = true)
    val after = scaleToWidth(outSmall, 1080)

    // Centre crop: 20% of the short side, same frame fraction in both.
    fun centreCrop(b: Bitmap): Bitmap {
        val side = (min(b.width, b.height) * 0.2f).roundToInt()
        return Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    }
    val cropSmall = centreCrop(outSmall)
    val cropFull = centreCrop(outFull)
    val fullSize = "${outFull.width}×${outFull.height}"
    outFull.recycle()
    full.recycle()
    return DebugResult(beforeUpright, after, cropSmall, cropFull, fullSize, fullMs, smallMs)
}

private fun scaleToWidth(b: Bitmap, w: Int): Bitmap =
    if (b.width <= w) b else Bitmap.createScaledBitmap(b, w, (b.height * w.toFloat() / b.width).roundToInt(), true)
