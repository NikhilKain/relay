package com.vythera.relay.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How clipboard sync with trusted devices behaves. */
enum class ClipboardMode { OFF, ASK, AUTOMATIC }

enum class ColorPreference { WALLPAPER, RELAY }

data class RelaySettings(
    val onboardingComplete: Boolean = false,
    val deviceName: String = "",
    // Universal clipboard is the everyday reason to have Relay, so it is on for trusted devices.
    val clipboardMode: ClipboardMode = ClipboardMode.AUTOMATIC,
    val colors: ColorPreference = ColorPreference.WALLPAPER,
    /** Keep receiving while Relay is not on screen. Uses a low-priority persistent notification. */
    val stayAvailable: Boolean = true,
    /** A folder the user picked for received files (a tree URI), or null for Relay's default folders. */
    val saveFolder: String? = null,
    /** The user's own contact card as JSON, shared only from the Share contact screen. */
    val contactCard: String? = null,
    /** Trusted devices act as one: no Accept prompts, and what they send is ready to paste. */
    val ecosystem: Boolean = true,
    /** Optional, anonymous usage statistics. Off unless the user turns it on. */
    val analytics: Boolean = false,
    /** The permission sheet has been shown once; Settings still offers everything it asked for. */
    val setupAsked: Boolean = false,
    /** Send what is copied on this phone even while Relay is in the background. Needs one-time setup. */
    val instantClipboard: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "relay_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_complete")
        val deviceName = stringPreferencesKey("device_name")
        val clipboard = stringPreferencesKey("clipboard_mode")
        val colors = stringPreferencesKey("colors")
        val stayAvailable = booleanPreferencesKey("stay_available")
        val saveFolder = stringPreferencesKey("save_folder")
        val contactCard = stringPreferencesKey("contact_card")
        val ecosystem = booleanPreferencesKey("ecosystem")
        val instantClipboard = booleanPreferencesKey("instant_clipboard")
        val setupAsked = booleanPreferencesKey("setup_asked")
        val analytics = booleanPreferencesKey("analytics")
    }

    val settings: Flow<RelaySettings> = context.dataStore.data.map { prefs ->
        RelaySettings(
            onboardingComplete = prefs[Keys.onboarding] ?: false,
            deviceName = prefs[Keys.deviceName].orEmpty(),
            clipboardMode = prefs[Keys.clipboard]?.let { runCatching { ClipboardMode.valueOf(it) }.getOrNull() } ?: ClipboardMode.AUTOMATIC,
            colors = prefs[Keys.colors]?.let { runCatching { ColorPreference.valueOf(it) }.getOrNull() } ?: ColorPreference.WALLPAPER,
            stayAvailable = prefs[Keys.stayAvailable] ?: true,
            saveFolder = prefs[Keys.saveFolder],
            contactCard = prefs[Keys.contactCard],
            ecosystem = prefs[Keys.ecosystem] ?: true,
            instantClipboard = prefs[Keys.instantClipboard] ?: true,
            setupAsked = prefs[Keys.setupAsked] ?: false,
            analytics = prefs[Keys.analytics] ?: false,
        )
    }

    suspend fun current(): RelaySettings = settings.first()

    suspend fun completeOnboarding(deviceName: String) = context.dataStore.edit {
        it[Keys.deviceName] = deviceName
        it[Keys.onboarding] = true
    }

    suspend fun setDeviceName(name: String) = context.dataStore.edit { it[Keys.deviceName] = name }
    suspend fun setClipboardMode(mode: ClipboardMode) = context.dataStore.edit { it[Keys.clipboard] = mode.name }
    suspend fun setColors(colors: ColorPreference) = context.dataStore.edit { it[Keys.colors] = colors.name }
    suspend fun setStayAvailable(enabled: Boolean) = context.dataStore.edit { it[Keys.stayAvailable] = enabled }
    suspend fun setContactCard(json: String) = context.dataStore.edit { it[Keys.contactCard] = json }
    suspend fun setEcosystem(enabled: Boolean) = context.dataStore.edit { it[Keys.ecosystem] = enabled }
    suspend fun setInstantClipboard(enabled: Boolean) = context.dataStore.edit { it[Keys.instantClipboard] = enabled }
    suspend fun setSetupAsked() = context.dataStore.edit { it[Keys.setupAsked] = true }
    suspend fun setAnalytics(enabled: Boolean) = context.dataStore.edit { it[Keys.analytics] = enabled }
    suspend fun setSaveFolder(uri: String?) = context.dataStore.edit { if (uri == null) it.remove(Keys.saveFolder) else it[Keys.saveFolder] = uri }
}
