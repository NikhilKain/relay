package com.vythera.relay.protocol

import kotlinx.serialization.Serializable

/**
 * Versions of the Relay message protocol.
 *
 * Every build declares the inclusive range it can speak. Two peers talk using the
 * highest version both ranges contain; if the ranges do not overlap the connection is
 * closed with [ErrorCode.INCOMPATIBLE_VERSION] and the UI asks the user to update.
 *
 * Rules for evolving the protocol live in `docs/protocol-v1.md`. In short: adding
 * optional fields or new message types does NOT bump the version (receivers ignore
 * what they do not understand). Changing the meaning of an existing field does.
 */
object ProtocolVersion {
    const val V1: Int = 1

    val SUPPORTED: VersionRange = VersionRange(min = V1, max = V1)

    fun negotiate(local: VersionRange, remote: VersionRange): Int? {
        val low = maxOf(local.min, remote.min)
        val high = minOf(local.max, remote.max)
        return if (low <= high) high else null
    }
}

@Serializable
data class VersionRange(val min: Int, val max: Int) {
    init {
        require(min in 1..max) { "Invalid version range $min..$max" }
    }
}
