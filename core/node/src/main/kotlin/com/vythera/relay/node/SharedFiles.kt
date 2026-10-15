package com.vythera.relay.node

import com.vythera.relay.protocol.ToolItem
import java.io.File

/** Only paths beneath the chosen shared folder may be listed or downloaded, including symlinks. */
object SharedFiles {
    /**
     * A dot-name is private on every platform, not only where the OS calls it hidden: on
     * Windows `isHidden` means the file attribute, so Relay's own `.relay` folder of
     * transfer records showed up in, and could be fetched from, the shared inbox.
     */
    private fun isPrivate(file: File) = file.isHidden || file.name.startsWith(".")

    fun resolve(root: File, path: String): File {
        require(path.length <= 4096 && !File(path).isAbsolute)
        require(path.split('/', '\\').none { it.startsWith(".") }) { "Not shared" }
        val base = root.canonicalFile
        val target = File(base, path).canonicalFile
        require(target.toPath().startsWith(base.toPath())) { "Outside shared folder" }
        return target
    }

    fun list(root: File, path: String, offset: Int): Pair<List<ToolItem>, Int?> {
        require(offset >= 0)
        val folder = resolve(root, path)
        require(folder.isDirectory)
        val files = folder.listFiles()?.filter { file ->
            !isPrivate(file) && runCatching { resolve(root, file.relativeTo(root.canonicalFile).invariantSeparatorsPath) }.isSuccess
        }?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: error("Folder is unavailable")
        return files.drop(offset).take(50).map {
            ToolItem(it.relativeTo(root.canonicalFile).invariantSeparatorsPath, it.name.take(512), if (it.isDirectory) "Folder" else "${it.length()} bytes", if (it.isDirectory) "folder" else "file")
        } to (offset + 50).takeIf { it < files.size }
    }
}
