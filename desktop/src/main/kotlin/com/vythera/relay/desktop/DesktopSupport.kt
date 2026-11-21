package com.vythera.relay.desktop

import com.vythera.relay.protocol.Platform
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a person wants to tell us; each opens the feedback website on its own form. */
enum class FeedbackType(val key: String) { Bug("bug"), Feature("feature"), Message("other"), Rate("rate") }

/**
 * The shared Vythera feedback website, opened in the browser with Relay chosen and the version
 * and system filled in. Relay sends nothing itself; no name, email or account goes in the link.
 */
object DesktopFeedback {
    private const val BASE = "https://vythera-feedback.narzo9990.workers.dev/"

    fun url(type: FeedbackType): String {
        fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        val params = listOf(
            "app" to "relay",
            "type" to type.key,
            "version" to DesktopRelay.VERSION,
            "device" to DesktopSystem.model,
            "os" to DesktopSystem.os,
            "source" to "app",
        )
        return BASE + "?" + params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    fun open(type: FeedbackType) {
        runCatching { java.awt.Desktop.getDesktop().browse(URI(url(type))) }
            .onFailure { DesktopLog.write("feedback: no browser to open (${it.javaClass.simpleName})") }
    }
}

/** This computer as the feedback form and the admin panel describe it. Nothing personal: no names. */
object DesktopSystem {
    val os: String = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"

    /** "Windows", "macos" or "linux": the panel lists each of Relay's computers under its platform. */
    val kind: String = when (DesktopPaths.platform) {
        Platform.WINDOWS -> "windows"
        Platform.MACOS -> "macos"
        else -> "linux"
    }

    private val hardware: Pair<String, String> by lazy { runCatching { readHardware() }.getOrNull() ?: ("" to "") }
    val manufacturer: String get() = hardware.first
    /** "Lenovo 82K2", "MacBookPro18,3"; the kind of computer when the system will not say. */
    val model: String get() = listOf(hardware.first, hardware.second).filter(String::isNotBlank).joinToString(" ").ifBlank {
        when (DesktopPaths.platform) {
            Platform.WINDOWS -> "Windows PC"
            Platform.MACOS -> "Mac"
            else -> "Linux PC"
        }
    }

    private fun readHardware(): Pair<String, String> = when (DesktopPaths.platform) {
        Platform.WINDOWS -> run(
            "powershell", "-NoProfile", "-NonInteractive", "-Command",
            "\$c = Get-CimInstance Win32_ComputerSystem; \$c.Manufacturer + '|' + \$c.Model",
        ).split('|').let { it.getOrElse(0) { "" }.trim() to it.getOrElse(1) { "" }.trim() }
        Platform.MACOS -> "Apple" to run("sysctl", "-n", "hw.model").trim()
        else -> read("/sys/devices/virtual/dmi/id/sys_vendor") to read("/sys/devices/virtual/dmi/id/product_name")
    }.let { (maker, model) ->
        // Placeholders some firmware leaves in.
        fun clean(s: String) = s.takeUnless { it.isBlank() || it.contains("To be filled", true) || it.contains("System Product Name", true) || it == "Default string" }.orEmpty()
        clean(maker) to clean(model)
    }

    /** A stable id for this computer, from the system: survives reinstalling Relay. */
    fun machineId(): String? = runCatching {
        when (DesktopPaths.platform) {
            Platform.WINDOWS -> run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                .lines().firstOrNull { "MachineGuid" in it }?.trim()?.split(Regex("\\s+"))?.lastOrNull()
            Platform.MACOS -> Regex("\"IOPlatformUUID\" = \"([^\"]+)\"").find(run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"))?.groupValues?.get(1)
            else -> listOf("/etc/machine-id", "/var/lib/dbus/machine-id").map(::read).firstOrNull(String::isNotBlank)
        }
    }.getOrNull()?.takeIf { it.length >= 8 }

    private fun read(path: String) = runCatching { File(path).readText().trim() }.getOrDefault("")

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(10, TimeUnit.SECONDS)
        process.destroy()
        return out
    }
}

/**
 * When something goes wrong that Relay did not catch, a plain-text report in
 * Downloads/Relay/Crash logs: the error, the version and system, and the last lines of
 * relay.log. It stays on the computer unless the person attaches it to a report.
 */
object DesktopCrashLogs {
    val folder: File get() = File(DesktopPaths.defaultInbox, "Crash logs")
    /** Names the report a crash left, so the next start can point to it. */
    private val pending = File(DesktopPaths.data, "crash-pending.txt")

    /** Follows "Save crash logs". On until turned off. */
    @Volatile var enabled = true
    private var lastWritten = 0L

    @Synchronized
    fun record(thread: Thread, error: Throwable) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        // One error can repeat many times a second; one report says it.
        if (now - lastWritten < 60_000) return
        lastWritten = now
        runCatching {
            val date = Date(now)
            val report = buildString {
                appendLine("Relay crash report")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(date)}")
                appendLine("Relay: ${DesktopRelay.VERSION}")
                appendLine("System: ${DesktopSystem.os}")
                appendLine("Java: ${System.getProperty("java.version")}")
                appendLine("Thread: ${thread.name}")
                appendLine()
                appendLine(error.stackTraceToString())
                appendLine("Recent log")
                appendLine("----------")
                append(DesktopLog.recent(300))
            }
            folder.mkdirs()
            val file = File(folder, "relay-crash-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(date)}.txt")
            file.writeText(report)
            pending.writeText(file.absolutePath)
        }
    }

    /** The report left by a crash since Relay last started, once; null when there is none. */
    fun takePending(): File? {
        if (!pending.isFile) return null
        val file = runCatching { File(pending.readText().trim()) }.getOrNull()
        pending.delete()
        return file?.takeIf { it.isFile }
    }
}

/**
 * An anonymous count of the computers using Relay, for the developer's admin panel (the Vyxel
 * panel, app "relay", where each computer is listed under Windows, macOS or Linux). It runs
 * for every install, with no switch, and is described in the privacy policy. It only
 * reports; the panel's answer is ignored.
 *
 * Sent: a SHA-256 of the system's machine id and Relay's name (the id itself never leaves the
 * computer), a random installation id, a random session id and how long Relay's window has been
 * open, the computer's maker and model, the system and Relay's version. The panel keeps only the
 * country of the connection. Never files, text, clipboard, device names or addresses.
 *
 * A session is Relay's window being open. Relay that lives in the tray says it is running once
 * at start and once a day, so it is still counted.
 */
class DesktopCheckIn(private val scope: CoroutineScope, private val log: (String) -> Unit) {
    @Volatile var enabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) { if (windowOpen) opened() else presence() } else stop()
        }

    private var windowOpen = false
    private var sessionId = ""
    private var sessionStarted = 0L
    private var heartbeat: Job? = null
    private var daily: Job? = null

    private val installationId: String by lazy {
        val file = File(DesktopPaths.data, "checkin-id.txt")
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.length == 36 }
            ?: UUID.randomUUID().toString().also { runCatching { file.writeText(it) } }
    }
    private val deviceId: String by lazy {
        val raw = (DesktopSystem.machineId() ?: installationId) + ":com.vythera.relay"
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun windowVisible(visible: Boolean) {
        if (windowOpen == visible) return
        windowOpen = visible
        if (!enabled) return
        if (visible) opened() else closed()
    }

    private fun opened() {
        sessionId = UUID.randomUUID().toString()
        sessionStarted = System.currentTimeMillis()
        send("start")
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                send("heartbeat")
            }
        }
    }

    private fun closed() {
        heartbeat?.cancel()
        if (sessionId.isNotEmpty()) send("pause")
        sessionId = ""
        presence()
    }

    /** Running in the tray: a zero-length session now and every day, so tray-only use still counts. */
    private fun presence() {
        daily?.cancel()
        daily = scope.launch {
            while (isActive) {
                if (!windowOpen && enabled) {
                    sessionId = UUID.randomUUID().toString()
                    sessionStarted = System.currentTimeMillis()
                    send("start")
                    send("pause")
                    sessionId = ""
                }
                delay(TimeUnit.DAYS.toMillis(1))
            }
        }
    }

    private fun stop() {
        heartbeat?.cancel()
        daily?.cancel()
        sessionId = ""
    }

    private fun send(event: String) {
        if (!enabled || sessionId.isEmpty()) return
        val session = sessionId
        val seconds = ((System.currentTimeMillis() - sessionStarted) / 1000).coerceIn(0, 604_800)
        scope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val body = buildJsonObject {
                    put("app", "relay")
                    put("id", deviceId)
                    put("installationId", installationId)
                    put("sessionId", session)
                    put("event", event)
                    put("durationSeconds", seconds)
                    put("manufacturer", DesktopSystem.manufacturer)
                    put("model", DesktopSystem.model)
                    put("os", DesktopSystem.os)
                    put("appVersion", DesktopRelay.VERSION)
                    put("kind", DesktopSystem.kind)
                }
                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                if (code != 200) log("check-in answered HTTP $code")
            } catch (e: Exception) {
                log("check-in failed: ${e.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private companion object {
        const val ENDPOINT = "https://vyxel-admin.narzo9990.workers.dev/api/checkin"
        const val HEARTBEAT_MS = 5 * 60_000L
    }
}
