package com.nitin.filmcam

import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.filter.HueAdjust
import com.nitin.filmcam.filter.HueBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmParamsTest {

    /** Field must keep every engine-v2 control neutral, so it renders exactly like v1. */
    @Test
    fun fieldUsesOnlyNeutralV2Controls() {
        val f = FilmParams.DEFAULT
        assertEquals(0f, f.exposure)
        assertEquals(0f, f.warmth)
        assertEquals(0f, f.filmCurve)
        assertEquals(0f, f.dyeMix)
        assertEquals(0f, f.pastel)
        assertEquals(0f, f.colorDensity)
        assertEquals(0f, f.halation)
        assertEquals(0f, f.grainDensity)
        assertEquals(0f, f.grainColor)
        assertEquals(0f, f.filmMotion)
        assertTrue(f.hues.all { it.isNeutral })
        assertEquals(HueBand.entries.size, f.hues.size)
    }

    @Test
    fun goldenHasOneAdjustmentPerBand() {
        assertEquals(HueBand.entries.size, FilmParams.GOLDEN.hues.size)
        assertFalse(FilmParams.GOLDEN.hue(HueBand.GREEN).isNeutral)
    }

    @Test
    fun withHueChangesOnlyThatBand() {
        val p = FilmParams.DEFAULT.withHue(HueBand.BLUE, HueAdjust(saturation = 0.5f))
        assertEquals(0.5f, p.hue(HueBand.BLUE).saturation)
        HueBand.entries.filter { it != HueBand.BLUE }.forEach { assertTrue(p.hue(it).isNeutral) }
    }

    @Test
    fun missingBandsReadAsNeutral() {
        val p = FilmParams.DEFAULT.copy(hues = listOf(HueAdjust(saturation = 0.2f)))
        assertEquals(0.2f, p.hue(HueBand.RED).saturation)
        assertTrue(p.hue(HueBand.MAGENTA).isNeutral)
        assertEquals(HueBand.entries.size, p.withHue(HueBand.TEAL, HueAdjust(shift = 1f)).hues.size)
    }
}
