package com.vythera.relay.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/**
 * Tells the person when a newer Relay is out. The Android app gets this from Google Play;
 * the computer apps come from GitHub, so they ask GitHub.
 *
 * Once shortly after start and then once a day, it reads the latest release's version
 * from GitHub's public API. Nothing about the computer or its devices is sent; GitHub sees
 * only an ordinary web request. It never downloads or installs anything by itself: it
 * offers the release page. It can be turned off in Settings.
 */
class DesktopUpdates(private val currentVersion: String, private val log: (String) -> Unit) {
    class Available(val version: String, val page: String)

    private val _available = MutableStateFlow<Available?>(null)
    val available: StateFlow<Available?> = _available.asStateFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope, enabled: Boolean) {
        job?.cancel()
        job = null
        if (!enabled) {
            _available.value = null
            return
        }
        job = scope.launch(Dispatchers.IO) {
            delay(FIRST_CHECK_MILLIS)
            while (isActive) {
                check()
                delay(CHECK_EVERY_MILLIS)
            }
        }
    }

    private fun check() {
        runCatching {
            val connection = (URI(LATEST).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Relay/$currentVersion")
            }
            try {
                if (connection.responseCode != 200) return
                // Bounded read: the answer is a few kilobytes.
                val body = connection.inputStream.use { it.readNBytes(MAX_RESPONSE_BYTES) }.decodeToString()
                val release = Json.parseToJsonElement(body).jsonObject
                val tag = release["tag_name"]?.jsonPrimitive?.content ?: return
                val page = release["html_url"]?.jsonPrimitive?.content?.takeIf { it.startsWith(RELEASES) } ?: RELEASES
                val version = tag.removePrefix("v")
                if (isNewer(version, currentVersion)) {
                    if (_available.value?.version != version) log("updates: Relay $version is available")
                    _available.value = Available(version, page)
                } else {
                    _available.value = null
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { log("updates: could not check (${it.message})") }
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/NikhilKain/relay/releases/latest"
        private const val RELEASES = "https://github.com/NikhilKain/relay/releases"
        private const val FIRST_CHECK_MILLIS = 20_000L
        private const val CHECK_EVERY_MILLIS = 24 * 60 * 60 * 1000L
        private const val MAX_RESPONSE_BYTES = 512 * 1024

        /** "1.0.10" is newer than "1.0.9": compares number by number, not as text. */
        internal fun isNewer(candidate: String, current: String): Boolean {
            val a = parts(candidate) ?: return false
            val b = parts(current) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parts(version: String): List<Int>? =
            version.trim().split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }
    }
}
