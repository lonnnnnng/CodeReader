package com.codereader.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codereader.app.data.DocumentRepository
import com.codereader.app.data.MAX_CACHED_PAGE_CURSORS
import com.codereader.app.data.SafDocumentAccess
import com.codereader.app.model.EntryLocation
import com.codereader.app.model.FileType
import com.codereader.app.model.OpenDocument
import com.codereader.app.model.TextEncoding
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 游标淘汰必须关闭真实输入流，并能从已加载字符位置重新打开，不能仅检查缓存数量。 @author long */
@RunWith(AndroidJUnit4::class)
class PageCursorCacheInstrumentedTest {
    @Test
    fun cursorCacheEvictsLeastRecentlyReadStreamAndRestoresExactNextPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = ByteArray(800_000) { index -> ('a'.code + index % 26).toByte() }
        val openedStreams = mutableListOf<TrackedInputStream>()
        val access = object : SafDocumentAccess {
            override fun openInput(uri: Uri): InputStream = TrackedInputStream(bytes).also { openedStreams += it }
            override fun openOutput(uri: Uri, mode: String): OutputStream? = null
        }
        val repository = DocumentRepository(context, safDocumentAccess = access)
        try {
            val documents = (0 until MAX_CACHED_PAGE_CURSORS).map { index ->
                repository.reopen(document(index, bytes.size), TextEncoding.UTF_8)
            }
            val firstNext = repository.loadMore(documents.first())
            repository.reopen(document(MAX_CACHED_PAGE_CURSORS, bytes.size), TextEncoding.UTF_8)

            assertFalse("刚读取的游标不应被淘汰", openedStreams.first().closed)
            assertTrue("最久未读取的流应及时关闭", openedStreams[1].closed)
            assertEquals(MAX_CACHED_PAGE_CURSORS, openedStreams.count { !it.closed })

            val restored = repository.loadMore(documents[1])
            val start = documents[1].loadedCharacters.toInt()
            assertEquals(String(bytes, Charsets.UTF_8).substring(start, restored.nextCharacter.toInt()), restored.text)
            assertEquals(firstNext.nextCharacter, restored.nextCharacter)
            assertEquals(MAX_CACHED_PAGE_CURSORS, openedStreams.count { !it.closed })
        } finally {
            repository.close()
        }
        assertTrue("Repository 关闭后不应保留打开流", openedStreams.all { it.closed })
    }

    private fun document(index: Int, size: Int): OpenDocument = OpenDocument(
        name = "source-$index.txt",
        text = "",
        fileType = FileType.PLAIN_TEXT,
        canWrite = false,
        location = EntryLocation.Saf(Uri.parse("content://cursor-test/source-$index")),
        totalBytes = size.toLong(),
        largeFile = true,
        hasMore = true,
    )

    private class TrackedInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
