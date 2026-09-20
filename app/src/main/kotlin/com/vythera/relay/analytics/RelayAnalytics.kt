package com.vythera.relay.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.vythera.relay.BuildConfig

/**
 * Optional, anonymous usage statistics.
 *
 * Relay's promise is that what you send stays between your devices, so this reports only
 * that something happened, never what: no file names, no text, no device names, no
 * addresses, no identifiers of the devices you pair with. It stays off until the user
 * turns it on in Settings, and turning it off also tells Firebase to stop collecting.
 *
 * When the app is built without a Firebase project file, every call here does nothing.
 */
object RelayAnalytics {
    private const val TAG = "Relay"

    @Volatile private var enabled = false
    private var firebase: Any? = null

    val isAvailable: Boolean get() = BuildConfig.ANALYTICS_AVAILABLE

    /** Follows the user's choice, and applies it to Firebase's own collection flag. */
    fun setEnabled(context: Context, value: Boolean) {
        enabled = value && isAvailable
        if (!isAvailable) return
        runCatching {
            val analytics = instance(context) ?: return
            analytics.javaClass.getMethod("setAnalyticsCollectionEnabled", Boolean::class.javaPrimitiveType)
                .invoke(analytics, enabled)
        }.onFailure { Log.w(TAG, "Analytics unavailable", it) }
    }

    /** A thing that happened, with at most a coarse, non-identifying detail. */
    fun log(context: Context, event: Event, detail: String? = null) {
        if (!enabled || !isAvailable) return
        runCatching {
            val analytics = instance(context) ?: return
            val params = Bundle().apply { detail?.let { putString("detail", it) } }
            analytics.javaClass.getMethod("logEvent", String::class.java, Bundle::class.java)
                .invoke(analytics, event.key, params)
        }.onFailure { Log.w(TAG, "Could not log ${event.key}", it) }
    }

    private fun instance(context: Context): Any? {
        firebase?.let { return it }
        return runCatching {
            val type = Class.forName("com.google.firebase.analytics.FirebaseAnalytics")
            type.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext).also { firebase = it }
        }.getOrNull()
    }

    /** The whole vocabulary. Anything not here is not reported. */
    enum class Event(val key: String) {
        DevicePaired("device_paired"),
        TransferCompleted("transfer_completed"),
        TransferFailed("transfer_failed"),
        ClipboardShared("clipboard_shared"),
        WidgetUsed("widget_used"),
    }
}
