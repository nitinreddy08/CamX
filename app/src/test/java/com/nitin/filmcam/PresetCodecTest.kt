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
    fun presetsAlwaysStartWithBuiltIns() {
        val decoded = PresetCodec.decodePresets(null)
        assertEquals(listOf("Field", "Golden", "Ochre"), decoded.keys.toList())
        assertEquals(FilmParams.DEFAULT, decoded["Field"])
        assertEquals(FilmParams.GOLDEN, decoded["Golden"])
    }

    @Test
    fun presetsRoundTripKeepOrder() {
        val map = linkedMapOf(
            "Soft" to FilmParams(softness = 0.8f),
            "Grainy" to FilmParams(grainAmount = 0.2f),
        )
        val decoded = PresetCodec.decodePresets(PresetCodec.encodePresets(map))
        assertEquals(listOf("Field", "Golden", "Ochre", "Soft", "Grainy"), decoded.keys.toList())
        assertEquals(0.8f, decoded["Soft"]!!.softness, 1e-6f)
    }

    @Test
    fun builtInsCannotBeOverwritten() {
        val decoded = PresetCodec.withBuiltIns(mapOf("Field" to FilmParams(intensity = 0f), "Golden" to FilmParams()))
        assertEquals(FilmParams.DEFAULT, decoded["Field"])
        assertEquals(FilmParams.GOLDEN, decoded["Golden"])
    }

    @Test
    fun presetNameCleaning() {
        assertEquals("Golden hour", PresetCodec.cleanPresetName("  Golden   hour "))
        assertNull(PresetCodec.cleanPresetName("   "))
        assertNull(PresetCodec.cleanPresetName("field"))
        assertNull(PresetCodec.cleanPresetName("GOLDEN"))
        assertNull(PresetCodec.cleanPresetName("x".repeat(40)))
    }

    @Test
    fun settingsRoundTrip() {
        val s = AppSettings(saveOriginal = true, video4k = true, ratio43 = false)
        assertEquals(s, PresetCodec.decodeSettings(PresetCodec.encodeSettings(s)))
        assertTrue(PresetCodec.decodeSettings(null).ratio43)
    }

    @Test
    fun goldenRoundTrip() {
        assertEquals(FilmParams.GOLDEN, PresetCodec.decodeParams(PresetCodec.encodeParams(FilmParams.GOLDEN)))
    }

    @Test
    fun presetSavedBeforeEngineV2LoadsWithNeutralNewControls() {
        // JSON exactly as engine v1 wrote it: no exposure/warmth/hues/halation/... keys.
        val v1 = """{"intensity":0.75,"contrast":0.85,"blackLift":0.06,"whiteRoll":0.95,"saturation":0.78,
            "greenDesat":0.45,"greenToOlive":0.03,"shadowTint":{"r":-0.01,"g":0.0,"b":0.035},
            "midTint":{"r":0.015,"g":-0.01,"b":0.01},"highlightTint":{"r":0.05,"g":0.02,"b":-0.03},
            "grainAmount":0.072,"grainSize":1.6,"softness":0.3,"vignette":0.15}"""
        val p = PresetCodec.decodeParams(v1)
        assertEquals(0.75f, p.intensity, 1e-6f)
        assertEquals(0.072f, p.grainAmount, 1e-6f)
        assertEquals(FilmParams.DEFAULT.copy(intensity = 0.75f, grainAmount = 0.072f), p)
    }
}
