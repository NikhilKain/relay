package com.vythera.relay.desktop

import com.vythera.relay.protocol.DeviceId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Native inhibition expires after 30 minutes even if the network disappears. */
internal class DesktopAwake {
    private val sessions = mutableMapOf<DeviceId, Process>()

    @Synchronized fun start(peer: DeviceId) {
        stop(peer)
        val os = System.getProperty("os.name")
        val windows = os.startsWith("Windows")
        val args = when {
            // The request lasts only as long as the thread that made it, so the same
            // PowerShell sleeps on. It prints a line once Windows has accepted the request.
            windows -> DesktopNative.powershell(
                "Add-Type 'using System.Runtime.InteropServices; public class RelayAwake { [DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint flags); }'; " +
                    "if ([RelayAwake]::SetThreadExecutionState(2147483651) -eq 0) { exit 1 }; Write-Output '$READY'; [Console]::Out.Flush(); Start-Sleep -Seconds 1800",
            )
            os.contains("Mac") -> listOf("caffeinate", "-di", "-t", "1800")
            else -> listOf("systemd-inhibit", "--what=idle", "--mode=block", "--why=Relay presentation", "sleep", "1800")
        }
        val builder = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD)
        if (!windows) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        try {
            if (windows) {
                // Still running after a moment proved nothing: PowerShell takes longer than
                // that to start, let alone to fail. Wait for it to say Windows agreed.
                val ready = CompletableFuture.supplyAsync { process.inputReader().readLine() }
                check(runCatching { ready.get(15, TimeUnit.SECONDS) }.getOrNull() == READY) { "Windows did not accept the keep-awake request" }
            } else {
                check(!process.waitFor(1200, TimeUnit.MILLISECONDS)) { "The OS rejected sleep inhibition" }
            }
        } catch (e: Exception) {
            process.descendants().forEach { it.destroy() }
            process.destroy()
            throw e
        }
        sessions[peer] = process
    }

    @Synchronized fun stop(peer: DeviceId) {
        sessions.remove(peer)?.let { process ->
            process.descendants().forEach { it.destroy() }
            process.destroy()
        }
    }

    @Synchronized fun close() { sessions.keys.toList().forEach(::stop) }

    private companion object {
        const val READY = "relay-awake-ready"
    }
}
