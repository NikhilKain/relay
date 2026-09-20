package com.vythera.relay.node

import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A device the user confirmed. Only trusted devices may send content. */
@Serializable
data class TrustedDevice(
    val id: DeviceId,
    val name: String,
    val type: DeviceType,
    val platform: Platform,
    /** Hex SHA-256 of the device's public key, as confirmed during pairing. */
    val fingerprint: String,
    val pairedAtMillis: Long,
    /** "Always allow transfers": incoming files start without asking. */
    val autoAcceptTransfers: Boolean = false,
    val lastConnectedMillis: Long? = null,
)

/** Persistent set of trusted devices. Android implements it with Room, desktops with [JsonFileTrustStore]. */
interface TrustStore {
    val devices: StateFlow<Map<DeviceId, TrustedDevice>>

    suspend fun put(device: TrustedDevice)

    suspend fun update(id: DeviceId, transform: (TrustedDevice) -> TrustedDevice)

    suspend fun remove(id: DeviceId)
}

open class InMemoryTrustStore(initial: Collection<TrustedDevice> = emptyList()) : TrustStore {
    protected val state = MutableStateFlow(initial.associateBy { it.id })
    override val devices: StateFlow<Map<DeviceId, TrustedDevice>> = state.asStateFlow()

    override suspend fun put(device: TrustedDevice) {
        state.update { it + (device.id to device) }
        persist()
    }

    override suspend fun update(id: DeviceId, transform: (TrustedDevice) -> TrustedDevice) {
        var changed = false
        state.update { current ->
            val device = current[id] ?: return@update current
            changed = true
            current + (id to transform(device).copy(id = id))
        }
        if (changed) persist()
    }

    override suspend fun remove(id: DeviceId) {
        state.update { it - id }
        persist()
    }

    protected open suspend fun persist() = Unit
}

/**
 * Trust store in a JSON file, written atomically (write to a temp file, then rename)
 * so a crash mid-write cannot lose every trusted device.
 */
class JsonFileTrustStore(private val file: File) : InMemoryTrustStore(load(file)) {
    private val writeLock = Mutex()

    override suspend fun persist() {
        writeLock.withLock {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(serializer, state.value.values.sortedBy { it.pairedAtMillis }))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val serializer = ListSerializer(TrustedDevice.serializer())

        fun load(file: File): List<TrustedDevice> =
            if (!file.isFile) emptyList() else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
    }
}
