package com.nitin.filmcam.filter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** App-level settings (not part of the look). */
@Serializable
data class AppSettings(
    val saveOriginal: Boolean = false,
    val video4k: Boolean = false,
    val ratio43: Boolean = true,
)

/**
 * JSON encoding for FilmParams, named presets and settings. Pure Kotlin (unit tested).
 * Decoding is forgiving: unknown keys are ignored and missing keys take defaults, so presets
 * saved by an older/newer version of the app still load; garbage falls back to defaults.
 */
object PresetCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Serializable
    private data class PresetFile(val presets: Map<String, FilmParams> = emptyMap())

    fun encodeParams(p: FilmParams): String = json.encodeToString(FilmParams.serializer(), p)

    fun decodeParams(s: String?): FilmParams =
        if (s.isNullOrBlank()) FilmParams.DEFAULT
        else runCatching { json.decodeFromString(FilmParams.serializer(), s) }.getOrDefault(FilmParams.DEFAULT)

    fun encodePresets(presets: Map<String, FilmParams>): String =
        json.encodeToString(PresetFile.serializer(), PresetFile(presets))

    /** Always starts with the built-in presets ("Field", "Golden"). */
    fun decodePresets(s: String?): Map<String, FilmParams> {
        val stored = if (s.isNullOrBlank()) emptyMap()
        else runCatching { json.decodeFromString(PresetFile.serializer(), s).presets }.getOrDefault(emptyMap())
        return withBuiltIns(stored)
    }

    fun withBuiltIns(presets: Map<String, FilmParams>): Map<String, FilmParams> {
        val out = LinkedHashMap<String, FilmParams>(FilmParams.BUILT_INS)
        presets.forEach { (k, v) -> if (k !in FilmParams.BUILT_INS) out[k] = v }
        return out
    }

    fun encodeSettings(s: AppSettings): String = json.encodeToString(AppSettings.serializer(), s)

    fun decodeSettings(s: String?): AppSettings =
        if (s.isNullOrBlank()) AppSettings()
        else runCatching { json.decodeFromString(AppSettings.serializer(), s) }.getOrDefault(AppSettings())

    /** Trims and validates a user-typed preset name. Returns null if unusable. */
    fun cleanPresetName(raw: String): String? {
        val name = raw.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty() || name.length > 32) return null
        if (FilmParams.BUILT_INS.keys.any { it.equals(name, ignoreCase = true) }) return null
        return name
    }
}
