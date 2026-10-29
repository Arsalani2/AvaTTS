package com.example.data.pref

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ThemePalette(val persianTitle: String, val primaryHex: Long) {
    CYBER_VIOLET("بنفش مدرن (Cyber)", 0xFF6366F1),
    EMERALD_FOREST("زمردی جنگلی (Emerald)", 0xFF059669),
    SUNSET_AMBER("غروب کهربایی (Amber)", 0xFFEA580C),
    OCEAN_BLUE("اقیانوس لاجوردی (Ocean)", 0xFF2563EB),
    ROSE_QUARTZ("رز کوارتز (Rose)", 0xFFE11D48)
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themePalette: ThemePalette = ThemePalette.CYBER_VIOLET,
    val defaultChunkSize: Int = 500,
    val lastSelectedVoice: String = "Kore",
    val defaultStorageFolder: String = "TTS",
    val ttsModelName: String = "gemini-2.5-flash-preview-tts",
    val isSquareLayout: Boolean = false,
    val isCircularChart: Boolean = false
)

class UserPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("avatts_user_settings_v1", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val modeStr = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val paletteStr = prefs.getString("theme_palette", ThemePalette.CYBER_VIOLET.name)
            ?: ThemePalette.CYBER_VIOLET.name

        return AppSettings(
            themeMode = try { ThemeMode.valueOf(modeStr) } catch (e: Exception) { ThemeMode.SYSTEM },
            themePalette = try { ThemePalette.valueOf(paletteStr) } catch (e: Exception) { ThemePalette.CYBER_VIOLET },
            defaultChunkSize = prefs.getInt("default_chunk_size", 500),
            lastSelectedVoice = prefs.getString("last_voice", "Kore") ?: "Kore",
            defaultStorageFolder = prefs.getString("default_storage_folder", "TTS") ?: "TTS",
            ttsModelName = prefs.getString("tts_model_name", "gemini-2.5-flash-preview-tts") ?: "gemini-2.5-flash-preview-tts",
            isSquareLayout = prefs.getBoolean("is_square_layout", false),
            isCircularChart = prefs.getBoolean("is_circular_chart", false)
        )
    }

    fun updateSquareLayout(isSquare: Boolean) {
        prefs.edit().putBoolean("is_square_layout", isSquare).apply()
        _settings.value = _settings.value.copy(isSquareLayout = isSquare)
    }

    fun updateCircularChart(isCircular: Boolean) {
        prefs.edit().putBoolean("is_circular_chart", isCircular).apply()
        _settings.value = _settings.value.copy(isCircularChart = isCircular)
    }

    fun updateTtsModelName(modelName: String) {
        val trimmed = modelName.trim().ifBlank { "gemini-2.5-flash-preview-tts" }
        prefs.edit().putString("tts_model_name", trimmed).apply()
        _settings.value = _settings.value.copy(ttsModelName = trimmed)
    }

    fun updateThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    fun updateThemePalette(palette: ThemePalette) {
        prefs.edit().putString("theme_palette", palette.name).apply()
        _settings.value = _settings.value.copy(themePalette = palette)
    }

    fun updateDefaultChunkSize(chunkSize: Int) {
        prefs.edit().putInt("default_chunk_size", chunkSize).apply()
        _settings.value = _settings.value.copy(defaultChunkSize = chunkSize)
    }

    fun updateLastVoice(voiceName: String) {
        prefs.edit().putString("last_voice", voiceName).apply()
        _settings.value = _settings.value.copy(lastSelectedVoice = voiceName)
    }

    fun updateStorageFolder(folder: String) {
        val clean = folder.trim().ifBlank { "TTS" }
        prefs.edit().putString("default_storage_folder", clean).apply()
        _settings.value = _settings.value.copy(defaultStorageFolder = clean)
    }

    fun hasSeededInitialProject(): Boolean {
        return prefs.getBoolean("has_seeded_initial_project", false)
    }

    fun setSeededInitialProject(seeded: Boolean) {
        prefs.edit().putBoolean("has_seeded_initial_project", seeded).apply()
    }

    fun getLastActiveProjectId(): Long {
        return prefs.getLong("last_active_project_id", -1L)
    }

    fun setLastActiveProjectId(id: Long) {
        prefs.edit().putLong("last_active_project_id", id).apply()
    }

    fun resetToDefaults() {
        val seeded = hasSeededInitialProject()
        // BUG-21: lastActiveProjectId حفظ می‌شود تا کاربر بعد از ریسِت به اولین پروژه پرتاب نشود
        val lastActiveProjectId = getLastActiveProjectId()
        prefs.edit().clear().apply()
        setSeededInitialProject(seeded)
        setLastActiveProjectId(lastActiveProjectId)
        _settings.value = AppSettings()
    }
}
