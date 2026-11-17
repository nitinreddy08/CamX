package com.nitin.filmcam.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.gl.PhotoFilterRenderer
import java.io.File
import java.util.Date
import kotlin.math.max

/**
 * The photo pipeline after the shutter:
 *   unfiltered JPEG (from CameraX) -> decode -> GPU film filter (+ EXIF rotation) ->
 *   JPEG quality 95 -> copy basic EXIF -> MediaStore (Pictures/FilmCam)
 * Runs on a background thread; the camera keeps running meanwhile.
 */
class PhotoProcessor(private val context: Context, private val renderer: PhotoFilterRenderer) {

    data class Result(val uri: Uri, val millis: Long, val width: Int, val height: Int)

    fun process(captured: File, params: FilmParams, saveOriginal: Boolean): Result {
        val start = System.currentTimeMillis()
        val now = Date()

        // EXIF from the camera's file: orientation (which way is up) and capture time.
        val srcExif = ExifInterface(captured)
        val orientation = srcExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val dateOriginal = srcExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: srcExif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: FileNames.exifDate(now)

        // Decode at full size unless the GPU can't hold it in one texture (very rare on new phones).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(captured.path, bounds)
        val maxTex = renderer.maxTextureSize()
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxTex) sample *= 2
        val bitmap = BitmapFactory.decodeFile(captured.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Could not decode captured photo")

        val filtered = renderer.render(bitmap, params, exifOrientation = orientation)

        val outFile = File(context.cacheDir, "filtered_${System.nanoTime()}.jpg")
        try {
            outFile.outputStream().use { filtered.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            val w = filtered.width
            val h = filtered.height
            filtered.recycle()

            // Pixels are already upright, so orientation becomes NORMAL. Keep the capture date.
            ExifInterface(outFile).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateOriginal)
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateOriginal)
                setAttribute(ExifInterface.TAG_DATETIME, FileNames.exifDate(now))
                for (tag in COPIED_TAGS) srcExif.getAttribute(tag)?.let { setAttribute(tag, it) }
                saveAttributes()
            }

            val uri = MediaStoreSaver.saveJpeg(context, outFile, FileNames.photo(now), now.time)
            if (saveOriginal) {
                MediaStoreSaver.saveJpeg(context, captured, FileNames.photo(now, original = true), now.time)
            }
            return Result(uri, System.currentTimeMillis() - start, w, h)
        } finally {
            outFile.delete()
        }
    }

    private companion object {
        // Exposure info is harmless and useful when reviewing shots; device make/model omitted.
        val COPIED_TAGS = listOf(
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        )
    }
}
