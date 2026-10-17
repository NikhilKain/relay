package com.vythera.relay.desktop

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

/** Calls the real Windows functions, so it runs only on Windows. */
class WindowsNativeTest {
    @Before fun onWindows() = assumeTrue(System.getProperty("os.name").startsWith("Windows"))

    @Test fun `battery status is read without PowerShell`() {
        // A desktop with no battery returns null; a laptop returns 0 to 100.
        WindowsNative.powerStatus()?.let { assertTrue(it.percent in 0..100) }
    }

    @Test fun `keep awake is accepted and released on the same thread`() {
        assertTrue(WindowsNative.requestAwake())
        WindowsNative.releaseAwake()
    }
}

/** Writes and reads back a throwaway key, so it runs only on Windows. */
class WindowsRegistryTest {
    private val key = "Software\\Relay-test-${System.nanoTime()}"

    @Before fun onWindows() = assumeTrue(System.getProperty("os.name").startsWith("Windows"))

    @org.junit.After fun cleanUp() {
        WindowsRegistry.deleteKey(key)
    }

    @Test fun `quotes and spaces reach the registry intact`() {
        val command = "\"C:\\Program Files\\Relay\\Relay.exe\" --send \"%1\""
        assertTrue(WindowsRegistry.setStrings(key, mapOf(null to command, "MUIVerb" to "Send with Relay")))
        val output = ProcessBuilder("reg", "query", "HKCU\\$key").start().inputStream.bufferedReader().readText()
        assertTrue(command in output, output)
        assertTrue(WindowsRegistry.hasValue(key, "MUIVerb"))
    }
}
