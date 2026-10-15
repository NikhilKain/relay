package com.vythera.relay.node

import com.vythera.relay.protocol.Capability
import com.vythera.relay.protocol.DeviceId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Properties

/** Background reads use independent request ids and never overwrite interactive tool results. */
class RemoteToolsMonitor(
    private val node: RelayNode,
    scope: CoroutineScope,
    private val settingsFile: File,
    private val notice: (String, String) -> Unit = { _, _ -> },
) {
    private val preferences = Properties().apply { if (settingsFile.isFile) runCatching { settingsFile.inputStream().use(::load) } }
    private val _mirroring = MutableStateFlow(preferences.stringPropertyNames().map(::DeviceId).toSet())
    val mirroring = _mirroring.asStateFlow()
    private val _status = MutableStateFlow<Map<DeviceId, Map<String, String>>>(emptyMap())
    val status = _status.asStateFlow()
    private val notifications = mutableMapOf<DeviceId, NotificationChanges>()
    private val battery = mutableMapOf<DeviceId, LowBatteryWarning>()

    fun setMirroring(peer: DeviceId, enabled: Boolean) = synchronized(preferences) {
        if (node.devices.value.none { it.id == peer && it.isTrusted }) return@synchronized
        if (enabled) preferences.setProperty(peer.value, "true") else preferences.remove(peer.value)
        save()
        _mirroring.value = preferences.stringPropertyNames().map(::DeviceId).toSet()
    }

    private fun save() { settingsFile.parentFile.mkdirs(); settingsFile.outputStream().use { preferences.store(it, "Local notification mirroring") } }

    init {
        scope.launch {
            node.events.collect { event ->
                if (event is NodeEvent.DeviceForgotten) synchronized(preferences) {
                    preferences.remove(event.deviceId.value); save()
                    _mirroring.update { it - event.deviceId }
                    _status.update { it - event.deviceId }
                }
            }
        }
        scope.launch {
            val slots = Semaphore(4)
            while (isActive) {
                val peers = node.devices.value.filter(::shouldPoll)
                val ids = peers.map { it.id }.toSet()
                _status.update { it.filterKeys(ids::contains) }
                notifications.keys.retainAll(ids.intersect(_mirroring.value))
                battery.keys.retainAll(ids)
                // Trackers are created on this coroutine; each child owns one peer's tracker.
                peers.forEach { battery.getOrPut(it.id) { LowBatteryWarning() }; if (it.id in _mirroring.value) notifications.getOrPut(it.id) { NotificationChanges() } }
                coroutineScope {
                    peers.forEach { peer -> launch {
                        slots.withPermit {
                            try {
                                val response = node.requestTool(peer.id, "status.get")
                                if (response.success && node.devices.value.any { it.id == peer.id && it.isTrusted }) {
                                    _status.update { it + (peer.id to response.values) }
                                    val raw = response.values["Battery"].orEmpty()
                                    val level = raw.substringBefore('%').toIntOrNull()
                                    if (battery[peer.id]?.update(level, raw.contains("Charging", ignoreCase = true) || raw.contains("Plugged in", ignoreCase = true)) == true)
                                        notice("${peer.name}: low battery", "$level% remaining")
                                } else _status.update { it - peer.id }
                                if (peer.id in _mirroring.value) {
                                    val reply = node.requestTool(peer.id, "notifications.list")
                                    if (reply.success && peer.id in _mirroring.value && node.devices.value.any { it.id == peer.id && it.isTrusted }) {
                                        val changed = notifications[peer.id]?.update(reply.items).orEmpty()
                                        changed.take(3).forEach { notice("${peer.name}: ${it.title}", it.subtitle) }
                                        if (changed.size > 3) notice(peer.name, "${changed.size - 3} more notifications. Open Notifications in Device tools.")
                                    } else notifications[peer.id]?.reset()
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { _status.update { it - peer.id } }
                        }
                    } }
                }
                delay(5_000)
            }
        }
    }

    companion object {
        /**
         * Which devices are polled in the background: trusted, nearby, and either known to
         * offer device tools or not asked yet. A device's capabilities arrive with a
         * connection and are forgotten when it drops, so waiting for them before connecting
         * meant a device that had disconnected once (a restart, a Wi-Fi blip) was never
         * polled again: mirroring, live status and low-battery alerts stopped for good.
         * Devices known to lack tools, such as older Relay versions, are still left alone.
         */
        internal fun shouldPoll(device: RelayDevice): Boolean =
            device.isTrusted && device.isReachable &&
                (device.capabilities.isEmpty() || Capability.DeviceTools in device.capabilities)
    }
}
