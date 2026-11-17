package com.nitin.filmcam.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Adds files to the shared gallery via MediaStore. On Android 10+ an app may insert its own
 * media into Pictures/ and Movies/ without any storage permission.
 *
 * IS_PENDING = 1 while writing hides the half-written file from the gallery; we flip it to 0
 * when done.
 */
object MediaStoreSaver {

    /** Copies a finished JPEG file into Pictures/FilmCam. Returns the gallery Uri. */
    fun saveJpeg(context: Context, file: File, displayName: String, dateTakenMs: Long): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, FileNames.PHOTO_DIR)
            put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMs)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("cannot open output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    /** ContentValues for a new video; CameraX's Recorder writes the file itself. */
    fun videoValues(displayName: String): ContentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, FileNames.VIDEO_DIR)
    }
}
