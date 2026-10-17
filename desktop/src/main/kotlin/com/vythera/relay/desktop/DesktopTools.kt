package com.vythera.relay.desktop

import com.vythera.relay.node.SharedFiles
import com.vythera.relay.node.ToolPlatform
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.RelayMessage
import com.vythera.relay.protocol.ToolItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Native desktop handlers. Commands are configured locally; the wire carries only their ids. */
class DesktopTools(private val relay: DesktopRelay) : ToolPlatform {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val linux = System.getProperty("os.name").contains("Linux")
    private val inputAvailable = !GraphicsEnvironment.isHeadless() && System.getenv("XDG_SESSION_TYPE") != "wayland"
    override val groups = buildSet {
        addAll(setOf("status", "ring", "ping", "files", "commands"))
        if (inputAvailable) addAll(setOf("input", "presentation"))
        if (windows || linux) add("lock")
        if (windows || (linux && executable("playerctl"))) add("media")
        if (windows || (linux && executable("pactl"))) add("volume")
        if (windows || System.getProperty("os.name").contains("Mac") || executable("systemd-inhibit")) add("keepawake")
    }
    private val robot by lazy { Robot() }
    private val battery = DesktopBattery()
    private val awake = DesktopAwake()
    fun close() = awake.close()
    init {
        relay.scope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { awake.close() }
        relay.scope.launch { relay.node.events.collect { event -> if (event is com.vythera.relay.node.NodeEvent.DeviceForgotten) awake.stop(event.deviceId) } }
    }
    private val inputLock = Any()
    private val preferencesFile = File(DesktopPaths.data, "tools.properties")
    private val preferences = Properties().apply { if (preferencesFile.isFile) runCatching { preferencesFile.inputStream().use(::load) } }
    var sharedFolder: File
        get() = synchronized(preferences) { preferences.getProperty("folder")?.let(::File) ?: relay.inboxFolder() }
        set(value) = synchronized(preferences) { preferences.setProperty("folder", value.canonicalPath); save() }

    fun commands(): List<ToolItem> = synchronized(preferences) {
        preferences.stringPropertyNames().filter { it.startsWith("command.") }.sorted().map {
            ToolItem(it.removePrefix("command."), it.removePrefix("command."), "Run on this computer", "command")
        }
    }

    fun saveCommand(name: String, command: String) = synchronized(preferences) {
        require(name.isNotBlank() && name.length <= 80 && command.length <= 4096)
        if (command.isBlank()) preferences.remove("command.$name") else preferences.setProperty("command.$name", command)
        save()
    }
    private fun save() { preferencesFile.parentFile.mkdirs(); preferencesFile.outputStream().use { preferences.store(it, "Local device tools") } }

    override suspend fun execute(peer: DeviceId, request: RelayMessage.ToolRequest): RelayMessage.ToolResponse = withContext(Dispatchers.IO) {
        fun result(message: String = "Done", values: Map<String, String> = emptyMap(), items: List<ToolItem> = emptyList()) =
            RelayMessage.ToolResponse(request.requestId, message = message, values = values, items = items)
        val args = request.arguments
        when (request.action) {
            "keepawake.start" -> { awake.start(peer); result("Keeping the desktop awake for up to 30 minutes. OS policy may override this.") }
            "keepawake.stop" -> { awake.stop(peer); result("Normal sleep behavior restored") }
            "status.get" -> result("${relay.deviceName} • ${System.getProperty("os.name")}", buildMap {
                put("System", System.getProperty("os.name")); put("Connection", "Encrypted local connection")
                battery.read()?.let { put("Battery", it) }
            })
            "media.status", "media.seek" -> {
                require(linux && executable("playerctl")) { "Playback details are currently supported on Linux and Android" }
                val players = DesktopNative.output(listOf("playerctl", "--list-all")).lines().filter(String::isNotBlank).take(50)
                val player = args["player"].orEmpty().ifBlank { players.firstOrNull().orEmpty() }
                require(player in players) { "Player is no longer active" }
                val base = listOf("playerctl", "--player", player)
                if (request.action == "media.seek") {
                    val seconds = args["seconds"]?.toIntOrNull()?.coerceIn(-60, 60) ?: error("Missing offset")
                    runProcess(base + listOf("position", "${kotlin.math.abs(seconds)}${if (seconds < 0) "-" else "+"}"))
                }
                result("Playback details", mapOf("Player" to player,
                    "Now playing" to DesktopNative.output(base + listOf("metadata", "--format", "{{artist}} — {{title}}")).take(1024),
                    "Playback" to DesktopNative.output(base + "status"), "canSeek" to "true"),
                    players.map { ToolItem(it, it, "Media player", "player") })
            }
            "ping.send" -> { relay.toolNotice("Ping from ${relay.name(peer)}", args["text"].orEmpty().take(200).ifBlank { "Your devices are connected." }); result("Ping delivered") }
            "ring.start" -> { Toolkit.getDefaultToolkit().beep(); relay.toolNotice("Find this device", "${relay.name(peer)} is looking for this computer."); result("Sound played on this computer") }
            "ring.stop" -> result("Sound stopped")
            "files.list" -> {
                val path = args["path"].orEmpty()
                val (items, next) = SharedFiles.list(sharedFolder, path, args["offset"]?.toIntOrNull() ?: 0)
                result(if (items.isEmpty()) "This folder is empty" else "Shared files", buildMap { put("path", path); next?.let { put("next", it.toString()) } }, items)
            }
            "files.get" -> {
                val file = SharedFiles.resolve(sharedFolder, args["path"] ?: error("Missing path"))
                require(file.isFile)
                relay.sendFiles(peer, listOf(file))
                result("File queued. Follow its progress in Transfers.")
            }
            "commands.list" -> result("Commands configured on this computer", items = commands())
            "commands.run" -> {
                val command = synchronized(preferences) { preferences.getProperty("command.${args["id"]}") } ?: error("Unknown command")
                val invocation = if (windows) listOf("cmd.exe", "/c", command) else listOf("/bin/sh", "-c", command)
                // Output may contain secrets: never copy it to the peer or logs.
                ProcessBuilder(invocation).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                result("Command started")
            }
            "lock.now" -> {
                if (windows) WindowsNative.lockScreen() else runProcess(listOf("loginctl", "lock-session"))
                result("Lock requested")
            }
            "input.move" -> {
                val dx = args["x"]?.toIntOrNull()?.coerceIn(-500, 500) ?: 0
                val dy = args["y"]?.toIntOrNull()?.coerceIn(-500, 500) ?: 0
                synchronized(inputLock) { val p = MouseInfo.getPointerInfo().location; robot.mouseMove(p.x + dx, p.y + dy) }
                result()
            }
            "input.click" -> {
                val button = if (args["button"] == "right") InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
                synchronized(inputLock) { robot.mousePress(button); try { } finally { robot.mouseRelease(button) } }; result()
            }
            "input.scroll" -> { synchronized(inputLock) { robot.mouseWheel((args["amount"]?.toIntOrNull() ?: 0).coerceIn(-10, 10)) }; result() }
            "input.key", "presentation.key" -> {
                val key = when (args["key"]) { "next" -> KeyEvent.VK_PAGE_DOWN; "previous" -> KeyEvent.VK_PAGE_UP; "start" -> KeyEvent.VK_F5; "escape" -> KeyEvent.VK_ESCAPE; "enter" -> KeyEvent.VK_ENTER; "backspace" -> KeyEvent.VK_BACK_SPACE; "tab" -> KeyEvent.VK_TAB; "black" -> KeyEvent.VK_B; else -> error("Unknown key") }
                synchronized(inputLock) { robot.keyPress(key); try { } finally { robot.keyRelease(key) } }; result()
            }
            "input.text" -> {
                val text = args["text"].orEmpty()
                require(text.length <= 500 && text.all { it.code in 32..126 }) { "Use basic Latin keyboard text" }
                synchronized(inputLock) {
                    text.forEach { char ->
                        val shifted = "~!@#$%^&*()_+{}|:\"<>?".indexOf(char)
                        val base = if (shifted >= 0) "`1234567890-=[]\\;',./"[shifted] else char
                        val key = KeyEvent.getExtendedKeyCodeForChar(base.uppercaseChar().code)
                        require(key != KeyEvent.VK_UNDEFINED)
                        val shift = char.isUpperCase() || shifted >= 0
                        if (shift) robot.keyPress(KeyEvent.VK_SHIFT)
                        try { robot.keyPress(key); robot.keyRelease(key) } finally { if (shift) robot.keyRelease(KeyEvent.VK_SHIFT) }
                    }
                }; result("Text typed")
            }
            "media.control", "volume.control" -> {
                val action = args["action"] ?: error("Missing action")
                if (windows) {
                    val key = when (action) { "playpause" -> 179; "next" -> 176; "previous" -> 177; "stop" -> 178; "up" -> 175; "down" -> 174; "mute" -> 173; else -> error("Unknown action") }
                    require((request.action == "media.control") == (key >= 176))
                    WindowsNative.tapKey(key)
                } else if (request.action == "media.control") {
                    val player = args["player"].orEmpty()
                    val target = if (player.isBlank()) emptyList() else {
                        require(player in DesktopNative.output(listOf("playerctl", "--list-all")).lines())
                        listOf("--player", player)
                    }
                    runProcess(listOf("playerctl") + target + when (action) { "playpause" -> "play-pause"; "next", "previous", "stop" -> action; else -> error("Unknown action") })
                } else {
                    runProcess(when (action) { "up" -> listOf("pactl", "set-sink-volume", "@DEFAULT_SINK@", "+5%"); "down" -> listOf("pactl", "set-sink-volume", "@DEFAULT_SINK@", "-5%"); "mute" -> listOf("pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle"); else -> error("Unknown action") })
                }; result("Control sent")
            }
            else -> RelayMessage.ToolResponse(request.requestId, false, "This action is not supported on this computer.")
        }
    }

    private fun executable(name: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() }
    private fun runProcess(args: List<String>) {
        val process = ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try { check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
