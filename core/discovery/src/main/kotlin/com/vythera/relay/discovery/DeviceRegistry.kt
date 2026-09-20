package com.vythera.relay.discovery

import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import com.vythera.relay.protocol.VersionRange
import com.vythera.relay.security.Fingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where a device can be reached. Most recently confirmed first. */
data class Endpoint(val host: String, val port: Int)

data class DiscoveredDevice(
    val id: DeviceId,
    val name: String,
    val type: DeviceType,
    val platform: Platform,
    val fingerprint: Fingerprint,
    val protocol: VersionRange,
    val endpoints: List<Endpoint>,
    val lastSeenMillis: Long,
)

/**
 * The single source of truth for "which devices are nearby right now".
 *
 * Every discovery mechanism (LAN multicast today, mDNS or Bluetooth later) and every
 * inbound connection reports into this registry, which:
 * - de-duplicates a device seen through several mechanisms or network interfaces;
 * - rejects announcements whose id is not derived from the announced key;
 * - expires devices that stopped announcing, so disappearing devices leave the UI
 *   without anyone having to poll them.
 */
class DeviceRegistry(
    private val localId: DeviceId,
    private val clock: () -> Long = System::currentTimeMillis,
    private val expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
) {
    private val _devices = MutableStateFlow<Map<DeviceId, DiscoveredDevice>>(emptyMap())
    val devices: StateFlow<Map<DeviceId, DiscoveredDevice>> = _devices.asStateFlow()

    /** Returns true if the announcement was accepted. */
    fun onAnnouncement(announcement: Announcement, sourceHost: String): Boolean {
        if (announcement.id == localId) return false
        if (announcement.kind == Announcement.Kind.BYE) {
            remove(announcement.id)
            return true
        }
        val fingerprint = runCatching { Fingerprint.fromHex(announcement.fingerprint) }.getOrNull() ?: return false
        if (fingerprint.deviceId != announcement.id) return false

        val endpoint = Endpoint(sourceHost, announcement.port)
        val now = clock()
        _devices.update { current ->
            val previous = current[announcement.id]
            val endpoints = (listOf(endpoint) + previous?.endpoints.orEmpty().filter { it != endpoint })
                .take(MAX_ENDPOINTS)
            current + (announcement.id to DiscoveredDevice(
                id = announcement.id,
                name = announcement.name.take(MAX_NAME_LENGTH),
                type = announcement.type,
                platform = announcement.platform,
                fingerprint = fingerprint,
                protocol = announcement.protocol,
                endpoints = endpoints,
                lastSeenMillis = now,
            ))
        }
        return true
    }

    /**
     * A device connected to us directly and proved its key over TLS. Recording it means
     * we can reach it back even when its discovery datagrams never arrive here, which
     * happens on networks that filter broadcast in one direction.
     */
    fun onDirectContact(
        id: DeviceId,
        name: String,
        type: DeviceType,
        platform: Platform,
        fingerprint: Fingerprint,
        protocol: VersionRange,
        endpoint: Endpoint?,
    ) {
        if (id == localId || fingerprint.deviceId != id) return
        val now = clock()
        _devices.update { current ->
            val previous = current[id]
            val endpoints = if (endpoint == null) {
                previous?.endpoints.orEmpty()
            } else {
                (listOf(endpoint) + previous?.endpoints.orEmpty().filter { it != endpoint }).take(MAX_ENDPOINTS)
            }
            current + (id to DiscoveredDevice(
                id = id,
                name = name.take(MAX_NAME_LENGTH),
                type = type,
                platform = platform,
                fingerprint = fingerprint,
                protocol = protocol,
                endpoints = endpoints,
                lastSeenMillis = now,
            ))
        }
    }

    /** A live connection is proof of presence, so it refreshes the device's expiry. */
    fun markSeen(id: DeviceId) {
        _devices.update { current ->
            val device = current[id] ?: return@update current
            current + (id to device.copy(lastSeenMillis = clock()))
        }
    }

    /** Called when connecting to an endpoint failed; the next endpoint gets tried first. */
    fun demoteEndpoint(id: DeviceId, endpoint: Endpoint) {
        _devices.update { current ->
            val device = current[id] ?: return@update current
            if (endpoint !in device.endpoints) return@update current
            current + (id to device.copy(endpoints = device.endpoints.filter { it != endpoint } + endpoint))
        }
    }

    fun remove(id: DeviceId) {
        _devices.update { it - id }
    }

    /** Drops devices not seen within the expiry window. Returns the ids removed. */
    fun expireStale(): Set<DeviceId> {
        val cutoff = clock() - expiryMillis
        var removed: Set<DeviceId> = emptySet()
        _devices.update { current ->
            removed = current.filterValues { it.lastSeenMillis < cutoff }.keys
            if (removed.isEmpty()) current else current - removed
        }
        return removed
    }

    fun clear() {
        _devices.value = emptyMap()
    }

    companion object {
        /** Three missed periodic announcements plus slack. */
        const val DEFAULT_EXPIRY_MILLIS = 100_000L
        private const val MAX_ENDPOINTS = 4
        private const val MAX_NAME_LENGTH = 64
    }
}
