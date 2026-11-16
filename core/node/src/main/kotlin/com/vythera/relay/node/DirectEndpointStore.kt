package com.vythera.relay.node

import com.vythera.relay.protocol.DeviceId
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

/** A manually selected address, not a discovery announcement or an authentication identity. */
@Serializable
data class DirectEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank() && host.length <= 253 && host.none(Char::isWhitespace))
        require(port in 1..65535)
    }
}

/** Remembers explicit addresses independently of expiring LAN discovery. */
interface DirectEndpointStore {
    val endpoints: StateFlow<Map<DeviceId, DirectEndpoint>>
    suspend fun put(id: DeviceId, endpoint: DirectEndpoint)
    suspend fun remove(id: DeviceId)
}

open class InMemoryDirectEndpointStore(
    initial: Map<DeviceId, DirectEndpoint> = emptyMap(),
) : DirectEndpointStore {
    protected val state = MutableStateFlow(initial)
    override val endpoints: StateFlow<Map<DeviceId, DirectEndpoint>> = state.asStateFlow()

    override suspend fun put(id: DeviceId, endpoint: DirectEndpoint) {
        state.update { it + (id to endpoint) }
        persist()
    }

    override suspend fun remove(id: DeviceId) {
        state.update { it - id }
        persist()
    }

    protected open suspend fun persist() = Unit
}

/** Desktop persistence; a lost/corrupt file never disables ordinary LAN discovery. */
class JsonFileDirectEndpointStore(private val file: File) : InMemoryDirectEndpointStore(load(file)) {
    private val writeLock = Mutex()

    override suspend fun persist() {
        writeLock.withLock {
            AtomicFiles.write(file, json.encodeToString(serializer, state.value.map { (id, endpoint) ->
                SavedDirectEndpoint(id, endpoint.host, endpoint.port)
            }.sortedBy { it.id.value }))
        }
    }

    private companion object {
        @Serializable
        data class SavedDirectEndpoint(val id: DeviceId, val host: String, val port: Int)

        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val serializer = ListSerializer(SavedDirectEndpoint.serializer())

        fun load(file: File): Map<DeviceId, DirectEndpoint> =
            if (!file.isFile) emptyMap() else runCatching {
                json.decodeFromString(serializer, file.readText()).associate { it.id to DirectEndpoint(it.host, it.port) }
            }.getOrDefault(emptyMap())
    }
}
