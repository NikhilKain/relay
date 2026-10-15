package com.vythera.relay.node

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class SharedFilesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `only files beneath the selected root can be resolved`() {
        val root = temp.newFolder("shared")
        val file = File(root, "hello.txt").apply { writeText("hello") }
        assertEquals(file.canonicalFile, SharedFiles.resolve(root, "hello.txt"))
        assertFailsWith<IllegalArgumentException> { SharedFiles.resolve(root, "../private.txt") }
        assertFailsWith<IllegalArgumentException> { SharedFiles.resolve(root, temp.root.absolutePath) }
        assertFailsWith<IllegalArgumentException> { SharedFiles.resolve(root, "../shared-other/file") }
    }

    @Test fun `dot folders such as Relay's own records are neither listed nor fetchable`() {
        val root = temp.newFolder("shared")
        File(root, "photo.jpg").writeText("x")
        File(root, ".relay").apply { mkdirs() }.let { File(it, "transfer-0.json").writeText("{}") }
        File(root, ".notes.txt").writeText("x")
        assertEquals(listOf("photo.jpg"), SharedFiles.list(root, "", 0).first.map { it.title })
        assertFailsWith<IllegalArgumentException> { SharedFiles.resolve(root, ".relay/transfer-0.json") }
        assertFailsWith<IllegalArgumentException> { SharedFiles.resolve(root, ".relay") }
        assertFailsWith<IllegalArgumentException> { SharedFiles.resolve(root, ".notes.txt") }
        assertFailsWith<IllegalArgumentException> { SharedFiles.resolve(root, "sub\\.relay\\x.json") }
    }

    @Test fun `folder listing is bounded and paginated`() {
        val root = temp.newFolder("shared")
        repeat(60) { File(root, "file-$it.txt").writeText("hello") }
        val (first, next) = SharedFiles.list(root, "", 0)
        assertEquals(50, first.size)
        assertEquals(50, next)
        val (last, end) = SharedFiles.list(root, "", next!!)
        assertEquals(10, last.size)
        assertNull(end)
        assertTrue(first.map { it.id }.intersect(last.map { it.id }.toSet()).isEmpty())
    }
}
