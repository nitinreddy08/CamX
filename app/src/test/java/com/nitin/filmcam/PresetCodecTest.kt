package com.nitin.filmcam

import com.nitin.filmcam.filter.AppSettings
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.filter.PresetCodec
import com.nitin.filmcam.filter.Tint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetCodecTest {

    @Test
    fun paramsRoundTrip() {
        val p = FilmParams(intensity = 0.5f, grainAmount = 0.2f, shadowTint = Tint(0.1f, -0.2f, 0.3f))
        assertEquals(p, PresetCodec.decodeParams(PresetCodec.encodeParams(p)))
    }

    @Test
    fun defaultsRoundTrip() {
        assertEquals(FilmParams.DEFAULT, PresetCodec.decodeParams(PresetCodec.encodeParams(FilmParams.DEFAULT)))
    }

    @Test
    fun missingOrGarbageFallsBackToDefaults() {
        assertEquals(FilmParams.DEFAULT, PresetCodec.decodeParams(null))
        assertEquals(FilmParams.DEFAULT, PresetCodec.decodeParams(""))
        assertEquals(FilmParams.DEFAULT, PresetCodec.decodeParams("{not json"))
    }

    @Test
    fun unknownAndMissingKeysAreTolerated() {
        // e.g. a preset saved by a future version with an extra field, missing others
        val p = PresetCodec.decodeParams("""{"intensity":0.4,"someFutureKnob":3}""")
        assertEquals(0.4f, p.intensity, 1e-6f)
        assertEquals(FilmParams.DEFAULT.grainAmount, p.grainAmount, 1e-6f)
    }

    @Test
    fun presetsAlwaysContainFieldFirst() {
        val decoded = PresetCodec.decodePresets(null)
        assertEquals(listOf("Field"), decoded.keys.toList())
        assertEquals(FilmParams.DEFAULT, decoded["Field"])
    }

    @Test
    fun presetsRoundTripKeepOrder() {
        val map = linkedMapOf(
            "Soft" to FilmParams(softness = 0.8f),
            "Grainy" to FilmParams(grainAmount = 0.2f),
        )
        val decoded = PresetCodec.decodePresets(PresetCodec.encodePresets(map))
        assertEquals(listOf("Field", "Soft", "Grainy"), decoded.keys.toList())
        assertEquals(0.8f, decoded["Soft"]!!.softness, 1e-6f)
    }

    @Test
    fun builtInFieldCannotBeOverwritten() {
        val decoded = PresetCodec.withBuiltIns(mapOf("Field" to FilmParams(intensity = 0f)))
        assertEquals(FilmParams.DEFAULT, decoded["Field"])
    }

    @Test
    fun presetNameCleaning() {
        assertEquals("Golden hour", PresetCodec.cleanPresetName("  Golden   hour "))
        assertNull(PresetCodec.cleanPresetName("   "))
        assertNull(PresetCodec.cleanPresetName("field"))
        assertNull(PresetCodec.cleanPresetName("x".repeat(40)))
    }

    @Test
    fun settingsRoundTrip() {
        val s = AppSettings(saveOriginal = true, video4k = true, ratio43 = false)
        assertEquals(s, PresetCodec.decodeSettings(PresetCodec.encodeSettings(s)))
        assertTrue(PresetCodec.decodeSettings(null).ratio43)
    }
}
