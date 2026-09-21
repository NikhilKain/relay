package com.vythera.relay.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * Optional, anonymous usage statistics for the desktop app.
 *
 * There is no Firebase SDK for Windows, Linux or macOS, so the same events the Android app
 * reports are posted straight to Google Analytics over HTTPS, into the same project.
 *
 * What Relay promises applies here too: this reports only that something happened, never
 * what. No file names, no text, no clipboard contents, no device names, no addresses, and
 * no identifier of the devices you pair with. The one identifier is a random id for this
 * installation, kept in the settings file, which turning the setting off forgets.
 *
 * It stays off until the user turns it on in Settings.
 */
class DesktopAnalytics(
    private val scope: CoroutineScope,
    private val clientId: () -> String,
    private val log: (String) -> Unit,
) {
    @Volatile var enabled: Boolean = false

    /** A thing that happened, with at most a coarse, non-identifying detail. */
    fun log(event: Event, detail: String? = null) {
        if (!enabled) return
        scope.launch(Dispatchers.IO) { post(event, detail) }
    }

    private fun post(event: Event, detail: String?) {
        val payload = buildString {
            append("{\"client_id\":\"").append(clientId()).append("\",")
            append("\"non_personalized_ads\":true,")
            append("\"events\":[{\"name\":\"").append(event.key).append("\",\"params\":{")
            append("\"engagement_time_msec\":\"1\",\"platform\":\"").append(DesktopPaths.platform.name.lowercase()).append('"')
            if (detail != null) append(",\"detail\":\"").append(detail.filter { it.isLetterOrDigit() || it == '_' }).append('"')
            append("}}]}")
        }
        runCatching {
            val url = "$ENDPOINT?measurement_id=$MEASUREMENT_ID&api_secret=$API_SECRET"
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(payload.toByteArray()) }
                responseCode
                disconnect()
            }
        }.onFailure { log("analytics: could not report ${event.key} (${it.message})") }
    }

    /** The whole vocabulary. Anything not here is not reported. */
    enum class Event(val key: String) {
        DevicePaired("device_paired"),
        TransferCompleted("transfer_completed"),
        TransferFailed("transfer_failed"),
        ClipboardShared("clipboard_shared"),
    }

    companion object {
        /** A new installation identifier, stored with the settings. */
        fun newClientId(): String = UUID.randomUUID().toString()

        private const val ENDPOINT = "https://www.google-analytics.com/mp/collect"
        private const val MEASUREMENT_ID = "G-5T2DS0WV9F"
        // Not a secret in any real sense: it ships inside the app, as Google's own
        // measurement protocol requires. It grants nothing except sending events here.
        private const val API_SECRET = "Wv8LtfMdRWmZqjgz34AifQ"
    }
}
