package com.vythera.relay.node

import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import com.vythera.relay.protocol.VersionRange
import com.vythera.relay.protocol.TransferKind
import com.vythera.relay.protocol.Capability
import com.vythera.relay.security.RelayIdentity
import com.vythera.relay.transfer.DirectoryStorage
import com.vythera.relay.transfer.OutgoingFile
import com.vythera.relay.transfer.TransferFailure
import com.vythera.relay.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class MemoryFile(override val name: String, private val bytes: ByteArray) : OutgoingFile {
    override val sizeBytes = bytes.size.toLong()
    override val mimeType = "application/octet-stream"
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

class RelayNodeTest {
    @get:Rule val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runningNodes = mutableListOf<RelayNode>()

    private class TestDevice(val node: RelayNode, val trust: TrustStore, val inbox: File, val clipboard: File, val events: MutableStateFlow<List<NodeEvent>>)

    @After
    fun tearDown() {
        runningNodes.forEach { it.close() }
        scope.cancel()
    }

    private fun freeUdpPort() = DatagramSocket(0).use { it.localPort }

    /** Creates devices that discover each other over loopback instead of the LAN. */
    private fun devices(vararg names: String, protocol: (Int) -> VersionRange = { VersionRange(1, 1) }): List<TestDevice> {
        val ports = names.map { freeUdpPort() }
        val loopback = InetAddress.getLoopbackAddress()
        return names.mapIndexed { i, name ->
            val trust = InMemoryTrustStore()
            val inbox = temp.newFolder(name.replace(' ', '_'))
            val config = NodeConfig(
                deviceName = name,
                deviceType = DeviceType.PHONE,
                platform = Platform.ANDROID,
                appVersion = "test",
                listenPort = 0,
                discoveryPort = ports[i],
                discoveryTargets = ports.filterIndexed { j, _ -> j != i }.map { InetSocketAddress(loopback, it) },
                protocol = protocol(i),
            )
            val clipboard = temp.newFolder(name.replace(' ', '_') + "-clipboard")
            val node = RelayNode(config, RelayIdentity.generate(), trust, DirectoryStorage(inbox), scope, clipboardStorage = DirectoryStorage(clipboard))
            val events = MutableStateFlow<List<NodeEvent>>(emptyList())
            scope.launch { node.events.collect { event -> events.update { it + event } } }
            TestDevice(node, trust, inbox, clipboard, events)
        }.onEach { runningNodes += it.node; it.node.start() }
    }

    private suspend fun TestDevice.awaitSees(other: TestDevice) =
        node.devices.first { list -> list.any { it.id == other.node.localId } }

    private suspend inline fun <reified T : NodeEvent> TestDevice.awaitEvent(crossinline predicate: (T) -> Boolean = { true }): T =
        events.first { list -> list.any { it is T && predicate(it) } }.filterIsInstance<T>().first { predicate(it) }

    private suspend fun pair(a: TestDevice, b: TestDevice, autoAccept: Boolean = false) {
        a.awaitSees(b)
        val requestId = a.node.pair(b.node.localId)
        val request = b.awaitEvent<NodeEvent.PairingRequested>()
        b.node.respondToPairing(request.session.requestId, accept = true, autoAcceptTransfers = autoAccept)
        a.node.pairingSessions.first { sessions -> sessions.any { it.requestId == requestId && it.stage == PairingStage.Completed } }
    }

    @Test
    fun `pairing shows the same code on both devices and trusts both ways`(): Unit = runBlocking {
        withTimeout(20_000) {
            val (phone, pc) = devices("Pixel 6a", "Gaming PC")
            phone.awaitSees(pc)
            pc.awaitSees(phone)

            val requestId = phone.node.pair(pc.node.localId)
            val request = pc.awaitEvent<NodeEvent.PairingRequested>()
            val responderCode = assertIs<PairingStage.Confirm>(request.session.stage).code
            val initiatorCode = phone.node.pairingSessions
                .first { s -> s.any { it.requestId == requestId && it.stage is PairingStage.WaitingForPeer } }
                .first { it.requestId == requestId }.stage.let { assertIs<PairingStage.WaitingForPeer>(it).code }
            assertEquals(initiatorCode, responderCode)
            assertEquals("Pixel 6a", request.session.peerName)

            pc.node.respondToPairing(request.session.requestId, accept = true)
            phone.node.pairingSessions.first { s -> s.any { it.requestId == requestId && it.stage == PairingStage.Completed } }

            assertTrue(phone.trust.devices.value.containsKey(pc.node.localId))
            assertTrue(pc.trust.devices.value.containsKey(phone.node.localId))
            val seenByPhone = phone.node.devices.first { list -> list.any { it.id == pc.node.localId && it.isTrusted } }
            assertEquals("Gaming PC", seenByPhone.single().name)
        }
    }

    @Test
    fun `device tools require pairing and local grants over TLS`(): Unit = runBlocking {
        withTimeout(25_000) {
            val (phone, pc) = devices("Tools phone", "Tools pc")
            var executions = 0
            val platform = object : ToolPlatform {
                override val groups = setOf("status", "input")
                override suspend fun execute(peer: com.vythera.relay.protocol.DeviceId, request: com.vythera.relay.protocol.RelayMessage.ToolRequest): com.vythera.relay.protocol.RelayMessage.ToolResponse {
                    executions++
                    if (request.action == "status.large") return com.vythera.relay.protocol.RelayMessage.ToolResponse(
                        request.requestId, items = List(100) { com.vythera.relay.protocol.ToolItem("x".repeat(4096), "large") },
                    )
                    return com.vythera.relay.protocol.RelayMessage.ToolResponse(request.requestId, message = "executed")
                }
            }
            val service = DeviceTools(pc.node, platform, scope, File(temp.root, "grants.properties"))
            phone.awaitSees(pc)
            assertFalse(phone.node.requestTool(pc.node.localId, "input.click").success)
            assertEquals(0, executions)
            pair(phone, pc)
            assertEquals(setOf("status"), phone.node.requestTool(pc.node.localId, "discover").values.keys)
            assertFalse(phone.node.requestTool(pc.node.localId, "input.click").success)
            assertEquals(0, executions)
            service.setAllowed(phone.node.localId, "input", true)
            assertTrue(phone.node.requestTool(pc.node.localId, "input.click").success)
            assertEquals(1, executions)
            service.setAllowed(phone.node.localId, "input", false)
            assertFalse(phone.node.requestTool(pc.node.localId, "input.click").success)
            assertEquals(1, executions)
            val oversized = phone.node.requestTool(pc.node.localId, "status.large")
            assertFalse(oversized.success)
            assertTrue(oversized.message.contains("Too many results"))
            assertTrue(phone.node.requestTool(pc.node.localId, "status.get").success)
            phone.node.forget(pc.node.localId)
            assertFalse(phone.node.requestTool(pc.node.localId, "status.get").success)
        }
    }

    @Test
    fun `direct address connection still requires pairing`() = runBlocking {
        withTimeout(20_000) {
            val (phone, pc) = devices("Direct phone", "Direct pc")
            assertEquals(pc.node.localId, phone.node.connectAddress("127.0.0.1", pc.node.listeningPort))
            assertFalse(phone.node.requestTool(pc.node.localId, "status.get").success)
            pair(phone, pc)
            assertEquals(SendResult.Sent, phone.node.sendText(pc.node.localId, "Direct connection test"))
        }
    }

    @Test
    fun `two working addresses do not replace the winning session`() = runBlocking {
        withTimeout(6_000) {
            val (phone, pc) = devices("Dual phone", "Dual pc")
            val key = ConnectionInvite.parse(pc.node.connectionInvite("192.168.1.2")).fingerprint
            val endpoint = com.vythera.relay.discovery.Endpoint("127.0.0.1", pc.node.listeningPort)
            val target = com.vythera.relay.discovery.DiscoveredDevice(pc.node.localId, "Dual pc", DeviceType.PHONE,
                Platform.ANDROID, key, VersionRange(1, 1), listOf(endpoint, endpoint), 0)
            val connection = phone.node.raceConnections(target)
            val pong = kotlinx.coroutines.CompletableDeferred<Unit>()
            val reader = scope.launch { connection.readFrames { pong.complete(Unit) } }
            try {
                delay(100)
                connection.send(com.vythera.relay.protocol.RelayMessage.Ping(123))
                pong.await()
            } finally { connection.abort(); reader.cancel() }
        }
    }

    @Test
    fun `working endpoint wins while another stalls TLS and loser closes`() = runBlocking {
        withTimeout(6_000) {
            val (phone, pc) = devices("Race phone", "Race pc")
            java.net.ServerSocket(0).use { stalled ->
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                scope.launch(Dispatchers.IO) {
                    stalled.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        while (input.read() != -1) { }
                        closed.complete(Unit)
                    }
                }
                val key = ConnectionInvite.parse(pc.node.connectionInvite("192.168.1.2")).fingerprint
                val target = com.vythera.relay.discovery.DiscoveredDevice(pc.node.localId, "Race pc", DeviceType.PHONE,
                    Platform.ANDROID, key, VersionRange(1, 1), listOf(
                        com.vythera.relay.discovery.Endpoint("127.0.0.1", stalled.localPort),
                        com.vythera.relay.discovery.Endpoint("127.0.0.1", pc.node.listeningPort)), 0)
                val connection = phone.node.raceConnections(target)
                try { assertEquals(pc.node.localId, connection.peer.id); closed.await() }
                finally { connection.abort() }
            }
        }
    }

    @Test
    fun `QR fingerprint rejects a different device before registration`() = runBlocking {
        withTimeout(20_000) {
            val (phone, pc) = devices("QR phone", "QR pc")
            val wrongKey = ConnectionInvite.parse(phone.node.connectionInvite("192.168.1.2")).fingerprint
            kotlin.test.assertFailsWith<IdentityMismatchException> {
                phone.node.connectAddress("127.0.0.1", pc.node.listeningPort, wrongKey)
            }
            assertFalse(phone.trust.devices.value.containsKey(pc.node.localId))
            val rightKey = ConnectionInvite.parse(pc.node.connectionInvite("192.168.1.2")).fingerprint
            assertEquals(pc.node.localId, phone.node.connectAddress("127.0.0.1", pc.node.listeningPort, rightKey))
        }
    }

    @Test
    fun `declined pairing leaves both devices untrusted`(): Unit = runBlocking {
        withTimeout(20_000) {
            val (phone, tablet) = devices("Pixel 6a", "Tab S9 FE+")
            phone.awaitSees(tablet)
            val requestId = phone.node.pair(tablet.node.localId)
            val request = tablet.awaitEvent<NodeEvent.PairingRequested>()
            tablet.node.respondToPairing(request.session.requestId, accept = false)

            val stage = phone.node.pairingSessions.first { s -> s.any { it.requestId == requestId && it.stage.isFinished } }
                .single().stage
            assertEquals(PairingStage.Declined(byPeer = true), stage)
            assertTrue(phone.trust.devices.value.isEmpty())
            assertTrue(tablet.trust.devices.value.isEmpty())
        }
    }

    @Test
    fun `content from untrusted devices is refused`(): Unit = runBlocking {
        withTimeout(20_000) {
            val (phone, pc) = devices("Pixel 6a", "Gaming PC")
            phone.awaitSees(pc)
            assertEquals(SendResult.Failed(TransferFailure.NOT_TRUSTED), phone.node.sendText(pc.node.localId, "hi"))

            // Even if the sender believes it is trusted, the receiver enforces its own list.
            phone.trust.put(
                TrustedDevice(pc.node.localId, "Gaming PC", DeviceType.DESKTOP, Platform.WINDOWS, "00".repeat(32), 0),
            )
            assertEquals(SendResult.Sent, phone.node.sendText(pc.node.localId, "sneaky"))
            delay(500)
            assertTrue(pc.events.value.none { it is NodeEvent.TextReceived }, "untrusted text must not be delivered")
        }
    }

    @Test
    fun `trusted devices exchange text and continue requests`(): Unit = runBlocking {
        withTimeout(20_000) {
            val (phone, pc) = devices("Pixel 6a", "Gaming PC")
            pair(phone, pc)

            assertEquals(SendResult.Sent, phone.node.sendText(pc.node.localId, "Meeting moved to 3 PM"))
            assertEquals("Meeting moved to 3 PM", pc.awaitEvent<NodeEvent.TextReceived>().text)

            val activity = com.vythera.relay.protocol.ContinueActivity(com.vythera.relay.protocol.ContinueActivity.KIND_WEB_PAGE, "https://github.com/project")
            assertEquals(SendResult.Sent, pc.node.continueOn(phone.node.localId, activity))
            assertEquals(activity, phone.awaitEvent<NodeEvent.ContinueRequested>().activity)
        }
    }

    @Test
    fun `file transfer waits for acceptance and arrives intact`(): Unit = runBlocking {
        withTimeout(30_000) {
            val (phone, pc) = devices("Pixel 6a", "Gaming PC")
            pair(phone, pc)
            val content = Random(42).nextBytes(4_000_000)

            val transferId = phone.node.sendFiles(pc.node.localId, listOf(MemoryFile("vacation.zip", content)))
            val offer = pc.awaitEvent<NodeEvent.TransferOffered>()
            assertEquals(transferId, offer.transfer.id)
            pc.node.acceptTransfer(transferId)

            val finished = phone.awaitEvent<NodeEvent.TransferFinished> { it.transfer.id == transferId }
            assertEquals(TransferStatus.Completed, finished.transfer.status)
            pc.awaitEvent<NodeEvent.TransferFinished> { it.transfer.id == transferId }
            assertContentEquals(content, File(pc.inbox, "vacation.zip").readBytes())
        }
    }

    @Test
    fun `in ecosystem mode trusted devices send without asking, including offers already waiting`(): Unit = runBlocking {
        withTimeout(30_000) {
            val (phone, pc) = devices("Pixel 6a", "Gaming PC")
            pair(phone, pc)

            val waiting = phone.node.sendFiles(pc.node.localId, listOf(MemoryFile("before.txt", "one".encodeToByteArray())))
            pc.awaitEvent<NodeEvent.TransferOffered> { it.transfer.id == waiting }
            pc.node.setEcosystem(true)
            pc.node.transfers.first { list -> list.any { it.id == waiting && it.status == TransferStatus.Completed } }

            val dropped = phone.node.sendFiles(pc.node.localId, listOf(MemoryFile("dropped.txt", "two".encodeToByteArray())))
            pc.node.transfers.first { list -> list.any { it.id == dropped && it.status == TransferStatus.Completed } }
            assertEquals("one", File(pc.inbox, "before.txt").readText())
            assertEquals("two", File(pc.inbox, "dropped.txt").readText())
        }
    }

    @Test
    fun `a transfer to an absent device is delivered when it comes back`(): Unit = runBlocking {
        withTimeout(30_000) {
            val (phone, pc) = devices("Pixel 6a", "Gaming PC")
            pair(phone, pc, autoAccept = true)

            pc.node.stop()
            phone.node.devices.first { list -> list.single { it.id == pc.node.localId }.presence == Presence.OFFLINE }

            val transferId = phone.node.sendFiles(pc.node.localId, listOf(MemoryFile("notes.txt", "hello".encodeToByteArray())))
            val waiting = phone.node.transfers.first { list -> list.any { it.id == transferId && it.status is TransferStatus.Failed } }
            assertEquals(TransferStatus.Failed(TransferFailure.PEER_UNAVAILABLE), waiting.single { it.id == transferId }.status)

            pc.node.start()
            phone.node.transfers.first { list -> list.any { it.id == transferId && it.status == TransferStatus.Completed } }
            pc.node.transfers.first { list -> list.any { it.id == transferId && it.status == TransferStatus.Completed } }
            assertEquals("hello", File(pc.inbox, "notes.txt").readText())
        }
    }

    @Test
    fun `devices on incompatible protocol versions are flagged, not paired`(): Unit = runBlocking {
        withTimeout(20_000) {
            val (phone, future) = devices("Pixel 6a", "Future PC") { i -> if (i == 0) VersionRange(1, 1) else VersionRange(2, 3) }
            phone.awaitSees(future)
            val requestId = phone.node.pair(future.node.localId)
            val stage = phone.node.pairingSessions.first { s -> s.any { it.requestId == requestId && it.stage.isFinished } }.single().stage
            assertEquals(PairingStage.Failed(PairingFailure.INCOMPATIBLE_VERSION), stage)
            val device = phone.node.devices.first { list -> list.any { it.id == future.node.localId && !it.isCompatible } }
            assertFalse(device.single().isReachable)
        }
    }

    @Test
    fun `clipboard updates do not echo back`(): Unit = runBlocking {
        withTimeout(20_000) {
            val (phone, pc) = devices("Pixel 6a", "Gaming PC")
            pair(phone, pc)
            // Clipboard sync only uses existing connections; pairing left one open.
            assertEquals(listOf(pc.node.localId), phone.node.publishClipboard("adb shell pm list packages", listOf(pc.node.localId)))

            val received = pc.awaitEvent<NodeEvent.ClipboardReceived>()
            pc.node.markClipboardApplied(received.clip)
            // PC's clipboard listener now fires for the text it just applied.
            assertTrue(pc.node.publishClipboard(received.clip.text, listOf(phone.node.localId)).isEmpty())

            // A genuinely new copy on the PC does go out.
            assertEquals(listOf(phone.node.localId), pc.node.publishClipboard("github.com/project", listOf(phone.node.localId)))
            assertEquals("github.com/project", phone.awaitEvent<NodeEvent.ClipboardReceived>().clip.text)
        }
    }
}

class TrustPersistenceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `trusted devices survive a restart`(): Unit = runBlocking {
        val file = File(temp.root, "trust/devices.json")
        val identity = RelayIdentity.generate()
        val device = TrustedDevice(identity.deviceId, "Gaming PC", DeviceType.DESKTOP, Platform.WINDOWS, identity.fingerprint.hex, 1_700_000_000_000, autoAcceptTransfers = true)

        JsonFileTrustStore(file).apply {
            put(device)
            update(device.id) { it.copy(name = "Workstation") }
        }

        val reloaded = JsonFileTrustStore(file).devices.value
        assertEquals(device.copy(name = "Workstation"), reloaded[device.id])

        JsonFileTrustStore(file).remove(device.id)
        assertTrue(JsonFileTrustStore(file).devices.value.isEmpty())
    }
}

class ClipboardLoopGuardTest {
    private val self = RelayIdentity.generate().deviceId
    private val other = RelayIdentity.generate().deviceId

    @Test
    fun `drops own and repeated clips`() {
        val guard = ClipboardLoopGuard(self)
        val remote = com.vythera.relay.protocol.ClipItem("c1", other, 0, "hello")
        assertTrue(guard.acceptRemote(remote))
        assertFalse(guard.acceptRemote(remote))

        val local = guard.onLocalChange("mine")!!
        assertFalse(guard.acceptRemote(local))
    }

    @Test
    fun `applied clips are not rebroadcast but later edits are`() {
        var now = 0L
        val guard = ClipboardLoopGuard(self, clock = { now })
        guard.markApplied("hello")
        assertEquals(null, guard.onLocalChange("hello"))
        assertEquals("hello world", guard.onLocalChange("hello world")?.text)
        assertEquals(null, guard.onLocalChange("hello world"), "same text twice is not a new clip")
    }
}

class ClipboardFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runningNodes = mutableListOf<RelayNode>()

    @After
    fun tearDown() { runningNodes.forEach { it.close() }; scope.cancel() }

    @Test
    fun `copied files land in the clipboard cache without asking, or are refused when sync is off`(): Unit = runBlocking {
        withTimeout(30_000) {
            val ports = listOf(DatagramSocket(0).use { it.localPort }, DatagramSocket(0).use { it.localPort })
            val loopback = InetAddress.getLoopbackAddress()
            fun make(i: Int, name: String): Triple<RelayNode, File, MutableStateFlow<List<NodeEvent>>> {
                val clipboard = temp.newFolder("$i-clipboard")
                val node = RelayNode(
                    NodeConfig(name, DeviceType.PHONE, Platform.ANDROID, "test", listenPort = 0, discoveryPort = ports[i], discoveryTargets = listOf(InetSocketAddress(loopback, ports[1 - i]))),
                    RelayIdentity.generate(), InMemoryTrustStore(), DirectoryStorage(temp.newFolder("$i-inbox")), scope,
                    clipboardStorage = DirectoryStorage(clipboard),
                )
                val events = MutableStateFlow<List<NodeEvent>>(emptyList())
                scope.launch { node.events.collect { e -> events.update { it + e } } }
                runningNodes += node
                node.start()
                return Triple(node, clipboard, events)
            }
            val (pc, _, _) = make(0, "Gaming PC")
            val (phone, phoneClipboard, phoneEvents) = make(1, "Pixel")

            pc.devices.first { list -> list.any { it.id == phone.localId } }
            val request = pc.pair(phone.localId)
            val offer = phoneEvents.first { list -> list.any { it is NodeEvent.PairingRequested } }.filterIsInstance<NodeEvent.PairingRequested>().first()
            phone.respondToPairing(offer.session.requestId, accept = true)
            pc.pairingSessions.first { s -> s.any { it.requestId == request && it.stage == PairingStage.Completed } }

            val photo = Random(7).nextBytes(300_000)

            phone.setClipboardSync(false)
            // With sync off the phone does not advertise clipboard files, so the PC does not even try.
            assertTrue(pc.publishClipboardFiles(listOf(MemoryFile("screenshot.png", photo)), listOf(phone.localId)).isEmpty())

            phone.setClipboardSync(true)
            pc.devices.first { list -> list.any { it.id == phone.localId && Capability.ClipboardFiles in it.capabilities } }
            val accepted = pc.publishClipboardFiles(listOf(MemoryFile("screenshot.png", photo)), listOf(phone.localId)).single()
            pc.transfers.first { list -> list.any { it.id == accepted && it.status == TransferStatus.Completed } }

            assertContentEquals(photo, File(phoneClipboard, "screenshot.png").readBytes())
            assertTrue(phoneEvents.value.none { it is NodeEvent.TransferOffered }, "clipboard transfers never prompt")
            val finished = phoneEvents.first { list -> list.any { it is NodeEvent.TransferFinished && it.transfer.id == accepted } }
                .filterIsInstance<NodeEvent.TransferFinished>().first { it.transfer.id == accepted }
            assertEquals(TransferKind.CLIPBOARD, finished.transfer.kind)
        }
    }
}
