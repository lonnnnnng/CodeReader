package com.codereader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codereader.app.data.DocumentRepository
import com.codereader.app.domain.TextSearchOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** @author long */
@RunWith(AndroidJUnit4::class)
class LargeFileInstrumentedTest {
    @Test
    fun fileOverTenMegabytesCanOpenPageAndSearchToEnd() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("large-file-over-ten-mb-", ".log", context.cacheDir)
        val repository = DocumentRepository(context)
        try {
            val bodyLine = "ordinary ".repeat(90)
            val targetLine = 16_385
            file.bufferedWriter().use { writer ->
                repeat(targetLine - 1) { writer.appendLine(bodyLine) }
                writer.appendLine("val needleAfterTenMegabytes = 1")
            }
            assertTrue(file.length() > 10L * 1024 * 1024)
            val document = repository.openLocal(file)
            assertTrue(document.largeFile)
            assertTrue(document.hasMore)
            assertFalse(document.canWrite)
            val next = repository.loadMore(document)
            assertTrue(next.nextCharacter > document.loadedCharacters)

            val result = repository.searchDocument(
                document,
                "needleAfterTenMegabytes",
                TextSearchOptions(caseSensitive = true, wholeWord = true),
                10,
            )
            assertEquals(targetLine, result.matches.single().line)
            assertEquals(4, result.matches.single().column)
        } finally {
            repository.close()
            file.delete()
        }
    }

    @Test
    fun largeFileIsOpenedReadOnlyAndCanLoadNextPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "large-source.log")
        file.bufferedWriter().use { writer ->
            repeat(90_000) { index -> writer.appendLine("$index service.findUser()") }
        }

        val repository = DocumentRepository(context)
        val document = repository.openLocal(file)
        val next = repository.loadMore(document)

        assertTrue("大文件没有进入分段模式", document.largeFile)
        assertFalse("分段模式不应允许覆盖保存", document.canWrite)
        assertTrue("首段内容为空", document.text.isNotEmpty())
        assertTrue("第二段内容为空", next.text.isNotEmpty())
        assertTrue("第二段游标没有前进", next.nextCharacter > document.loadedCharacters)

        val utf16File = File(context.cacheDir, "large-source-utf16.log")
        utf16File.outputStream().use { output ->
            output.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            OutputStreamWriter(output, StandardCharsets.UTF_16LE).use { writer ->
                repeat(45_000) { index -> writer.appendLine("$index 用户服务.findUser()") }
            }
        }
        val utf16Document = repository.openLocal(utf16File)
        assertTrue("UTF-16 大文件没有进入分段模式", utf16Document.largeFile)
        assertTrue("UTF-16 大文件没有按 BOM 解码", utf16Document.text.contains("用户服务.findUser()"))
    }

    @Test
    fun largeFileSearchScansPastLoadedPageAndKeepsExactPosition() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "large-file-search.log")
        val targetLine = 70_001
        file.bufferedWriter().use { writer ->
            repeat(targetLine - 1) { index -> writer.appendLine("$index ordinary source line") }
            writer.appendLine("val uniqueNeedle = uniqueNeedle")
            repeat(10_000) { index -> writer.appendLine("tail $index") }
        }

        val repository = DocumentRepository(context)
        try {
            val document = repository.openLocal(file)
            var lastScannedLines = 0
            var lastProgressMatches = 0
            val result = repository.searchDocument(
                document = document,
                query = "uniqueNeedle",
                options = TextSearchOptions(caseSensitive = true, wholeWord = true),
                maxStoredMatches = 10,
                onProgress = { progress ->
                    lastScannedLines = progress.scannedLines
                    lastProgressMatches = progress.matchesFound
                },
            )

            assertTrue("测试文件应进入分段读取", document.largeFile)
            assertFalse("首段不应提前包含目标内容", document.text.contains("uniqueNeedle"))
            assertEquals(2, result.totalMatches)
            assertEquals(targetLine, result.matches.first().line)
            assertEquals(4, result.matches.first().column)
            assertFalse(result.truncated)
            assertTrue("流式搜索没有报告文件末尾进度", lastScannedLines > targetLine)
            assertEquals("流式进度的命中数与最终结果不一致", 2, lastProgressMatches)
        } finally {
            repository.close()
            file.delete()
        }
    }
}
