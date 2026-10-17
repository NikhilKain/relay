package com.vythera.relay.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.vythera.relay.desktop.resources.*
import com.vythera.relay.desktop.ui.DesktopApp
import com.vythera.relay.desktop.ui.DesktopNavigation
import com.vythera.relay.desktop.ui.DropShelf
import com.vythera.relay.desktop.ui.Section
import com.vythera.relay.desktop.ui.RelayMarkPainter
import com.vythera.relay.desktop.ui.SendPicker
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/**
 * Relay for desktop. Lives in the system tray so it can receive and sync the clipboard
 * while its window is closed; closing the window hides it, Quit (in the tray) exits.
 *
 * `--minimized` starts in the tray only, which is how sign-in autostart launches it.
 */
fun main(args: Array<String>) {
    // "Send with Relay" in a file manager: everything after --send is a path.
    val toSend = args.dropWhile { it != "--send" }.drop(1).map(::File).filter { it.exists() }
    val lock = acquireSingleInstanceLock() ?: run {
        // Relay is already running: hand it the files, or ask it to come forward instead
        // of starting a second copy, which is what someone expects from clicking again.
        if (toSend.isNotEmpty()) {
            // Explorer starts one Relay per selected file, all at once, while the first may
            // still be starting; keep knocking for a while rather than giving up.
            askRunningInstance(sendRequest(toSend), patienceMillis = 20_000)
        } else if (!askRunningInstance("show\n", patienceMillis = 1_500)) {
            JOptionPane.showMessageDialog(null, "Relay is already running. Look for it in the system tray.", "Relay", JOptionPane.INFORMATION_MESSAGE)
        }
        exitProcess(0)
    }
    DesktopLog.installCrashHandler()
    DesktopLog.write("relay: starting")
    // Anything that stops Relay starting would otherwise surface only as the launcher's
    // bare "Failed to launch JVM". Say what happened, and where the details are.
    val relay = try {
        DesktopRelay().apply { start() }
    } catch (error: Throwable) {
        DesktopLog.write("relay: could not start", error)
        JOptionPane.showMessageDialog(
            null,
            "Relay could not start: ${error.message ?: error.javaClass.simpleName}\n\n" +
                "Details are in ${File(DesktopPaths.data, "relay.log").path}.\n" +
                "If this keeps happening, please send that file to ${com.vythera.relay.desktop.ui.RelayLinks.FEEDBACK_EMAIL}.",
            "Relay",
            JOptionPane.ERROR_MESSAGE,
        )
        exitProcess(1)
    }
    SendInbox.add(toSend)
    val startHidden = "--minimized" in args || toSend.isNotEmpty()

    application {
        var windowVisible by remember { mutableStateOf(!startHidden) }
        val navigation = remember {
            DesktopNavigation().apply {
                args.firstOrNull { it.startsWith("--section=") }?.substringAfter("=")
                    ?.let { name -> Section.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                    ?.let { section = it }
                // Opens on this computer's connection code, as the tray's "Add a device" does.
                if ("--add-device" in args) {
                    section = Section.Devices
                    addingDevice = true
                }
            }
        }
        val trayState = rememberTrayState()
        val scope = rememberCoroutineScope()
        val icon = remember { RelayMarkPainter() }
        val devices by relay.devices.collectAsState()
        val trusted = devices.filter { it.isTrusted && it.isReachable }

        LaunchedEffect(Unit) {
            relay.notices.collect { trayState.sendNotification(Notification(it.title, it.message)) }
        }

        LaunchedEffect(Unit) {
            listenForOtherCopies(onShow = { windowVisible = true }, onSend = SendInbox::add)
        }
        val waiting by SendInbox.files.collectAsState()
        if (waiting.isNotEmpty()) {
            SendPicker(relay, waiting, onClose = SendInbox::clear)
        }

        fun quit() {
            relay.stop()
            lock.release()
            exitApplication()
        }

        Tray(
            icon = icon,
            state = trayState,
            tooltip = stringResource(Res.string.app_name),
            onAction = { windowVisible = true },
            menu = {
                Item(stringResource(Res.string.tray_open), onClick = { windowVisible = true })
                Item(stringResource(Res.string.tray_add_device), onClick = {
                    navigation.section = Section.Devices
                    navigation.addingDevice = true
                    windowVisible = true
                })
                Separator()
                trusted.forEach { device ->
                    Item(stringResource(Res.string.tray_send_file, device.name), onClick = {
                        val dialog = FileDialog(null as java.awt.Frame?, "Send to ${device.name}", FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
                        dialog.files.orEmpty().toList().takeIf { it.isNotEmpty() }?.let { relay.sendFiles(device.id, it) }
                    })
                }
                Item(stringResource(Res.string.tray_send_clipboard), onClick = {
                    scope.launch {
                        val sent = relay.sendClipboard()
                        trayState.sendNotification(
                            Notification(
                                "Relay",
                                if (sent > 0) "Clipboard sent to $sent device(s)" else "Nothing to send. Copy some text and keep a trusted device nearby.",
                            ),
                        )
                    }
                })
                Separator()
                Item(stringResource(Res.string.tray_quit), onClick = ::quit)
            },
        )

        val settings by relay.settings.collectAsState()
        if (settings.dropShelf) {
            DropShelf(relay, onChooseFiles = { id ->
                val dialog = FileDialog(null as java.awt.Frame?, "Send to ${relay.name(id)}", FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
                dialog.files.orEmpty().toList().takeIf { it.isNotEmpty() }?.let { relay.sendFiles(id, it) }
            })
        }

        // The window stays in the composition while hidden, so Relay keeps running in the
        // tray: an application with no windows at all would quit.
        Window(
            onCloseRequest = { windowVisible = false },
            visible = windowVisible,
            title = "Relay",
            icon = icon,
            state = rememberWindowState(size = DpSize(1180.dp, 780.dp)),
        ) {
            window.minimumSize = java.awt.Dimension(860, 600)
            DesktopApp(relay, navigation, window)
        }
    }
}

/** One Relay per user: a second copy would fight over the discovery port. */
private fun acquireSingleInstanceLock(): FileLock? = runCatching {
    val file = File(DesktopPaths.data, "relay.lock")
    RandomAccessFile(file, "rw").channel.tryLock()
}.getOrNull()

/**
 * A loopback line between copies of Relay, bound to this machine only. The running copy
 * listens; a second launch says "show", or "send" followed by one path per line and a
 * blank line, and steps aside. A send only opens the device picker: nothing leaves the
 * computer until someone chooses devices and presses Send.
 */
private const val SHOW_PORT = 47802

private fun sendRequest(files: List<File>) = "send\n" + files.joinToString("") { it.absolutePath + "\n" } + "\n"

private fun askRunningInstance(message: String, patienceMillis: Long): Boolean {
    val deadline = System.currentTimeMillis() + patienceMillis
    while (true) {
        val sent = runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), SHOW_PORT), 1500)
                socket.getOutputStream().write(message.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
        }.isSuccess
        if (sent || System.currentTimeMillis() > deadline) return sent
        Thread.sleep(300)
    }
}

/** Listens for those messages for as long as Relay runs. */
private fun listenForOtherCopies(onShow: () -> Unit, onSend: (List<File>) -> Unit) {
    Thread {
        runCatching {
            java.net.ServerSocket(SHOW_PORT, 16, java.net.InetAddress.getLoopbackAddress()).use { server ->
                while (true) {
                    runCatching {
                        server.accept().use { client ->
                            client.soTimeout = 3_000
                            val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
                            when (reader.readLine()?.trim()) {
                                "show" -> onShow()
                                "send" -> onSend(
                                    generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.take(MAX_SEND_PATHS)
                                        .map(::File).filter { it.exists() }.toList(),
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure { DesktopLog.write("relay: show channel closed", it) }
    }.apply { isDaemon = true; name = "relay-show-listener" }.start()
}

private const val MAX_SEND_PATHS = 10_000

/** Files waiting for the user to pick devices. Several requests in a row add up. */
private object SendInbox {
    private val _files = kotlinx.coroutines.flow.MutableStateFlow<List<File>>(emptyList())
    val files: kotlinx.coroutines.flow.StateFlow<List<File>> = _files

    fun add(more: List<File>) {
        if (more.isNotEmpty()) _files.value = (_files.value + more).distinctBy { it.absolutePath }
    }

    fun clear() {
        _files.value = emptyList()
    }
}
