package com.codereader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codereader.app.data.DocumentRepository
import com.codereader.app.domain.ProjectSearchOptions
import com.codereader.app.domain.ProjectSearchScope
import com.codereader.app.domain.TextSearchOptions
import com.codereader.app.model.EntryLocation
import com.codereader.app.model.FileType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 验证超过旧搜索上限的源码仍能扫描到文件后半段命中。 @author long */
@RunWith(AndroidJUnit4::class)
class LargeProjectSearchInstrumentedTest {
    @Test
    fun projectSearchMatchesFileSearchAcrossCrLfAndMixedLineEndings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-search-line-endings").apply { mkdirs() }
        val repository = DocumentRepository(context)
        try {
            val texts = mapOf(
                "lf.txt" to "first\nneedle\nthird\nneedle\n",
                "crlf.txt" to "first\r\nneedle\r\nthird\r\nneedle\r\n",
                "cr.txt" to "first\rneedle\rthird\rneedle\r",
                "mixed.txt" to "first\r\nneedle\rthird\nneedle",
            )
            texts.forEach { (name, text) -> File(root, name).writeText(text) }
            val options = TextSearchOptions(caseSensitive = true, regularExpression = true)
            val page = repository.searchProject(
                repository.indexProject(EntryLocation.Local(root)),
                "^needle$",
                ProjectSearchOptions(text = options),
            )

            assertEquals(8, page.totalMatches)
            assertEquals(4, page.matchedFiles)
            texts.keys.forEach { name ->
                val document = repository.openLocal(File(root, name))
                val fileMatches = repository.searchDocument(document, "^needle$", options, 10)
                assertEquals(listOf(2, 4), fileMatches.matches.map { it.line })
                assertEquals(fileMatches.matches.map { it.line }, page.results.filter { it.path == name }.map { it.line })
            }
        } finally {
            repository.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun projectSearchPreservesLongLineMatchesAndTotalsBeyondDisplayLimit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-search-long-lines").apply { mkdirs() }
        val repository = DocumentRepository(context)
        try {
            // 单行跨越多个字符页时仍需保持整行正则语义，不能为限制缓存把一个匹配拆成几行。 @author long
            File(root, "long.txt").writeText("first\n" + "x".repeat(3 * 256 * 1024) + " needle\n")
            File(root, "many.txt").writeText((1..12).joinToString("\r\n") { "needle needle" })
            val entries = repository.indexProject(EntryLocation.Local(root))
            val long = repository.searchProject(
                entries,
                "needle$",
                ProjectSearchOptions(text = TextSearchOptions(regularExpression = true), pathFilter = "long.txt"),
            )
            assertEquals(1, long.totalMatches)
            assertEquals(2, long.results.single().line)
            assertTrue(long.results.single().excerpt.length <= 180)

            val many = repository.searchProject(entries, "needle", ProjectSearchOptions(pathFilter = "many.txt"))
            assertEquals(24, many.totalMatches)
            assertEquals(8, many.results.size)
            assertTrue(many.truncated)
        } finally {
            repository.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun projectSearchFindsMatchAfterTwoMegabytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-search-large").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val targetLine = 120_001
            File(root, "Large.kt").bufferedWriter().use { writer ->
                repeat(targetLine) { index -> writer.appendLine("val line$index = \"ordinary source\"") }
                writer.appendLine("fun targetAfterLargePage() = \"needle-after-two-megabytes\"")
            }

            val repository = DocumentRepository(context)
            val entries = repository.indexProject(EntryLocation.Local(root))
            val results = repository.searchProject(entries, "needle-after-two-megabytes")

            assertTrue("测试源码应超过 2 MB", File(root, "Large.kt").length() > 2 * 1024 * 1024)
            assertEquals(1, results.size)
            assertEquals(targetLine + 1, results.single().line)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun projectSearchAppliesTextTypeDirectoryAndPathFilters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-search-options").apply {
            deleteRecursively()
            resolve("src/main/service").mkdirs()
            resolve("src/test/service").mkdirs()
        }
        try {
            File(root, "src/main/service/UserService.kt").writeText("val User = user + user")
            File(root, "src/test/service/UserService.kt").writeText("val user = user")
            File(root, "src/main/service/UserService.java").writeText("String user = user;")

            val repository = DocumentRepository(context)
            val entries = repository.indexProject(EntryLocation.Local(root))
            val page = repository.searchProject(
                entries = entries,
                query = "user",
                options = ProjectSearchOptions(
                    text = TextSearchOptions(caseSensitive = true, wholeWord = true),
                    fileType = FileType.KOTLIN,
                    scope = ProjectSearchScope.CURRENT_DIRECTORY,
                    directoryPath = "src/main",
                    pathFilter = "service",
                ),
            )

            assertEquals(1, page.results.size)
            assertEquals("src/main/service/UserService.kt", page.results.single().path)
            assertEquals(2, page.totalMatches)
            assertEquals(1, page.matchedFiles)
            assertTrue(!page.truncated)
        } finally {
            root.deleteRecursively()
        }
    }
}
