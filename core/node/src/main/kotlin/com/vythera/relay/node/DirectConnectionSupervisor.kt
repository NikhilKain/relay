package com.vythera.relay.node

import com.vythera.relay.protocol.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Maintains explicitly saved, trusted direct peers (VPN or other routed networks).
 * Only one worker dials each peer; failures back off, whereas successful sessions are observed
 * without additional network probes. A stopped node cancels every worker.
 */
internal class DirectConnectionSupervisor(
    private val endpoints: DirectEndpointStore,
    private val trust: TrustStore,
    private val connected: (DeviceId) -> Boolean,
    private val dial: suspend (DeviceId) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private data class Worker(val job: Job, val wake: Channel<Unit>)
    private val workers = ConcurrentHashMap<DeviceId, Worker>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(endpoints.endpoints, trust.devices) { saved, trusted ->
                saved.keys.intersect(trusted.keys)
            }.collect { eligible ->
                workers.keys.filter { it !in eligible }.forEach(::remove)
                eligible.forEach { id ->
                    if (workers[id]?.job?.isActive != true) {
                        val wake = Channel<Unit>(Channel.CONFLATED)
                        val job = scope.launch { maintain(id, wake) }
                        workers[id] = Worker(job, wake)
                    }
                }
            }
        }
    }

    fun signal(id: DeviceId) {
        workers[id]?.wake?.trySend(Unit)
    }

    fun signalAll() {
        workers.values.forEach { it.wake.trySend(Unit) }
    }

    fun stop() {
        workers.keys.toList().forEach(::remove)
    }

    private fun remove(id: DeviceId) {
        workers.remove(id)?.let { it.job.cancel(); it.wake.close() }
    }

    private suspend fun maintain(id: DeviceId, wake: Channel<Unit>) {
        var failures = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            if (endpoints.endpoints.value[id] == null || trust.devices.value[id] == null) return
            if (connected(id)) {
                failures = 0
                pause(wake, 2_000)
                continue
            }
            try {
                dial(id)
                failures = 0
                pause(wake, 2_000)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures++
                log("Direct reconnect to ${id.value.take(8)} failed: ${error.javaClass.simpleName}")
                // 1s, 2s, 4s ... capped at 60s, with bounded jitter to avoid synchronized bursts.
                val base = (1_000L shl (failures - 1).coerceIn(0, 6)).coerceAtMost(60_000L)
                pause(wake, base + Random.nextLong(0, base / 4 + 1))
            }
        }
    }

    private suspend fun pause(wake: Channel<Unit>, millis: Long) {
        withTimeoutOrNull(millis) { wake.receiveCatching() }
    }
}
