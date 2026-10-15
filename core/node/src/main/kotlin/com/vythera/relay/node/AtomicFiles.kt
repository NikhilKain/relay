package com.vythera.relay.node

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Replaces a file so that a crash or a power cut leaves either the old contents or the
 * new ones, never neither. Pairings live in such a file: losing it means pairing every
 * device again.
 *
 * Writing to a temporary file and renaming was not enough on Windows, where rename
 * refuses an existing target: the fallback deleted the old file first, leaving a moment
 * with no file at all. And without a sync, the renamed file can survive a power cut
 * while its contents do not.
 */
object AtomicFiles {
    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING)
        }
    }
}
