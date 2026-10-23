package com.magpie.pocket.core.security

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.magpie.pocket.core.model.DisplayPreferences
import com.magpie.pocket.core.model.GlassTier
import com.magpie.pocket.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "magpie_pocket_settings")

class PreferencesManager(private val context: Context) {

    val displayPreferencesFlow: Flow<DisplayPreferences> = context.dataStore.data.map { prefs ->
        val themeModeStr = prefs[KEY_THEME_MODE] ?: ThemeMode.SYSTEM.name
        val glassTierStr = prefs[KEY_GLASS_TIER] ?: GlassTier.CLEAR.name
        val reduceMotion = prefs[KEY_REDUCE_MOTION] ?: false
        val maskAccounts = prefs[KEY_MASK_ACCOUNTS] ?: true
        val keepLiveConnection = prefs[KEY_KEEP_LIVE_CONNECTION] ?: true

        DisplayPreferences(
            themeMode = try { ThemeMode.valueOf(themeModeStr) } catch (_: Exception) { ThemeMode.SYSTEM },
            glassTier = try { GlassTier.valueOf(glassTierStr) } catch (_: Exception) { GlassTier.CLEAR },
            reduceMotion = reduceMotion,
            maskAccountNames = maskAccounts,
            keepLiveConnectionInBackground = keepLiveConnection
        )
    }

    val lastActiveTabFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_ACTIVE_TAB]
    }

    val lastGroupIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_GROUP_ID]
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    suspend fun updateGlassTier(tier: GlassTier) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GLASS_TIER] = tier.name
        }
    }

    suspend fun updateReduceMotion(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_REDUCE_MOTION] = enabled
        }
    }

    suspend fun updateMaskAccounts(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MASK_ACCOUNTS] = enabled
        }
    }

    suspend fun updateKeepLiveConnectionInBackground(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KEEP_LIVE_CONNECTION] = enabled
        }
    }

    suspend fun saveLastActiveTab(tab: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_ACTIVE_TAB] = tab
        }
    }

    suspend fun saveLastGroupId(groupId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_GROUP_ID] = groupId
        }
    }

    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("pref_theme_mode")
        private val KEY_GLASS_TIER = stringPreferencesKey("pref_glass_tier")
        private val KEY_REDUCE_MOTION = booleanPreferencesKey("pref_reduce_motion")
        private val KEY_MASK_ACCOUNTS = booleanPreferencesKey("pref_mask_accounts")
        private val KEY_KEEP_LIVE_CONNECTION = booleanPreferencesKey("pref_keep_live_connection")
        private val KEY_LAST_ACTIVE_TAB = stringPreferencesKey("pref_last_tab")
        private val KEY_LAST_GROUP_ID = stringPreferencesKey("pref_last_group_id")
    }
}
