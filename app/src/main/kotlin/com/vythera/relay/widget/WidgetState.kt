package com.vythera.relay.widget

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the home-screen widget shows, written whenever the device list changes.
 *
 * The widget must draw instantly, from a cold process, long after Relay itself was last
 * open. So it never talks to the engine: it reads this small file, which the running app
 * keeps up to date.
 */
@Serializable
data class WidgetDevice(val id: String, val name: String, val kind: String, val reachable: Boolean)

@Serializable
data class WidgetState(val devices: List<WidgetDevice> = emptyList())

object WidgetStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context) = File(context.filesDir, "widget.json")

    fun read(context: Context): WidgetState =
        runCatching { json.decodeFromString(WidgetState.serializer(), file(context).readText()) }.getOrDefault(WidgetState())

    fun write(context: Context, state: WidgetState) {
        runCatching { file(context).writeText(json.encodeToString(WidgetState.serializer(), state)) }
    }
}
