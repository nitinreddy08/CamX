package com.nitin.filmcam

import com.nitin.filmcam.storage.FileNames
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FileNamesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val date = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.NOVEMBER, 17, 6, 5, 9)
    }.time

    @Test fun photoName() = assertEquals("IMG_20261117_060509.jpg", FileNames.photo(date, timeZone = utc))
    @Test fun originalPhotoName() = assertEquals("IMG_20261117_060509_original.jpg", FileNames.photo(date, original = true, timeZone = utc))
    @Test fun videoName() = assertEquals("VID_20261117_060509.mp4", FileNames.video(date, timeZone = utc))
    @Test fun exifDate() = assertEquals("2026:11:17 06:05:09", FileNames.exifDate(date, utc))
    @Test fun folders() {
        assertEquals("Pictures/FilmCam", FileNames.PHOTO_DIR)
        assertEquals("Movies/FilmCam", FileNames.VIDEO_DIR)
    }
}
