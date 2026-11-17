package com.nitin.filmcam

import com.nitin.filmcam.gl.ExifTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks where each corner of the upright output reads from in the sensor-oriented input.
 * Coordinates are (u, v) with v = 0 at the top.
 */
class ExifTransformTest {
    private fun map(o: Int, u: Float, v: Float) = ExifTransform.apply(ExifTransform.texMatrix(o), u, v)
    private fun assertMaps(o: Int, from: Pair<Float, Float>, to: Pair<Float, Float>) {
        val r = map(o, from.first, from.second)
        assertEquals("u for $o", to.first, r.first, 1e-6f)
        assertEquals("v for $o", to.second, r.second, 1e-6f)
    }

    @Test fun normalIsIdentity() {
        assertMaps(ExifTransform.NORMAL, 0.2f to 0.7f, 0.2f to 0.7f)
        assertFalse(ExifTransform.swapsDimensions(ExifTransform.NORMAL))
    }

    @Test fun rotate90Clockwise() {
        // Upright top-left came from the input's bottom-left; top-right from top-left.
        assertMaps(ExifTransform.ROTATE_90, 0f to 0f, 0f to 1f)
        assertMaps(ExifTransform.ROTATE_90, 1f to 0f, 0f to 0f)
        assertMaps(ExifTransform.ROTATE_90, 1f to 1f, 1f to 0f)
        assertTrue(ExifTransform.swapsDimensions(ExifTransform.ROTATE_90))
    }

    @Test fun rotate180() = assertMaps(ExifTransform.ROTATE_180, 0f to 0f, 1f to 1f)

    @Test fun rotate270() {
        assertMaps(ExifTransform.ROTATE_270, 0f to 0f, 1f to 0f)
        assertMaps(ExifTransform.ROTATE_270, 0f to 1f, 0f to 0f)
    }

    @Test fun flips() {
        assertMaps(ExifTransform.FLIP_HORIZONTAL, 0f to 0.3f, 1f to 0.3f)
        assertMaps(ExifTransform.FLIP_VERTICAL, 0.3f to 0f, 0.3f to 1f)
        assertMaps(ExifTransform.TRANSPOSE, 0.2f to 0.9f, 0.9f to 0.2f)
        assertMaps(ExifTransform.TRANSVERSE, 0f to 0f, 1f to 1f)
    }
}
