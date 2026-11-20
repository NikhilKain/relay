package com.vythera.relay.node

import com.vythera.relay.security.Fingerprint
import java.net.URI
import java.net.Inet4Address
import java.net.NetworkInterface

/** A short-lived address hint, never a replacement for TLS identity or user pairing. */
data class ConnectionInvite(val host: String, val port: Int, val fingerprint: Fingerprint, val expires: Long) {
    fun encode(): String = "relay://connect/$host/$port/${fingerprint.hex}/$expires"
    companion object {
        fun parse(text: String, now: Long = System.currentTimeMillis()): ConnectionInvite {
            require(text.length <= 512) { "Not a Relay connection QR" }
            val uri = URI(text)
            require(uri.scheme == "relay" && uri.rawAuthority == "connect" && uri.rawQuery == null && uri.rawFragment == null)
            val parts = uri.rawPath.split('/')
            require(parts.size == 5 && parts[0].isEmpty())
            val octets = parts[1].split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })
            require(octets[0].toInt() in 1..223 && octets[0].toInt() != 127 && octets.last().toInt() != 255)
            val port = parts[2].toInt(); require(port in 1..65535)
            val expires = parts[4].toLong()
            require(expires > now && expires - now <= 10 * 60_000) { "QR expired. Refresh it on the other device." }
            return ConnectionInvite(parts[1], port, Fingerprint.fromHex(parts[3]), expires)
        }

        /** Useful local endpoints for QR invites: routed VPN first, real LAN next. */
        fun localAddresses(): List<String> {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = interfaces.filter { it.isUp && !it.isLoopback }.flatMap { network ->
                network.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
                    .map { network.name to it }
            }
            return prioritizeAddresses(candidates)
        }

        /**
         * Container bridge addresses cannot be reached by another host and overwhelm
         * the network picker on machines hosting many containers. Keep a fallback
         * if a device only has virtual networking, and preserve other VPN interfaces.
         */
        internal fun prioritizeAddresses(candidates: List<Pair<String, Inet4Address>>): List<String> {
            val useful = candidates.filterNot { (name, _) ->
                val lower = name.lowercase()
                lower.startsWith("br-") || lower.startsWith("docker") ||
                    lower.startsWith("veth") || lower.startsWith("virbr") ||
                    lower.startsWith("podman") || lower.startsWith("cni")
            }.ifEmpty { candidates }
            return useful.sortedWith(
                compareByDescending<Pair<String, Inet4Address>> { (_, address) ->
                    isTailnetAddress(address.hostAddress)
                }.thenByDescending { (_, address) -> address.isSiteLocalAddress }
            ).map { (_, address) -> address.hostAddress }.distinct()
        }
    }
}
