package com.codereader.app.ui

import com.codereader.app.model.EntryLocation
import com.codereader.app.model.SourceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** @author long */
class MarkdownResourceIndexTest {
    private val image = source("docs/images/architecture.svg")
    private val attachment = source("attachments/readme.txt")
    private val index = MarkdownResourceIndex(
        documentPath = "docs/guide/README.md",
        projectEntries = listOf(image, attachment),
    )

    @Test
    fun `相对路径按 Markdown 所在目录解析`() {
        assertEquals(image.source, index.resolve("../images/architecture.svg"))
        assertEquals(attachment.source, index.resolve("../../attachments/readme.txt#details"))
    }

    @Test
    fun `项目根越界和外部地址不能读取本地文件`() {
        assertNull(index.resolve("../../../private.txt"))
        assertNull(index.resolve("/absolute/path.png"))
        assertNull(index.resolve("https://example.com/image.png"))
        assertNull(index.resolve("data:image/png;base64,AAAA"))
    }

    @Test
    fun `多个预览共享项目路径表但保持各自相对目录`() {
        var entryReads = 0
        val entries = listOf(image, attachment)
        val observedEntries = object : AbstractList<com.codereader.app.model.ProjectTreeEntry>() {
            override val size: Int get() = entries.size
            override fun get(index: Int): com.codereader.app.model.ProjectTreeEntry {
                entryReads++
                return entries[index]
            }
        }
        val resources = MarkdownProjectResources(observedEntries)
        val guide = MarkdownResourceIndex("docs/guide/README.md", resources)
        val root = MarkdownResourceIndex("README.md", resources)

        assertEquals(image.source, guide.resolve("../images/architecture.svg"))
        assertEquals(image.source, root.resolve("docs/images/architecture.svg"))
        assertNull(root.resolve("../docs/images/architecture.svg"))
        assertEquals("共享路径表不应因文档切换再次遍历项目", entries.size, entryReads)
    }

    @Test
    fun `共享路径表保留百分号和加号文件名并隔离不同项目`() {
        val named = source("docs/图 + 示例.svg")
        val firstProject = MarkdownProjectResources(listOf(named))
        val first = MarkdownResourceIndex("docs/README.md", firstProject)
        val other = MarkdownResourceIndex("docs/README.md", MarkdownProjectResources(emptyList()))

        assertEquals(named.source, first.resolve("%E5%9B%BE%20+%20%E7%A4%BA%E4%BE%8B.svg"))
        assertNull(first.resolve("../../docs/图 + 示例.svg"))
        assertNull(other.resolve("图 + 示例.svg"))
        assertTrue(first.documentUrl.endsWith("/project/docs/README.md"))
    }

    private fun source(path: String): com.codereader.app.model.ProjectTreeEntry {
        val file = File("/tmp/markdown-resource-test/$path")
        val entry = SourceEntry(
            name = file.name,
            isDirectory = false,
            size = 1,
            canWrite = false,
            location = EntryLocation.Local(file),
        )
        return com.codereader.app.model.ProjectTreeEntry(
            source = entry,
            path = path,
            parentId = null,
            depth = path.count { it == '/' },
        )
    }
}
