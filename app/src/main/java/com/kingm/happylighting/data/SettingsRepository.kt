package com.kingm.happylighting.data

import android.content.Context
import com.kingm.happylighting.model.AppStyleState
import com.kingm.happylighting.model.MainUiState
import com.kingm.happylighting.model.PickerMode
import com.kingm.happylighting.model.SavedDreamPreset
import com.kingm.happylighting.model.SavedSwatch

class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("happylighting", Context.MODE_PRIVATE)

    fun loadDefaults(): MainUiState = MainUiState(
        savedDeviceAddress = preferences.getString(KEY_LAST_DEVICE_ADDRESS, null),
        savedDeviceName = preferences.getString(KEY_LAST_DEVICE_NAME, null),
        brightness = preferences.getInt(KEY_BRIGHTNESS, 100),
        speed = preferences.getInt(KEY_SPEED, 60),
        pickerMode = preferences.getString(KEY_PICKER_MODE, PickerMode.WHEEL.name)
            ?.let { runCatching { PickerMode.valueOf(it) }.getOrDefault(PickerMode.WHEEL) }
            ?: PickerMode.WHEEL,
        savedSwatches = loadSwatches(),
        savedDreamPresets = loadDreamPresets(),
        appStyle = AppStyleState(
            accentColor = loadColor(KEY_ACCENT_COLOR_R, KEY_ACCENT_COLOR_G, KEY_ACCENT_COLOR_B, Triple(255, 142, 94)),
            saturation = preferences.getInt(KEY_APP_SATURATION, 100).coerceIn(0, 200),
            contrast = preferences.getInt(KEY_APP_CONTRAST, 100).coerceIn(60, 160),
            matchLightColor = preferences.getBoolean(KEY_MATCH_LIGHT_COLOR, false),
            amoledMode = preferences.getBoolean(KEY_AMOLED_MODE, true),
        ),
        baseColor = loadColor(KEY_BASE_COLOR_R, KEY_BASE_COLOR_G, KEY_BASE_COLOR_B, Triple(255, 140, 66)),
        appliedColor = loadColor(KEY_APPLIED_COLOR_R, KEY_APPLIED_COLOR_G, KEY_APPLIED_COLOR_B, Triple(255, 140, 66)),
    )

    fun saveBaseColor(color: Triple<Int, Int, Int>) {
        preferences.edit()
            .putInt(KEY_BASE_COLOR_R, color.first)
            .putInt(KEY_BASE_COLOR_G, color.second)
            .putInt(KEY_BASE_COLOR_B, color.third)
            .apply()
    }

    fun saveAppliedColor(color: Triple<Int, Int, Int>) {
        preferences.edit()
            .putInt(KEY_APPLIED_COLOR_R, color.first)
            .putInt(KEY_APPLIED_COLOR_G, color.second)
            .putInt(KEY_APPLIED_COLOR_B, color.third)
            .apply()
    }

    fun saveBrightness(value: Int) {
        preferences.edit().putInt(KEY_BRIGHTNESS, value).apply()
    }

    fun saveSpeed(value: Int) {
        preferences.edit().putInt(KEY_SPEED, value).apply()
    }

    fun savePickerMode(mode: PickerMode) {
        preferences.edit().putString(KEY_PICKER_MODE, mode.name).apply()
    }

    fun saveAppStyle(style: AppStyleState) {
        preferences.edit()
            .putInt(KEY_ACCENT_COLOR_R, style.accentColor.first)
            .putInt(KEY_ACCENT_COLOR_G, style.accentColor.second)
            .putInt(KEY_ACCENT_COLOR_B, style.accentColor.third)
            .putInt(KEY_APP_SATURATION, style.saturation)
            .putInt(KEY_APP_CONTRAST, style.contrast)
            .putBoolean(KEY_MATCH_LIGHT_COLOR, style.matchLightColor)
            .putBoolean(KEY_AMOLED_MODE, style.amoledMode)
            .apply()
    }

    fun saveSwatches(swatches: List<SavedSwatch>) {
        val encoded = swatches.joinToString(";") { swatch ->
            listOf(
                swatch.id.toString(),
                swatch.color.first.toString(),
                swatch.color.second.toString(),
                swatch.color.third.toString(),
                swatch.brightness.toString(),
            ).joinToString(",")
        }
        preferences.edit().putString(KEY_SWATCHES, encoded).apply()
    }

    fun saveDreamPresets(presets: List<SavedDreamPreset>) {
        val encoded = presets.joinToString(";") { preset ->
            val safeName = preset.name.replace("|", " ").replace(";", " ")
            "${preset.mode}|$safeName"
        }
        preferences.edit().putString(KEY_DREAM_PRESETS, encoded).apply()
    }

    fun saveDevice(address: String?, name: String?) {
        preferences.edit()
            .putString(KEY_LAST_DEVICE_ADDRESS, address)
            .putString(KEY_LAST_DEVICE_NAME, name)
            .apply()
    }

    private fun loadDreamPresets(): List<SavedDreamPreset> =
        preferences.getString(KEY_DREAM_PRESETS, null)
            ?.split(";")
            ?.mapNotNull { entry ->
                val separator = entry.indexOf('|')
                if (separator <= 0 || separator >= entry.lastIndex) {
                    return@mapNotNull null
                }
                val mode = entry.substring(0, separator).toIntOrNull() ?: return@mapNotNull null
                val name = entry.substring(separator + 1).trim()
                if (name.isBlank()) return@mapNotNull null
                SavedDreamPreset(mode = mode.coerceIn(0, 255), name = name)
            }
            ?: emptyList()

    private fun loadColor(
        keyR: String,
        keyG: String,
        keyB: String,
        fallback: Triple<Int, Int, Int>,
    ): Triple<Int, Int, Int> = Triple(
        preferences.getInt(keyR, fallback.first),
        preferences.getInt(keyG, fallback.second),
        preferences.getInt(keyB, fallback.third),
    )

    private fun loadSwatches(): List<SavedSwatch> =
        preferences.getString(KEY_SWATCHES, null)
            ?.split(";")
            ?.mapNotNull { entry ->
                val parts = entry.split(",")
                if (parts.size !in 4..5) {
                    return@mapNotNull null
                }
                val id = parts[0].toLongOrNull() ?: return@mapNotNull null
                val red = parts[1].toIntOrNull() ?: return@mapNotNull null
                val green = parts[2].toIntOrNull() ?: return@mapNotNull null
                val blue = parts[3].toIntOrNull() ?: return@mapNotNull null
                val brightness = parts.getOrNull(4)?.toIntOrNull() ?: 100
                SavedSwatch(id = id, color = Triple(red, green, blue), brightness = brightness.coerceIn(1, 100))
            }
            ?: emptyList()

    private companion object {
        const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        const val KEY_LAST_DEVICE_NAME = "last_device_name"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_SPEED = "speed"
        const val KEY_PICKER_MODE = "picker_mode"
        const val KEY_SWATCHES = "swatches"
        const val KEY_DREAM_PRESETS = "dream_presets"
        const val KEY_ACCENT_COLOR_R = "accent_color_r"
        const val KEY_ACCENT_COLOR_G = "accent_color_g"
        const val KEY_ACCENT_COLOR_B = "accent_color_b"
        const val KEY_APP_SATURATION = "app_saturation"
        const val KEY_APP_CONTRAST = "app_contrast"
        const val KEY_MATCH_LIGHT_COLOR = "match_light_color"
        const val KEY_AMOLED_MODE = "amoled_mode"
        const val KEY_BASE_COLOR_R = "base_color_r"
        const val KEY_BASE_COLOR_G = "base_color_g"
        const val KEY_BASE_COLOR_B = "base_color_b"
        const val KEY_APPLIED_COLOR_R = "applied_color_r"
        const val KEY_APPLIED_COLOR_G = "applied_color_g"
        const val KEY_APPLIED_COLOR_B = "applied_color_b"
    }
}
