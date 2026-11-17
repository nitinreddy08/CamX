package com.nitin.filmcam.filter

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.filmStore by preferencesDataStore(name = "filmcam")

/**
 * Persists the current look, named presets and app settings with Jetpack DataStore
 * (an async, crash-safe key-value file). Each value is stored as a JSON string.
 */
class SettingsRepository(private val context: Context) {
    private val keyParams = stringPreferencesKey("current_params")
    private val keyPresets = stringPreferencesKey("presets")
    private val keySettings = stringPreferencesKey("settings")

    val params: Flow<FilmParams> = context.filmStore.data.map { PresetCodec.decodeParams(it[keyParams]) }
    val presets: Flow<Map<String, FilmParams>> = context.filmStore.data.map { PresetCodec.decodePresets(it[keyPresets]) }
    val settings: Flow<AppSettings> = context.filmStore.data.map { PresetCodec.decodeSettings(it[keySettings]) }

    suspend fun loadParams(): FilmParams = params.first()
    suspend fun loadSettings(): AppSettings = settings.first()

    suspend fun saveParams(p: FilmParams) {
        context.filmStore.edit { it[keyParams] = PresetCodec.encodeParams(p) }
    }

    suspend fun savePreset(name: String, p: FilmParams) {
        context.filmStore.edit { prefs ->
            val map = LinkedHashMap(PresetCodec.decodePresets(prefs[keyPresets]))
            map[name] = p
            prefs[keyPresets] = PresetCodec.encodePresets(map)
        }
    }

    suspend fun deletePreset(name: String) {
        if (name == FilmParams.DEFAULT_PRESET_NAME) return
        context.filmStore.edit { prefs ->
            val map = LinkedHashMap(PresetCodec.decodePresets(prefs[keyPresets]))
            map.remove(name)
            prefs[keyPresets] = PresetCodec.encodePresets(map)
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.filmStore.edit { prefs ->
            prefs[keySettings] = PresetCodec.encodeSettings(transform(PresetCodec.decodeSettings(prefs[keySettings])))
        }
    }
}
