package com.nitin.filmcam.filter

import kotlinx.serialization.Serializable

/**
 * A small RGB offset used for split toning. The spec used FloatArray for these, but arrays
 * break data-class equality (two identical FloatArrays are not "=="), which would make
 * Compose recompose needlessly and make preset comparisons wrong. A tiny data class fixes that.
 */
@Serializable
data class Tint(val r: Float, val g: Float, val b: Float) {
    fun toArray() = floatArrayOf(r, g, b)
}

/**
 * Adjustment for one colour band (see [HueBand]).
 * Neutral = saturation 1, brightness 0, shift 0.
 */
@Serializable
data class HueAdjust(
    val saturation: Float = 1f,   // 0 = grey .. 1 = unchanged .. 1.5 = richer
    val brightness: Float = 0f,   // -1 .. 1  (+/-50%); negative = deeper, "denser" colour
    val shift: Float = 0f,        // -1 .. 1  (+/-30 degrees round the colour wheel)
) {
    val isNeutral get() = saturation == 1f && brightness == 0f && shift == 0f
}

/** The 8 colour bands, in the same order as BAND_HUE in film.frag. */
enum class HueBand(val label: String, val hueDegrees: Float) {
    RED("Red", 0f), ORANGE("Orange / skin", 30f), YELLOW("Yellow", 60f), GREEN("Green", 120f),
    TEAL("Teal", 180f), BLUE("Blue", 225f), PURPLE("Purple", 270f), MAGENTA("Magenta", 315f);
}

val NEUTRAL_HUES: List<HueAdjust> = List(HueBand.entries.size) { HueAdjust() }

/**
 * Every knob of the film look. ONE instance is shared by the live preview, video recording
 * and photo processing, so all three always look the same.
 *
 * Engine v1 fields come first (the spec). Engine v2 fields all default to NEUTRAL values that
 * switch them off, so the default instance (= the "Field" preset) renders exactly like v1, and
 * presets saved before v2 load unchanged.
 */
@Serializable
data class FilmParams(
    // ---- v1 ----
    val intensity: Float = 1.0f,       // 0..1, strength of the colour/tone look (not grain)
    val contrast: Float = 0.85f,       // <1 = flatter
    val blackLift: Float = 0.06f,      // raises pure black to this level
    val whiteRoll: Float = 0.95f,      // caps pure white at this level
    val saturation: Float = 0.78f,     // global saturation
    val greenDesat: Float = 0.45f,     // extra desaturation on greens
    val greenToOlive: Float = 0.03f,   // pushes greens toward olive
    val shadowTint: Tint = Tint(-0.010f, 0.000f, 0.035f),     // cool blue-purple
    val midTint: Tint = Tint(0.015f, -0.010f, 0.010f),        // subtle magenta
    val highlightTint: Tint = Tint(0.050f, 0.020f, -0.030f),  // warm peach
    val grainAmount: Float = 0.09f,    // 0..0.25
    val grainSize: Float = 1.6f,       // grain cell size in px at 1080p reference
    val softness: Float = 0.30f,       // 0..1
    val vignette: Float = 0.15f,       // 0..1
    // ---- v2 (neutral defaults) ----
    val exposure: Float = 0f,          // stops, -1..1
    val warmth: Float = 0f,            // -1 cool .. 1 warm
    val filmCurve: Float = 0f,         // 0 = v1 straight contrast, 1 = film toe/shoulder curve
    val dyeMix: Float = 0f,            // 0..1 dye cross-talk
    val pastel: Float = 0f,            // 0..1 highlight/shadow saturation roll-off
    val colorDensity: Float = 0f,      // 0..1 strong colours get deeper
    val hues: List<HueAdjust> = NEUTRAL_HUES,
    val halation: Float = 0f,          // 0..1 red-orange glow around bright light
    val grainDensity: Float = 0f,      // 0 = v1 midtone grain mask, 1 = film density model
    val grainColor: Float = 0f,        // 0 = monochrome grain .. 1 = colour grain
    val filmMotion: Float = 0f,        // 0..1 gate weave + flicker (preview/video only)
) {
    /** Always 8 entries, even if a damaged preset stored fewer. */
    fun hue(band: HueBand): HueAdjust = hues.getOrNull(band.ordinal) ?: HueAdjust()

    fun withHue(band: HueBand, adj: HueAdjust): FilmParams =
        copy(hues = List(HueBand.entries.size) { i -> if (i == band.ordinal) adj else hues.getOrNull(i) ?: HueAdjust() })

    companion object {
        /** "Field": the original spec look. */
        val DEFAULT = FilmParams()
        const val DEFAULT_PRESET_NAME = "Field"

        /**
         * "Golden": tuned with the v2 engine (see README §6, "Golden"). Muted and matte but with
         * clean creamy highlights; reds, oranges, skin and yellows stay rich, greens go dusty
         * olive, blues lean pale teal, purples/magentas fade; warm light, gentle halation, film
         * density grain.
         */
        val GOLDEN = FilmParams(
            intensity = 1.0f,
            exposure = 0f, warmth = 0.16f,
            filmCurve = 1f, contrast = 0.85f, blackLift = 0.095f, whiteRoll = 0.96f,
            saturation = 0.84f, pastel = 0.6f, dyeMix = 0.6f, colorDensity = 0.45f,
            greenDesat = 0f, greenToOlive = 0f,
            hues = listOf(
                HueAdjust(0.95f, -0.10f, 0.10f),  // red: deep, a touch toward orange
                HueAdjust(1.00f, 0f, 0f),         // orange / skin: kept natural
                HueAdjust(1.00f, 0f, -0.20f),     // yellow: leans golden
                HueAdjust(0.55f, -0.08f, -0.50f), // green: dusty olive
                HueAdjust(0.95f, 0f, 0f),         // teal: kept
                HueAdjust(0.70f, -0.05f, -0.50f), // blue: muted, toward teal
                HueAdjust(0.60f, 0f, 0f),         // purple: faded
                HueAdjust(0.65f, 0f, 0f),         // magenta: faded
            ),
            shadowTint = Tint(-0.006f, 0f, 0.022f),
            midTint = Tint(0.010f, -0.007f, 0.006f),
            highlightTint = Tint(0.020f, 0.012f, -0.015f),
            grainAmount = 0.09f, grainSize = 1.8f, grainDensity = 1f, grainColor = 0f,
            halation = 0.3f, softness = 0.30f, vignette = 0.06f,
            filmMotion = 0f,
        )
        const val GOLDEN_PRESET_NAME = "Golden"

        /**
         * "Ochre": tuned to match a reference you liked (rich mustard/rust warm colours, muted
         * steel-blue cools, warm-grey neutrals, heavy FINE monochrome grain that stays strong in
         * the shadows, matte blacks around 0.08-0.10). Measured on the test scene: grain 0.029
         * (reference 0.025-0.036), blacks 0.08, highlights 0.93.
         */
        val OCHRE = GOLDEN.copy(
            warmth = 0.12f, contrast = 0.95f, blackLift = 0.10f, whiteRoll = 0.95f,
            saturation = 0.92f, pastel = 0.4f, dyeMix = 0.5f, colorDensity = 0.55f,
            hues = listOf(
                HueAdjust(1.00f, -0.10f, 0.15f),  // red: deep rust
                HueAdjust(1.10f, 0f, 0f),         // orange / skin: rich
                HueAdjust(1.15f, -0.05f, -0.25f), // yellow: mustard
                HueAdjust(0.50f, -0.08f, -0.50f), // green: olive
                HueAdjust(0.80f, 0f, 0f),         // teal
                HueAdjust(0.70f, -0.10f, -0.30f), // blue: steel
                HueAdjust(0.50f, 0f, 0f),         // purple
                HueAdjust(0.60f, 0f, 0f),         // magenta
            ),
            shadowTint = Tint(-0.004f, 0f, 0.012f),
            midTint = Tint(0.012f, 0.004f, -0.012f),      // warm-grey neutrals
            highlightTint = Tint(0.020f, 0.012f, -0.020f),
            grainAmount = 0.13f, grainSize = 1.3f, grainDensity = 0.7f, grainColor = 0.1f,
            halation = 0.25f, softness = 0.25f, vignette = 0.05f,
        )
        const val OCHRE_PRESET_NAME = "Ochre"

        /** Built-in presets, in display order. They can't be overwritten or deleted. */
        val BUILT_INS: Map<String, FilmParams> = linkedMapOf(
            DEFAULT_PRESET_NAME to DEFAULT,
            GOLDEN_PRESET_NAME to GOLDEN,
            OCHRE_PRESET_NAME to OCHRE,
        )
    }
}
