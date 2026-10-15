package com.vythera.relay.transfer

/**
 * Turns names chosen by a remote device into names that are safe to write locally.
 *
 * A received name is untrusted input: it could contain path separators, `..`, control
 * characters, or names Windows reserves. Everything here is total (never throws) so a
 * hostile name degrades to a boring one rather than failing the transfer.
 */
object FileNames {
    private const val MAX_NAME_BYTES = 200
    private const val FALLBACK = "file"
    private val WINDOWS_RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )
    private val FORBIDDEN = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun sanitize(name: String): String {
        var cleaned = name
            .map { if (it in FORBIDDEN || it.code < 0x20 || it.code == 0x7f) '_' else it }
            .joinToString("")
            .trim()
            .trimEnd('.')
            .trimStart('.')
        if (cleaned.isEmpty()) cleaned = FALLBACK
        if (cleaned.substringBefore('.').lowercase() in WINDOWS_RESERVED) cleaned = "_$cleaned"
        return truncateUtf8(cleaned)
    }

    /** Sanitizes each segment of a folder-relative path and drops `.`/`..`/empty segments. */
    fun sanitizeRelativePath(path: String): String =
        path.split('/', '\\')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { sanitize(it) }

    /**
     * Returns [name] if unused, otherwise "name (1).ext", "name (2).ext", …
     * The extension is kept intact, including compound ones like ".tar.gz".
     */
    fun resolveCollision(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val (base, extension) = split(name)
        var n = 1
        while (true) {
            val candidate = "$base ($n)$extension"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    private fun split(name: String): Pair<String, String> {
        val lower = name.lowercase()
        val compound = listOf(".tar.gz", ".tar.bz2", ".tar.xz").firstOrNull { lower.endsWith(it) && name.length > it.length }
        if (compound != null) return name.dropLast(compound.length) to name.takeLast(compound.length)
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    private fun truncateUtf8(name: String): String {
        if (name.encodeToByteArray().size <= MAX_NAME_BYTES) return name
        val (base, extension) = split(name)
        val budget = MAX_NAME_BYTES - extension.encodeToByteArray().size
        val builder = StringBuilder()
        var used = 0
        var i = 0
        while (i < base.length) {
            val cp = base.codePointAt(i)
            val bytes = String(Character.toChars(cp)).encodeToByteArray().size
            if (used + bytes > budget) break
            builder.appendCodePoint(cp)
            used += bytes
            i += Character.charCount(cp)
        }
        return builder.toString() + extension
    }
}
