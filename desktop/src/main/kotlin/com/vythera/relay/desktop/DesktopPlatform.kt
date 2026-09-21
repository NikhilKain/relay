package com.vythera.relay.desktop

import com.vythera.relay.protocol.Platform
import com.vythera.relay.security.IdentityCodec
import com.vythera.relay.security.IdentityStore
import com.vythera.relay.security.RelayIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Where Relay keeps its state on each desktop OS, following that OS's conventions. */
object DesktopPaths {
    private val os = System.getProperty("os.name").lowercase()
    val platform: Platform = when {
        "win" in os -> Platform.WINDOWS
        "mac" in os -> Platform.MACOS
        else -> Platform.LINUX
    }

    private val home = File(System.getProperty("user.home"))

    /** %APPDATA%\Relay, ~/Library/Application Support/Relay, or $XDG_DATA_HOME/relay. */
    val data: File = when (platform) {
        Platform.WINDOWS -> File(System.getenv("APPDATA") ?: home.path, "Relay")
        Platform.MACOS -> File(home, "Library/Application Support/Relay")
        else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "relay")
    }.apply { mkdirs() }

    val cache: File = File(
        when (platform) {
            Platform.WINDOWS -> System.getenv("LOCALAPPDATA")?.let { File(it, "Relay/Cache") } ?: File(data, "cache")
            Platform.MACOS -> File(home, "Library/Caches/Relay")
            else -> File(System.getenv("XDG_CACHE_HOME") ?: File(home, ".cache").path, "relay")
        }.path,
    ).apply { mkdirs() }

    /** Default place for received files: the user's Downloads folder, in a Relay sub-folder. */
    val defaultInbox: File = File(home, "Downloads/Relay")

    fun defaultDeviceName(): String =
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }?.let(::prettify)
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.substringBefore('.')?.let(::prettify)
            ?: "My computer"

    /** "DESKTOP-SHUBHAM" reads better as "Desktop-Shubham". */
    private fun prettify(name: String) =
        if (name == name.uppercase()) name.lowercase().split('-').joinToString("-") { it.replaceFirstChar(Char::titlecase) } else name
}

/**
 * A small log next to the settings, so a problem that happens when nobody is watching a
 * console can still be explained afterwards. Kept to one file, trimmed when it grows.
 */
object DesktopLog {
    private val file = File(DesktopPaths.data, "relay.log")
    private val stamp = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    @Synchronized
    fun write(line: String) {
        println(line)
        runCatching {
            if (file.length() > MAX_BYTES) file.writeText(file.readLines().takeLast(200).joinToString("\n") + "\n")
            file.appendText("${java.time.LocalDateTime.now().format(stamp)}  $line\n")
        }
    }

    fun write(message: String, error: Throwable) {
        write("$message: ${error.stackTraceToString()}")
    }

    /** Catches anything that would otherwise end a thread, or the app, in silence. */
    fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> write("uncaught on ${thread.name}", error) }
        Runtime.getRuntime().addShutdownHook(Thread { write("relay: stopping") })
    }

    private const val MAX_BYTES = 512 * 1024L
}

/**
 * The device identity in the user's private app-data folder, readable only by the user.
 * On Windows %APPDATA% is already per-user; on Linux and macOS the file is chmod 600.
 *
 * Moving this into the OS keychain (DPAPI / Keychain / Secret Service) is tracked in the
 * security model; the file never leaves the machine and is excluded from Relay's own sync.
 */
class FileIdentityStore(private val file: File) : IdentityStore {
    override fun load(): RelayIdentity? = if (!file.isFile) null else runCatching { IdentityCodec.decode(file.readBytes()) }.getOrNull()

    override fun save(identity: RelayIdentity) {
        file.parentFile.mkdirs()
        file.writeBytes(IdentityCodec.encode(identity))
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }
}

enum class DesktopClipboardMode { OFF, AUTOMATIC }

@Serializable
data class DesktopSettings(
    val deviceName: String = "",
    /** Absolute folder for received files; empty means Downloads/Relay. */
    val saveFolder: String = "",
    val clipboard: DesktopClipboardMode = DesktopClipboardMode.AUTOMATIC,
    val startWithComputer: Boolean = false,
    /** Trusted devices act as one: no Accept prompts, received files ready to paste. */
    val ecosystem: Boolean = true,
    /** The drop shelf at the screen edge that files can be dragged onto. */
    val dropShelf: Boolean = true,
    /** Optional, anonymous usage statistics. Off unless the user turns it on. */
    val analytics: Boolean = false,
    /** A random id for this installation, made when statistics are first turned on. */
    val analyticsClientId: String = "",
)

/** Settings as a small JSON file next to the identity. Written atomically. */
class DesktopSettingsStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): DesktopSettings =
        if (!file.isFile) DesktopSettings() else runCatching { json.decodeFromString(DesktopSettings.serializer(), file.readText()) }.getOrDefault(DesktopSettings())

    fun save(settings: DesktopSettings) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(DesktopSettings.serializer(), settings))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}

/**
 * "Start Relay when I sign in." Uses each OS's per-user mechanism, with no admin rights:
 * the Run registry key on Windows, a LaunchAgent on macOS, an XDG autostart entry on
 * Linux. Relay starts minimised to the tray.
 */
object Autostart {
    private val launcher: String? get() = System.getProperty("jpackage.app-path")

    /** Only a packaged app has a stable launcher to register. */
    val isAvailable: Boolean get() = launcher != null

    fun set(enabled: Boolean): Boolean = runCatching {
        val exe = launcher ?: return false
        when (DesktopPaths.platform) {
            Platform.WINDOWS -> {
                val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
                val command = if (enabled) {
                    listOf("reg", "add", key, "/v", "Relay", "/t", "REG_SZ", "/d", "\"$exe\" --minimized", "/f")
                } else {
                    listOf("reg", "delete", key, "/v", "Relay", "/f")
                }
                ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0 || !enabled
            }
            Platform.MACOS -> {
                val plist = File(System.getProperty("user.home"), "Library/LaunchAgents/com.vythera.relay.plist")
                if (enabled) {
                    plist.parentFile.mkdirs()
                    plist.writeText(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0"><dict>
                          <key>Label</key><string>com.vythera.relay</string>
                          <key>ProgramArguments</key><array><string>$exe</string><string>--minimized</string></array>
                          <key>RunAtLoad</key><true/>
                        </dict></plist>
                        """.trimIndent(),
                    )
                } else {
                    plist.delete()
                }
                true
            }
            else -> {
                val entry = File(System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config", "autostart/relay.desktop")
                if (enabled) {
                    entry.parentFile.mkdirs()
                    entry.writeText("[Desktop Entry]\nType=Application\nName=Relay\nExec=\"$exe\" --minimized\nX-GNOME-Autostart-enabled=true\n")
                } else {
                    entry.delete()
                }
                true
            }
        }
    }.getOrDefault(false)
}
