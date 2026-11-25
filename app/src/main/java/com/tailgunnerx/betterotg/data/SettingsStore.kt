package com.tailgunnerx.betterotg.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tailgunnerx.betterotg.media.ScaleOverlayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.betterOtgDataStore by preferencesDataStore(name = "betterotg_prefs")

data class UserSettings(
    val rotationDegrees: Int = 0,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
    val fillScreen: Boolean = false,
    val watermarkEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
    val scaleOverlay: ScaleOverlayMode = ScaleOverlayMode.NONE,
    val preferredModeKey: String? = null
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val ROTATION = intPreferencesKey("rotation_deg")
        val MIRROR_H = booleanPreferencesKey("mirror_h")
        val MIRROR_V = booleanPreferencesKey("mirror_v")
        val FILL_SCREEN = booleanPreferencesKey("fill_screen")
        val WATERMARK = booleanPreferencesKey("watermark")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SCALE = stringPreferencesKey("scale_overlay")
        val PREFERRED_MODE = stringPreferencesKey("preferred_mode")
    }

    val settingsFlow: Flow<UserSettings> = context.betterOtgDataStore.data.map { p ->
        UserSettings(
            rotationDegrees = p[Keys.ROTATION] ?: 0,
            mirrorH = p[Keys.MIRROR_H] ?: false,
            mirrorV = p[Keys.MIRROR_V] ?: false,
            fillScreen = p[Keys.FILL_SCREEN] ?: false,
            watermarkEnabled = p[Keys.WATERMARK] ?: true,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            scaleOverlay = ScaleOverlayMode.entries.firstOrNull { it.name == p[Keys.SCALE] }
                ?: ScaleOverlayMode.NONE,
            preferredModeKey = p[Keys.PREFERRED_MODE]
        )
    }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.betterOtgDataStore.edit { p ->
            val current = UserSettings(
                rotationDegrees = p[Keys.ROTATION] ?: 0,
                mirrorH = p[Keys.MIRROR_H] ?: false,
                mirrorV = p[Keys.MIRROR_V] ?: false,
                fillScreen = p[Keys.FILL_SCREEN] ?: false,
                watermarkEnabled = p[Keys.WATERMARK] ?: true,
                keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
                scaleOverlay = ScaleOverlayMode.entries.firstOrNull { it.name == p[Keys.SCALE] }
                    ?: ScaleOverlayMode.NONE,
                preferredModeKey = p[Keys.PREFERRED_MODE]
            )
            val next = transform(current)
            p[Keys.ROTATION] = next.rotationDegrees
            p[Keys.MIRROR_H] = next.mirrorH
            p[Keys.MIRROR_V] = next.mirrorV
            p[Keys.FILL_SCREEN] = next.fillScreen
            p[Keys.WATERMARK] = next.watermarkEnabled
            p[Keys.KEEP_SCREEN_ON] = next.keepScreenOn
            p[Keys.SCALE] = next.scaleOverlay.name
            if (next.preferredModeKey != null) {
                p[Keys.PREFERRED_MODE] = next.preferredModeKey
            }
        }
    }
}
