package com.vythera.relay.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.util.concurrent.TimeUnit

internal object DesktopNative {
    fun output(arguments: List<String>): String {
        val output = File.createTempFile("relay-native-", ".txt", DesktopPaths.cache.apply { mkdirs() })
        try {
            val process = ProcessBuilder(arguments).redirectOutput(output).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try { check(process.waitFor(6, TimeUnit.SECONDS) && process.exitValue() == 0) }
            finally { if (process.isAlive) process.destroyForcibly() }
            require(output.length() <= 65_536) { "Native response too large" }
            return output.readText().trim()
        } finally { output.delete() }
    }
}

internal class DesktopBattery {
    private var sampledAt = 0L
    private var cached: String? = null
    @Synchronized fun read(): String? {
        val now = System.nanoTime()
        if (sampledAt != 0L && now - sampledAt < 60_000_000_000L) return cached
        cached = runCatching { sample() }.getOrNull()
        sampledAt = now
        return cached
    }

    private fun sample(): String? {
        val os = System.getProperty("os.name")
        if (os.startsWith("Windows")) {
            val status = WindowsNative.powerStatus() ?: return null
            return "${status.percent}%${if (status.pluggedIn) " · Plugged in" else ""}"
        }
        if (os.contains("Linux")) {
            val battery = File("/sys/class/power_supply").listFiles()?.firstOrNull { File(it, "type").takeIf(File::isFile)?.readText()?.trim() == "Battery" } ?: return null
            val percent = File(battery, "capacity").readText().trim().toInt().coerceIn(0, 100)
            val state = File(battery, "status").readText().trim()
            return "$percent%${if (state == "Charging" || state == "Full") " · Plugged in" else ""}"
        }
        if (os.contains("Mac")) {
            val output = DesktopNative.output(listOf("pmset", "-g", "batt"))
            val percent = Regex("(\\d+)%").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return "$percent%${if (output.contains("AC Power")) " · Plugged in" else ""}"
        }
        return null
    }
}

/**
 * The few Windows calls Relay needs, made directly through JNA.
 *
 * These used to run in a hidden PowerShell with an encoded script. That works, but it is
 * exactly what antivirus behaviour monitors look for in malware (an unsigned program
 * quietly starting PowerShell with an unreadable command), and Kaspersky quarantined
 * Relay for it. Calling the same Windows functions in-process does the same job.
 */
internal object WindowsNative {
    class PowerStatus(val percent: Int, val pluggedIn: Boolean)

    /** Null when there is no battery, or Windows does not know its charge. */
    fun powerStatus(): PowerStatus? {
        val status = SystemPowerStatus()
        if (!kernel32.GetSystemPowerStatus(status)) return null
        val flags = status.BatteryFlag.toInt() and 0xff
        val percent = status.BatteryLifePercent.toInt() and 0xff
        if (flags and NO_BATTERY != 0 || percent > 100) return null
        return PowerStatus(percent, status.ACLineStatus.toInt() and 0xff == 1)
    }

    /** Presses and releases a virtual key, as a keyboard's media and volume keys do. */
    fun tapKey(virtualKey: Int) {
        user32.keybd_event(virtualKey.toByte(), 0, 0, null)
        user32.keybd_event(virtualKey.toByte(), 0, KEYEVENTF_KEYUP, null)
    }

    fun lockScreen() = check(user32.LockWorkStation()) { "Windows did not lock" }

    /**
     * Keeps the system and display awake. Windows ties the request to the calling thread,
     * so it lasts until that thread calls [releaseAwake] or ends.
     */
    fun requestAwake(): Boolean = kernel32.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED) != 0

    fun releaseAwake() {
        kernel32.SetThreadExecutionState(ES_CONTINUOUS)
    }

    @Suppress("FunctionName")
    internal interface Kernel32 : StdCallLibrary {
        fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean
        fun SetThreadExecutionState(flags: Int): Int
    }

    @Suppress("FunctionName")
    internal interface User32 : StdCallLibrary {
        fun keybd_event(virtualKey: Byte, scanCode: Byte, flags: Int, extraInfo: Pointer?)
        fun LockWorkStation(): Boolean
    }

    /** SYSTEM_POWER_STATUS, field for field. */
    @Suppress("PropertyName")
    @Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
    class SystemPowerStatus : Structure() {
        @JvmField var ACLineStatus: Byte = 0
        @JvmField var BatteryFlag: Byte = 0
        @JvmField var BatteryLifePercent: Byte = 0
        @JvmField var SystemStatusFlag: Byte = 0
        @JvmField var BatteryLifeTime: Int = 0
        @JvmField var BatteryFullLifeTime: Int = 0
    }

    private val kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    private val user32 by lazy { Native.load("user32", User32::class.java) }

    private const val NO_BATTERY = 128
    private const val KEYEVENTF_KEYUP = 2
    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x1
    private const val ES_DISPLAY_REQUIRED = 0x2
}
