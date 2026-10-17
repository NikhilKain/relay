package com.vythera.relay.desktop

import com.vythera.relay.protocol.Platform
import java.io.File

/**
 * "Send with Relay" in the file manager's right-click menu. Choosing it starts Relay with
 * `--send` and the selected paths; the running copy then asks which devices to send to.
 *
 * Everything is per user and needs no admin rights: a few keys under HKCU\Software\Classes
 * on Windows (on Windows 11 the entry is under "Show more options"), a Dolphin service
 * menu and a Nautilus script on Linux. macOS needs a Finder extension, which is not here
 * yet. Only a lasting installation registers itself, as with starting at sign-in.
 */
object FileManagerMenu {
    val isAvailable: Boolean get() = Installation.isLasting && DesktopPaths.platform != Platform.MACOS

    /** Adds the entry, or removes it. Re-adding is harmless and keeps the path current. */
    fun apply(enabled: Boolean) {
        val exe = Installation.launcher?.path ?: return
        runCatching {
            when (DesktopPaths.platform) {
                Platform.WINDOWS -> windows(enabled && isAvailable, exe)
                Platform.MACOS -> Unit
                else -> linux(enabled && isAvailable, exe)
            }
        }.onFailure { DesktopLog.write("file manager menu: could not update", it) }
    }

    private fun windows(enabled: Boolean, exe: String) {
        // Files (*) and folders (Directory) each get their own verb.
        for (kind in listOf("*", "Directory")) {
            val key = "HKCU\\Software\\Classes\\$kind\\shell\\RelaySend"
            if (enabled) {
                reg("add", key, "/v", "MUIVerb", "/d", "Send with Relay", "/f")
                reg("add", key, "/v", "Icon", "/d", "\"$exe\"", "/f")
                // Explorer otherwise hides verbs once more than 15 items are selected.
                reg("add", key, "/v", "MultiSelectModel", "/d", "Player", "/f")
                reg("add", "$key\\command", "/ve", "/d", "\"$exe\" --send \"%1\"", "/f")
            } else {
                reg("delete", key, "/f")
            }
        }
    }

    private fun reg(vararg args: String) {
        ProcessBuilder(listOf("reg") + args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .start().waitFor()
    }

    private fun linux(enabled: Boolean, exe: String) {
        val data = File(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share")
        val dolphin = File(data, "kio/servicemenus/relay-send.desktop")
        val nautilus = File(data, "nautilus/scripts/Send with Relay")
        if (!enabled) {
            dolphin.delete()
            nautilus.delete()
            return
        }
        // Desktop entries quote with double quotes, shell scripts more safely with single.
        val entryQuoted = "\"" + exe.replace(Regex("""(["`$\\])"""), "\\\\$1") + "\""
        val shellQuoted = "'" + exe.replace("'", "'\\''") + "'"
        dolphin.parentFile.mkdirs()
        dolphin.writeText(
            """
            [Desktop Entry]
            Type=Service
            MimeType=all/all;
            Actions=relaySend

            [Desktop Action relaySend]
            Name=Send with Relay
            Icon=relay
            Exec=$entryQuoted --send %F
            """.trimIndent() + "\n",
        )
        nautilus.parentFile.mkdirs()
        nautilus.writeText("#!/bin/sh\n# Added by Relay: sends the selected files to your devices.\nexec $shellQuoted --send \"$@\"\n")
        // Plasma 6 and Nautilus only run entries that are executable.
        dolphin.setExecutable(true)
        nautilus.setExecutable(true)
    }
}
