package com.nitin.filmcam.filter

import kotlinx.serialization.Serializable

/**
 * A small RGB offset used for split toning. The spec used FloatArray for these, but arrays
 * break data-class equality (two identical FloatArrays are not "==" ), which would make
 * Compose recompose needlessly and make preset comparisons wrong. A tiny data class fixes that.
 */
@Serializable
data class Tint(val r: Float, val g: Float, val b: Float) {
    fun toArray() = floatArrayOf(r, g, b)
}

/**
 * Every knob of the film look. ONE instance is shared by the live preview, video recording
 * and photo processing, so all three always look the same.
 * Defaults are the "Field" preset.
 */
@Serializable
data class FilmParams(
    val intensity: Float = 1.0f,       // 0..1, blend between original and graded
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
) {
    companion object {
        val DEFAULT = FilmParams()
        const val DEFAULT_PRESET_NAME = "Field"
    }
}
