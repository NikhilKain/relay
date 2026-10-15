package com.vythera.relay.node

import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.RelayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Properties

interface ToolPlatform {
    /** Groups with real handlers on this platform. */
    val groups: Set<String>
    suspend fun execute(peer: DeviceId, request: RelayMessage.ToolRequest): RelayMessage.ToolResponse
}

data class DeviceToolState(
    val busy: Boolean = false,
    val available: Set<String> = emptySet(),
    val response: RelayMessage.ToolResponse? = null,
    val action: String = "",
)

/** Per-device opt-ins are local only; a remote request cannot change its own grants. */
class DeviceTools(
    private val node: RelayNode,
    private val platform: ToolPlatform,
    private val scope: CoroutineScope,
    private val grantsFile: File,
) {
    val localGroups get() = platform.groups
    private val lock = Mutex()
    private val grants = Properties().apply {
        if (grantsFile.isFile) runCatching { grantsFile.inputStream().use(::load) }
    }
    private val _permissions = MutableStateFlow(readGrants())
    val permissions = _permissions.asStateFlow()
    private val _states = MutableStateFlow<Map<DeviceId, DeviceToolState>>(emptyMap())
    val states = _states.asStateFlow()

    init {
        node.installTools { peer, request ->
            val allowed = allowed(peer)
            when {
                request.action == "discover" -> RelayMessage.ToolResponse(request.requestId, values = allowed.associateWith { "true" })
                request.action.substringBefore('.') !in allowed -> RelayMessage.ToolResponse(request.requestId, false, "Enable this tool for the paired device in Relay on the other device.")
                else -> platform.execute(peer, request)
            }
        }
        scope.launch {
            node.events.collect { event ->
                if (event is NodeEvent.DeviceForgotten) {
                    lock.withLock {
                        synchronized(grants) { grants.remove(event.deviceId.value); persist() }
                        _permissions.value = readGrants()
                    }
                    _states.update { it - event.deviceId }
                }
            }
        }
    }

    private fun readGrants(): Map<DeviceId, Set<String>> = synchronized(grants) {
        grants.stringPropertyNames().associate { DeviceId(it) to grants.getProperty(it).split(',').filter(String::isNotBlank).toSet() }
    }

    fun allowed(peer: DeviceId) = (setOf("status", "ring", "ping") + _permissions.value[peer].orEmpty()).intersect(platform.groups)

    suspend fun setAllowed(peer: DeviceId, group: String, enabled: Boolean) = lock.withLock {
        if (group !in platform.groups || node.devices.value.none { it.id == peer && it.isTrusted }) return@withLock
        val current = _permissions.value[peer].orEmpty()
        synchronized(grants) {
            grants.setProperty(peer.value, (if (enabled) current + group else current - group).joinToString(","))
            persist()
        }
        _permissions.value = readGrants()
    }

    private fun persist() {
        grantsFile.parentFile.mkdirs()
        grantsFile.outputStream().use { grants.store(it, "Relay device tool permissions") }
    }

    suspend fun request(peer: DeviceId, action: String, arguments: Map<String, String> = emptyMap()) {
        // One UI operation per device: avoids duplicate commands and out-of-order results.
        var accepted = false
        synchronized(_states) {
            if (_states.value[peer]?.busy != true) {
                _states.update { it + (peer to (it[peer] ?: DeviceToolState()).copy(busy = true)) }
                accepted = true
            }
        }
        if (!accepted) return
        try {
            val response = node.requestTool(peer, action, arguments)
            _states.update {
                val old = it[peer] ?: DeviceToolState()
                it + (peer to old.copy(
                    busy = false,
                    available = if (action == "discover") response.values.keys else old.available,
                    response = response,
                    action = action,
                ))
            }
        } finally {
            _states.update { map -> map[peer]?.let { map + (peer to it.copy(busy = false)) } ?: map }
        }
    }
}
