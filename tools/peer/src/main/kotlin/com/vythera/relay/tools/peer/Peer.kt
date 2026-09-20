package com.vythera.relay.tools.peer

import com.vythera.relay.node.JsonFileTrustStore
import com.vythera.relay.node.NodeConfig
import com.vythera.relay.node.NodeEvent
import com.vythera.relay.node.PairingStage
import com.vythera.relay.node.RelayNode
import com.vythera.relay.protocol.ContinueActivity
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import com.vythera.relay.security.IdentityCodec
import com.vythera.relay.security.RelayIdentity
import com.vythera.relay.transfer.DirectoryStorage
import com.vythera.relay.transfer.OutgoingFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A headless Relay device for development: runs the real engine on a desktop JVM so an
 * Android build has a peer to discover, pair with and exchange files with.
 *
 * It accepts pairing and transfers automatically and takes commands from a file (one
 * per line, appended while it runs), which keeps it scriptable from any shell:
 *
 * ```
 * devices | transfers
 * pair <name-prefix>
 * text <name-prefix> <text>
 * continue <name-prefix> <url>
 * clip <name-prefix> <text>
 * send <name-prefix> <path>
 * quit
 * ```
 *
 * Not a product: the identity is stored unencrypted in the state directory.
 */
fun main(args: Array<String>) = runBlocking {
    val name = args.getOrNull(0) ?: "Gaming PC"
    val stateDir = File(args.getOrNull(1) ?: "build/peer-state").apply { mkdirs() }
    // Received files go where people look for downloads, not into the tool's state.
    val inbox = File(args.getOrNull(2) ?: File(System.getProperty("user.home"), "Downloads/Relay").path).apply { mkdirs() }
    val commands = File(stateDir, "commands.txt").apply { if (!exists()) createNewFile() }

    val identityFile = File(stateDir, "identity.json")
    val identity = if (identityFile.isFile) IdentityCodec.decode(identityFile.readBytes()) else RelayIdentity.generate().also { identityFile.writeBytes(IdentityCodec.encode(it)) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val node = RelayNode(
        config = NodeConfig(name, DeviceType.DESKTOP, Platform.WINDOWS, "peer-dev"),
        identity = identity,
        trustStore = JsonFileTrustStore(File(stateDir, "trusted.json")),
        storage = DirectoryStorage(inbox),
        parentScope = scope,
        clipboardStorage = DirectoryStorage(File(stateDir, "clipboard").apply { mkdirs() }),
        log = { log("node: $it") },
    )
    node.start()
    DesktopClipboard(node, scope, File(stateDir, "clipboard-out").apply { mkdirs() }, ::log).start()
    log("\"$name\" running as ${identity.deviceId}; inbox ${inbox.absolutePath}; commands ${commands.absolutePath}")

    scope.launch {
        node.devices.map { list -> list.map { "${it.name} [${it.presence}${if (it.isTrusted) ", trusted" else ""}${if (!it.isCompatible) ", incompatible" else ""}]" } }
            .distinctUntilChanged()
            .collect { log("devices: $it") }
    }
    scope.launch {
        node.pairingSessions.collect { sessions ->
            sessions.forEach { s ->
                val stage = s.stage
                if (stage is PairingStage.Confirm) {
                    log("pairing with ${s.peerName}: code ${stage.code.grouped} shapes ${stage.code.shapes} -> accepting")
                    node.respondToPairing(s.requestId, accept = true, autoAcceptTransfers = true)
                }
                if (stage is PairingStage.WaitingForPeer) log("pairing with ${s.peerName}: code ${stage.code.grouped} shapes ${stage.code.shapes}, waiting")
            }
        }
    }
    scope.launch {
        node.transfers.map { list -> list.map { "${it.id.take(8)} ${it.direction} ${it.status} ${(it.progress.fraction * 100).toInt()}%" } }
            .distinctUntilChanged()
            .collect { if (it.isNotEmpty()) log("transfers: $it") }
    }
    scope.launch {
        node.events.collect { event ->
            when (event) {
                is NodeEvent.TransferOffered -> {
                    log("offer ${event.transfer.id.take(8)}: ${event.transfer.items.map { it.name }} -> accepting")
                    node.acceptTransfer(event.transfer.id)
                }
                is NodeEvent.TransferFinished -> log("finished ${event.transfer.id.take(8)}: ${event.transfer.status} ${event.transfer.storedFiles.map { it.location }}")
                else -> log("event: $event")
            }
        }
    }

    var processed = commands.readLines().size
    while (true) {
        delay(400)
        val lines = commands.readLines()
        if (lines.size <= processed) continue
        for (line in lines.drop(processed)) {
            val parts = line.trim().split(" ", limit = 3)
            val target = parts.getOrNull(1)?.let { prefix -> node.devices.value.firstOrNull { it.name.startsWith(prefix, ignoreCase = true) } }
            log("> $line")
            when (parts[0]) {
                "quit" -> { node.close(); return@runBlocking }
                "devices" -> node.devices.value.forEach { log("  ${it.id} ${it.name} ${it.presence} trusted=${it.isTrusted} caps=${it.capabilities}") }
                "transfers" -> node.transfers.value.forEach { log("  $it") }
                "pair" -> target?.let { log("  request ${node.pair(it.id)}") } ?: log("  no such device")
                "text" -> target?.let { log("  ${node.sendText(it.id, parts.getOrElse(2) { "" })}") }
                "continue" -> target?.let { log("  ${node.continueOn(it.id, ContinueActivity(ContinueActivity.KIND_WEB_PAGE, parts.getOrElse(2) { "" }))}") }
                "clip" -> target?.let { log("  sent to ${node.publishClipboard(parts.getOrElse(2) { "" }, listOf(it.id))}") }
                "send" -> target?.let {
                    val file = File(parts.getOrElse(2) { "" })
                    if (!file.isFile) log("  no such file") else log("  transfer ${node.sendFiles(it.id, listOf(LocalFile(file)))}")
                }
                else -> log("  unknown command")
            }
        }
        processed = lines.size
    }
}

private class LocalFile(private val file: File) : OutgoingFile {
    override val name: String = file.name
    override val sizeBytes: Long = file.length()
    override val mimeType: String = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
    override fun open(): InputStream = file.inputStream()
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

internal fun log(message: String) = println("${LocalTime.now().format(timeFormat)} $message").also { System.out.flush() }
