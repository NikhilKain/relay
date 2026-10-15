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

        fun localAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .sortedByDescending { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct()
    }
}
