package com.vythera.relay.desktop

import com.vythera.relay.protocol.DeviceId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native inhibition expires after 30 minutes even if the network disappears. */
internal class DesktopAwake {
    private val sessions = mutableMapOf<DeviceId, () -> Unit>()

    @Synchronized fun start(peer: DeviceId) {
        stop(peer)
        val os = System.getProperty("os.name")
        sessions[peer] = if (os.startsWith("Windows")) windows() else process(
            if (os.contains("Mac")) listOf("caffeinate", "-di", "-t", "1800")
            else listOf("systemd-inhibit", "--what=idle", "--mode=block", "--why=Relay presentation", "sleep", "1800"),
        )
    }

    @Synchronized fun stop(peer: DeviceId) {
        sessions.remove(peer)?.invoke()
    }

    @Synchronized fun close() { sessions.keys.toList().forEach(::stop) }

    /**
     * Windows ties the request to the thread that made it, so a thread of its own makes it
     * and then waits, for 30 minutes or until stopped, before giving it back.
     */
    private fun windows(): () -> Unit {
        val done = CountDownLatch(1)
        val accepted = CompletableFuture<Boolean>()
        Thread({
            val ok = runCatching { WindowsNative.requestAwake() }.getOrDefault(false)
            accepted.complete(ok)
            if (ok) {
                try { done.await(30, TimeUnit.MINUTES) } catch (_: InterruptedException) { } finally { WindowsNative.releaseAwake() }
            }
        }, "relay-keep-awake").apply { isDaemon = true; start() }
        check(accepted.get(5, TimeUnit.SECONDS)) { "Windows did not accept the keep-awake request" }
        return { done.countDown() }
    }

    private fun process(args: List<String>): () -> Unit {
        val process = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (process.waitFor(1200, TimeUnit.MILLISECONDS)) error("The OS rejected sleep inhibition")
        return {
            process.descendants().forEach { it.destroy() }
            process.destroy()
        }
    }
}
