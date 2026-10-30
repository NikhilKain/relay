package com.vythera.relay.node

import com.vythera.relay.protocol.ToolItem
import org.junit.Test
import kotlin.test.*

class ToolUpdatesTest {
    @Test fun `notification baseline is quiet and updates are deduplicated`() {
        val tracker = NotificationChanges()
        val first = ToolItem("one", "Hello")
        assertTrue(tracker.update(listOf(first)).isEmpty())
        assertTrue(tracker.update(listOf(first)).isEmpty())
        val changed = first.copy(subtitle = "New text")
        assertEquals(listOf(changed), tracker.update(listOf(changed)))
        assertTrue(tracker.update(emptyList()).isEmpty())
        assertEquals(listOf(first), tracker.update(listOf(first)))
        tracker.reset()
        assertTrue(tracker.update(listOf(first)).isEmpty())
    }

    @Test fun `low battery warning resets only after charging or recovery`() {
        val warning = LowBatteryWarning()
        assertFalse(warning.update(null, false))
        assertTrue(warning.update(15, false))
        assertFalse(warning.update(14, false))
        assertFalse(warning.update(16, false))
        assertFalse(warning.update(15, false))
        assertFalse(warning.update(20, false))
        assertTrue(warning.update(10, false))
        assertFalse(warning.update(10, true))
        assertTrue(warning.update(9, false))
    }

    @Test
    fun `an incoming call is announced once per ring`() {
        val alert = IncomingCallAlert()
        assertFalse(alert.update("Idle"))
        assertTrue(alert.update("Ringing"))
        assertFalse(alert.update("Ringing"))
        assertFalse(alert.update("In a call"))
        assertFalse(alert.update("Idle"))
        assertTrue(alert.update("Ringing"))
        // A phone that does not share its call state never rings here.
        assertFalse(alert.update(null))
    }
}
