package com.vythera.relay.desktop

import java.io.File
import java.util.concurrent.TimeUnit

internal object DesktopNative {
    /**
     * The command line that runs [script] in a hidden Windows PowerShell.
     *
     * The script travels base64-encoded (-EncodedCommand), never as a -Command argument:
     * Java does not escape double quotes inside arguments on Windows, so a script holding
     * one (`[DllImport("user32.dll")]`, say) reached PowerShell with its quotes stripped
     * and failed to compile. Encoding sidesteps Windows command-line quoting altogether.
     */
    fun powershell(script: String): List<String> = listOf(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-EncodedCommand", java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)),
    )

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
            val output = DesktopNative.output(DesktopNative.powershell(
                "Get-CimInstance Win32_Battery | Select-Object -First 1 | ForEach-Object { Write-Output \$_.EstimatedChargeRemaining; Write-Output \$_.BatteryStatus }"))
            val lines = output.lines()
            val percent = lines.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            val powered = lines.getOrNull(1)?.trim()?.toIntOrNull() in setOf(2, 3, 6, 7, 8, 9)
            return "$percent%${if (powered) " · Plugged in" else ""}"
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
