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

    /** "DESKTOP-ALEX" reads better as "Desktop-Alex". */
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
    /** "Send with Relay" in the file manager's right-click menu. */
    val fileManagerMenu: Boolean = true,
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
        com.vythera.relay.node.AtomicFiles.write(file, json.encodeToString(DesktopSettings.serializer(), settings))
    }
}

/**
 * "Start Relay when I sign in." Uses each OS's per-user mechanism, with no admin rights:
 * the Run registry key on Windows, a LaunchAgent on macOS, an XDG autostart entry on
 * Linux. Relay starts minimised to the tray.
 */
/**
 * How this copy of Relay was started, which decides whether it can be registered to start
 * with the computer.
 *
 * Both the installer and the portable zip produce a packaged launcher, so the launcher
 * alone does not say whether Relay will still be there tomorrow. Two ways of opening it
 * leave it somewhere that disappears: running Relay.exe from inside the zip (Windows,
 * 7-Zip and WinRAR all unpack it into the temp folder and clean that later), and running
 * Relay.app straight from the mounted .dmg on a Mac (gone when the disk image is ejected).
 */
object Installation {
    /** The launcher Relay was started from; null when running from Gradle in development. */
    val launcher: File? = System.getProperty("jpackage.app-path")?.let(::File)

    /** Relay was opened from a place that will be cleaned away. */
    val isTemporary: Boolean = launcher?.let(::isInTemporaryPlace) ?: false

    /** A packaged copy in a lasting place: the only kind worth registering at sign-in. */
    val isLasting: Boolean get() = launcher != null && !isTemporary

    private fun isInTemporaryPlace(file: File): Boolean {
        val path = canonical(file)
        return when (DesktopPaths.platform) {
            Platform.MACOS -> path.startsWith("/Volumes/") || path.startsWith(canonical(File(System.getProperty("java.io.tmpdir"))))
            else -> {
                val temps = listOfNotNull(System.getProperty("java.io.tmpdir"), System.getenv("TEMP"), System.getenv("TMP"))
                temps.any { path.startsWith(canonical(File(it))) }
            }
        }
    }

    // Windows hands out 8.3 short names (C:\Users\JOHNSM~1\...) in some places and long
    // ones in others, so compare real paths, and without regard to case.
    private fun canonical(file: File): String {
        val real = runCatching { file.toPath().toRealPath().toString() }.getOrElse { file.absolutePath }
        val withSlash = if (real.endsWith(File.separator)) real else real + File.separator
        return if (DesktopPaths.platform == Platform.WINDOWS) withSlash.lowercase() else withSlash
    }
}

object Autostart {
    /** Registering a launcher that is about to be deleted would start nothing next time. */
    val isAvailable: Boolean get() = Installation.isLasting

    /** Whether the OS will start Relay at sign-in, whichever copy registered it. */
    fun isRegistered(): Boolean = runCatching {
        when (DesktopPaths.platform) {
            Platform.WINDOWS -> ProcessBuilder("reg", "query", RUN_KEY, "/v", "Relay")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor() == 0
            Platform.MACOS -> launchAgent.isFile
            else -> autostartEntry.isFile
        }
    }.getOrDefault(false)

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val launchAgent get() = File(System.getProperty("user.home"), "Library/LaunchAgents/com.vythera.relay.plist")
    private val autostartEntry get() = File(System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config", "autostart/relay.desktop")

    fun set(enabled: Boolean): Boolean = runCatching {
        // Turning it off needs no launcher: it only removes what an earlier copy added.
        val exe = Installation.launcher?.path.orEmpty()
        if (enabled && (exe.isEmpty() || !Installation.isLasting)) return false
        when (DesktopPaths.platform) {
            Platform.WINDOWS -> {
                val command = if (enabled) {
                    listOf("reg", "add", RUN_KEY, "/v", "Relay", "/t", "REG_SZ", "/d", "\"$exe\" --minimized", "/f")
                } else {
                    listOf("reg", "delete", RUN_KEY, "/v", "Relay", "/f")
                }
                ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0 || !enabled
            }
            Platform.MACOS -> {
                val plist = launchAgent
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
                val entry = autostartEntry
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
