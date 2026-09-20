package com.vythera.relay.transfer

/**
 * Transfer speed over a sliding window, so the number on screen is steady enough to
 * read but still reacts within a couple of seconds when Wi-Fi degrades.
 */
class SpeedMeter(
    private val clock: () -> Long,
    private val windowMillis: Long = 3_000,
) {
    private val times = ArrayDeque<Long>()
    private val bytes = ArrayDeque<Long>()

    fun record(totalBytesDone: Long) {
        val now = clock()
        times.addLast(now)
        bytes.addLast(totalBytesDone)
        while (times.size > 2 && now - times.first() > windowMillis) {
            times.removeFirst()
            bytes.removeFirst()
        }
    }

    /** Bytes per second, or 0 until there is at least half a second of samples. */
    fun bytesPerSecond(): Long {
        if (times.size < 2) return 0
        val elapsed = times.last() - times.first()
        if (elapsed < 500) return 0
        return (bytes.last() - bytes.first()) * 1_000 / elapsed
    }

    fun remainingMillis(remainingBytes: Long): Long? {
        val speed = bytesPerSecond()
        return if (speed <= 0) null else remainingBytes * 1_000 / speed
    }

    fun reset() {
        times.clear()
        bytes.clear()
    }
}
