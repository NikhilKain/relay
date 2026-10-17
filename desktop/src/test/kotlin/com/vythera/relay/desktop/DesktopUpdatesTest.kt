package com.vythera.relay.desktop

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdatesTest {
    @Test fun `newer versions are recognised number by number`() {
        assertTrue(DesktopUpdates.isNewer("1.0.4", "1.0.3"))
        assertTrue(DesktopUpdates.isNewer("1.0.10", "1.0.9"))
        assertTrue(DesktopUpdates.isNewer("1.1", "1.0.9"))
        assertTrue(DesktopUpdates.isNewer("2.0.0", "1.9.9"))
    }

    @Test fun `the same or older version is not an update`() {
        assertFalse(DesktopUpdates.isNewer("1.0.3", "1.0.3"))
        assertFalse(DesktopUpdates.isNewer("1.0", "1.0.0"))
        assertFalse(DesktopUpdates.isNewer("1.0.2", "1.0.3"))
    }

    @Test fun `tags that are not plain versions are ignored`() {
        assertFalse(DesktopUpdates.isNewer("1.0.4-beta", "1.0.3"))
        assertFalse(DesktopUpdates.isNewer("", "1.0.3"))
        assertFalse(DesktopUpdates.isNewer("latest", "1.0.3"))
    }
}
