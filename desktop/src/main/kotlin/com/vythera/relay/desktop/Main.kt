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
    val lock = acquireSingleInstanceLock() ?: run {
        // Relay is already running: ask that copy to come forward instead of starting a
        // second one, which is what someone expects from clicking the icon again.
        if (!askRunningInstanceToShow()) {
            JOptionPane.showMessageDialog(null, "Relay is already running. Look for it in the system tray.", "Relay", JOptionPane.INFORMATION_MESSAGE)
        }
        exitProcess(0)
    }
    DesktopLog.installCrashHandler()
    DesktopLog.write("relay: starting")
    val relay = DesktopRelay().apply { start() }
    val startHidden = "--minimized" in args

    application {
        var windowVisible by remember { mutableStateOf(!startHidden) }
        val navigation = remember {
            DesktopNavigation().apply {
                args.firstOrNull { it.startsWith("--section=") }?.substringAfter("=")
                    ?.let { name -> Section.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                    ?.let { section = it }
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
            listenForShowRequests { windowVisible = true }
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
 * listens; a second launch says "show" and steps aside.
 */
private const val SHOW_PORT = 47802

private fun askRunningInstanceToShow(): Boolean = runCatching {
    java.net.Socket().use { socket ->
        socket.connect(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), SHOW_PORT), 1500)
        socket.getOutputStream().write("show\n".toByteArray())
        socket.getOutputStream().flush()
    }
    true
}.getOrDefault(false)

/** Listens for those messages for as long as Relay runs. */
private fun listenForShowRequests(onShow: () -> Unit) {
    Thread {
        runCatching {
            java.net.ServerSocket(SHOW_PORT, 4, java.net.InetAddress.getLoopbackAddress()).use { server ->
                while (true) {
                    server.accept().use { client ->
                        val line = client.getInputStream().bufferedReader().readLine()
                        if (line?.trim() == "show") onShow()
                    }
                }
            }
        }.onFailure { DesktopLog.write("relay: show channel closed", it) }
    }.apply { isDaemon = true; name = "relay-show-listener" }.start()
}
