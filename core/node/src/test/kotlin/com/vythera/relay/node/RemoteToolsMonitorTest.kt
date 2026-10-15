package com.vythera.relay.node

import com.vythera.relay.protocol.Capability
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteToolsMonitorTest {
    private fun device(
        presence: Presence = Presence.NEARBY,
        trusted: Boolean = true,
        capabilities: Set<Capability> = emptySet(),
    ) = RelayDevice(
        id = DeviceId("t6jtyj5qft2wj6cq5a3fc55rge"), name = "Phone", type = DeviceType.PHONE, platform = Platform.ANDROID,
        presence = presence, isTrusted = trusted, autoAcceptTransfers = false,
        capabilities = capabilities, isCompatible = true, lastSeenMillis = null,
    )

    @Test fun `a trusted device seen again after a restart is polled before its capabilities are known`() {
        // Capabilities are forgotten when a connection drops; polling is what reconnects.
        assertTrue(RemoteToolsMonitor.shouldPoll(device(Presence.NEARBY, capabilities = emptySet())))
    }

    @Test fun `a connected device with device tools is polled`() {
        assertTrue(RemoteToolsMonitor.shouldPoll(device(Presence.CONNECTED, capabilities = setOf(Capability.DeviceTools))))
    }

    @Test fun `a device known to lack device tools is left alone`() {
        assertFalse(RemoteToolsMonitor.shouldPoll(device(Presence.CONNECTED, capabilities = setOf(Capability.ClipboardPush))))
    }

    @Test fun `untrusted or offline devices are never polled`() {
        assertFalse(RemoteToolsMonitor.shouldPoll(device(trusted = false)))
        assertFalse(RemoteToolsMonitor.shouldPoll(device(Presence.OFFLINE)))
    }
}
