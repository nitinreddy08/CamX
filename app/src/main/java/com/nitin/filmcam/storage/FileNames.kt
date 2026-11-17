package com.nitin.filmcam.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Gallery file names and folders. Pure Kotlin so it is unit-testable. */
object FileNames {
    const val ALBUM = "FilmCam"
    const val PHOTO_DIR = "Pictures/$ALBUM"
    const val VIDEO_DIR = "Movies/$ALBUM"

    private fun stamp(date: Date, timeZone: TimeZone): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { this.timeZone = timeZone }.format(date)

    /** IMG_yyyyMMdd_HHmmss.jpg (or IMG_..._original.jpg for the unfiltered copy). */
    fun photo(date: Date = Date(), original: Boolean = false, timeZone: TimeZone = TimeZone.getDefault()): String =
        "IMG_${stamp(date, timeZone)}${if (original) "_original" else ""}.jpg"

    /** VID_yyyyMMdd_HHmmss.mp4 */
    fun video(date: Date = Date(), timeZone: TimeZone = TimeZone.getDefault()): String =
        "VID_${stamp(date, timeZone)}.mp4"

    /** EXIF date format "yyyy:MM:dd HH:mm:ss". */
    fun exifDate(date: Date, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { this.timeZone = timeZone }.format(date)
}
