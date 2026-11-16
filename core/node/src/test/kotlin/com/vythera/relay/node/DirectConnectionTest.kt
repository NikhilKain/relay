package com.vythera.relay.node

import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import com.vythera.relay.security.RelayIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectEndpointStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `explicit address survives store re-creation and can be removed`() = runBlocking {
        val file = File(temp.root, "nested/direct-endpoints.json")
        val id = RelayIdentity.generate().deviceId
        val endpoint = DirectEndpoint("100.101.102.103", 47800)
        JsonFileDirectEndpointStore(file).put(id, endpoint)
        assertEquals(endpoint, JsonFileDirectEndpointStore(file).endpoints.value[id])
        JsonFileDirectEndpointStore(file).remove(id)
        assertFalse(JsonFileDirectEndpointStore(file).endpoints.value.containsKey(id))
    }

    @Test fun `only likely Tailnet IPs qualify for automatic inbound pinning`() {
        assertTrue(isTailnetAddress("100.64.0.1"))
        assertTrue(isTailnetAddress("100.127.255.254"))
        assertTrue(isTailnetAddress("fd7a:115c:a1e0::1"))
        assertFalse(isTailnetAddress("100.63.255.255"))
        assertFalse(isTailnetAddress("100.128.0.1"))
        assertFalse(isTailnetAddress("192.168.1.20"))
        assertFalse(isTailnetAddress("127.0.0.1"))
        assertFalse(isTailnetAddress("100.70.bad.4"))
    }

    @Test fun `bad persistence data is safely ignored`() {
        val file = temp.newFile("direct-endpoints.json")
        file.writeText("not json")
        assertTrue(JsonFileDirectEndpointStore(file).endpoints.value.isEmpty())
        assertFailsWith<IllegalArgumentException> { DirectEndpoint("bad host", 47800) }
        assertFailsWith<IllegalArgumentException> { DirectEndpoint("100.101.102.103", 0) }
    }
}

class DirectConnectionSupervisorTest {
    @Test fun `reconnects a trusted endpoint, wakes on disconnect and stops on unpair`() = runBlocking {
        val peer = RelayIdentity.generate()
        val trusted = TrustedDevice(peer.deviceId, "peer", DeviceType.DESKTOP, Platform.MACOS,
            peer.fingerprint.hex, System.currentTimeMillis())
        val trust = InMemoryTrustStore(listOf(trusted))
        val endpoints = InMemoryDirectEndpointStore(mapOf(peer.deviceId to DirectEndpoint("100.101.102.103", 47800)))
        val connected = AtomicBoolean(false)
        val dials = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val supervisor = DirectConnectionSupervisor(endpoints, trust, { connected.get() }, {
            dials.incrementAndGet()
            connected.set(true)
        })
        try {
            supervisor.start(scope)
            withTimeout(5_000) { while (dials.get() < 1) delay(20) }
            connected.set(false)
            supervisor.signal(peer.deviceId)
            withTimeout(5_000) { while (dials.get() < 2) delay(20) }
            trust.remove(peer.deviceId)
            delay(100)
            connected.set(false)
            supervisor.signal(peer.deviceId)
            delay(100)
            assertEquals(2, dials.get(), "Unpaired peers must never be redialed")
        } finally {
            supervisor.stop()
            scope.cancel()
        }
    }
}
