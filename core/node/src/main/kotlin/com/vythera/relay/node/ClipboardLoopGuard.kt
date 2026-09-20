package com.vythera.relay.node

import com.vythera.relay.protocol.ClipItem
import com.vythera.relay.protocol.DeviceId
import java.security.MessageDigest
import java.util.UUID

/**
 * Keeps clipboard sync from echoing.
 *
 * Without it: phone copies "hello" → PC applies it → PC's clipboard listener sees a
 * change → PC sends "hello" back → phone applies it → phone's listener fires → …
 *
 * Two independent defences:
 * 1. every clip carries (origin device, clip id); a clip from ourselves or one already
 *    seen is dropped;
 * 2. after applying a remote clip locally, the resulting local clipboard change is
 *    recognised by content and not broadcast.
 *
 * Only hashes of clipboard text are remembered, never the text.
 */
class ClipboardLoopGuard(
    private val localId: DeviceId,
    private val clock: () -> Long = System::currentTimeMillis,
    private val echoWindowMillis: Long = 60_000,
    private val capacity: Int = 256,
) {
    private val seen = object : LinkedHashMap<String, Long>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > capacity
    }
    private var appliedHash: String? = null
    private var appliedAtMillis = 0L
    private var broadcastHash: String? = null

    /** True if a clip received from the network should be offered to the user or applied. */
    @Synchronized
    fun acceptRemote(clip: ClipItem): Boolean {
        if (clip.originDeviceId == localId) return false
        val key = "${clip.originDeviceId}/${clip.clipId}"
        if (seen.containsKey(key)) return false
        seen[key] = clock()
        return true
    }

    /** Call right after writing a remote clip to the local clipboard. */
    @Synchronized
    fun markApplied(text: String) {
        val hash = hash(text)
        appliedHash = hash
        appliedAtMillis = clock()
        // What we just applied is also, in effect, what every device already has.
        broadcastHash = hash
    }

    /**
     * Called when the local clipboard changed. Returns the clip to broadcast, or null if
     * this change is an echo of an applied remote clip or a repeat of the last broadcast.
     */
    @Synchronized
    fun onLocalChange(text: String): ClipItem? {
        if (text.isEmpty() || text.length > ClipItem.MAX_CLIP_LENGTH) return null
        val hash = hash(text)
        if (hash == appliedHash && clock() - appliedAtMillis < echoWindowMillis) return null
        if (hash == broadcastHash) return null
        broadcastHash = hash
        val clip = ClipItem(UUID.randomUUID().toString(), localId, clock(), text)
        seen["${clip.originDeviceId}/${clip.clipId}"] = clip.createdAtMillis
        return clip
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
