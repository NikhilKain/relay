package com.vythera.relay.data.db

import com.vythera.relay.node.TrustStore
import com.vythera.relay.node.TrustedDevice
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [TrustStore] on Room. The in-memory map is the source of truth for reads (the node
 * checks trust on every message, so reads must not touch the disk); writes go to both.
 *
 * Create with [load], which reads the table once before the node starts, so no message
 * is ever judged against an empty list.
 */
class RoomTrustStore private constructor(
    private val dao: TrustedDeviceDao,
    initial: Map<DeviceId, TrustedDevice>,
) : TrustStore {
    private val state = MutableStateFlow(initial)
    override val devices: StateFlow<Map<DeviceId, TrustedDevice>> = state.asStateFlow()

    override suspend fun put(device: TrustedDevice) {
        dao.upsert(device.toEntity())
        state.update { it + (device.id to device) }
    }

    override suspend fun update(id: DeviceId, transform: (TrustedDevice) -> TrustedDevice) {
        val current = state.value[id] ?: return
        val updated = transform(current).copy(id = id)
        if (updated == current) return
        dao.upsert(updated.toEntity())
        state.update { it + (id to updated) }
    }

    override suspend fun remove(id: DeviceId) {
        dao.delete(id.value)
        state.update { it - id }
    }

    companion object {
        suspend fun load(dao: TrustedDeviceDao): RoomTrustStore {
            val devices = dao.getAll().mapNotNull { it.toModel() }.associateBy { it.id }
            return RoomTrustStore(dao, devices)
        }

        private fun TrustedDevice.toEntity() = TrustedDeviceEntity(
            id = id.value,
            name = name,
            type = type.wireName,
            platform = platform.wireName,
            fingerprint = fingerprint,
            pairedAtMillis = pairedAtMillis,
            autoAcceptTransfers = autoAcceptTransfers,
            lastConnectedMillis = lastConnectedMillis,
        )

        private fun TrustedDeviceEntity.toModel(): TrustedDevice? = runCatching {
            TrustedDevice(
                id = DeviceId(id),
                name = name,
                type = DeviceType.entries.firstOrNull { it.wireName == type } ?: DeviceType.UNKNOWN,
                platform = Platform.entries.firstOrNull { it.wireName == platform } ?: Platform.UNKNOWN,
                fingerprint = fingerprint,
                pairedAtMillis = pairedAtMillis,
                autoAcceptTransfers = autoAcceptTransfers,
                lastConnectedMillis = lastConnectedMillis,
            )
        }.getOrNull()
    }
}
