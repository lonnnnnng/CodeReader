package com.codereader.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codereader.app.data.DocumentRepository
import com.codereader.app.data.SafDocumentAccess
import com.codereader.app.model.TextEncoding
import com.codereader.app.ui.ReaderViewModel
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 用受控来源暂停 IO 后切换标签，确保旧请求不会覆盖其他文件或等待期间的新编辑。 @author long */
@RunWith(AndroidJUnit4::class)
class ReaderReadRaceInstrumentedTest {
    @Test
    fun loadingMoreUpdatesOriginTabAndKeepsOtherTabDraft() = runBlocking {
        withPausedRead(large = true) { viewModel, source, other, access, repository ->
            val before = viewModel.state.value.tabs.first { it.document.id == source.toString() }
            repository.close()
            withContext(Dispatchers.Main) { viewModel.loadMore() }
            assertTrue(access.started.await(5, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) {
                viewModel.switchTab(other.toString())
                viewModel.updateDraft("unsaved B")
            }
            access.release.countDown()
            val completed = withTimeout(5_000) {
                viewModel.state.first { state ->
                    state.tabs.any { it.document.id == source.toString() && it.document.loadedCharacters > before.document.loadedCharacters }
                }
            }

            assertEquals(other.toString(), completed.activeTabId)
            assertEquals("unsaved B", completed.draftText)
            assertTrue(completed.dirty)
            assertEquals("b.kt", completed.document?.name)
            val origin = completed.tabs.first { it.document.id == source.toString() }
            assertTrue(origin.draftText.startsWith(before.draftText))
            assertEquals(origin.draftText.length.toLong(), origin.document.loadedCharacters)
        }
    }

    @Test
    fun encodingReopenUpdatesOriginTabAndKeepsOtherTabDraft() = runBlocking {
        withPausedRead(large = false) { viewModel, source, other, access, _ ->
            withContext(Dispatchers.Main) { viewModel.setDocumentEncoding(TextEncoding.GB18030) }
            assertTrue(access.started.await(5, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) {
                viewModel.switchTab(other.toString())
                viewModel.updateDraft("unsaved B")
            }
            access.release.countDown()
            val completed = withTimeout(5_000) {
                viewModel.state.first { state ->
                    state.tabs.any { it.document.id == source.toString() && it.document.encoding == TextEncoding.GB18030 }
                }
            }

            assertEquals(other.toString(), completed.activeTabId)
            assertEquals("unsaved B", completed.draftText)
            assertTrue(completed.dirty)
            assertEquals(TextEncoding.UTF_8, completed.document?.encoding)
            assertEquals(2, completed.tabs.map { it.document.id }.distinct().size)
        }
    }

    @Test
    fun encodingReopenDoesNotDiscardEditsMadeWhileReading() = runBlocking {
        withPausedRead(large = false) { viewModel, source, _, access, _ ->
            withContext(Dispatchers.Main) { viewModel.setDocumentEncoding(TextEncoding.GB18030) }
            assertTrue(access.started.await(5, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) { viewModel.updateDraft("new edits while reading") }
            access.release.countDown()
            val completed = withTimeout(5_000) {
                viewModel.state.first { it.message == "文件内容已变化，未应用编码重读结果" }
            }

            assertEquals(source.toString(), completed.activeTabId)
            assertEquals("new edits while reading", completed.draftText)
            assertEquals(TextEncoding.UTF_8, completed.document?.encoding)
            assertTrue(completed.dirty)
        }
    }

    private suspend fun withPausedRead(
        large: Boolean,
        block: suspend (ReaderViewModel, Uri, Uri, PausedSafAccess, DocumentRepository) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("reader-read-race-", ".dir", context.cacheDir).apply {
            delete()
            mkdirs()
        }
        val sourceFile = File(root, "a.kt").apply {
            writeText(if (large) "// source A\n".repeat(150_000) else "val a = \"saved A\"\n")
        }
        val otherFile = File(root, "b.kt").apply { writeText("val b = \"saved B\"\n") }
        val source = Uri.fromFile(sourceFile)
        val other = Uri.fromFile(otherFile)
        val access = PausedSafAccess(source)
        val repository = DocumentRepository(context, safDocumentAccess = access)
        val viewModelStore = ViewModelStore()
        val viewModel = withContext(Dispatchers.Main) {
            ReaderViewModel(context.applicationContext as Application, repository).also { viewModelStore.put("reader", it) }
        }
        try {
            withContext(Dispatchers.Main) { viewModel.openUri(source) }
            withTimeout(5_000) { viewModel.state.first { it.document?.id == source.toString() } }
            withContext(Dispatchers.Main) { viewModel.openUri(other) }
            withTimeout(5_000) { viewModel.state.first { it.document?.id == other.toString() } }
            withContext(Dispatchers.Main) { viewModel.switchTab(source.toString()) }
            access.pauseNextRead.set(true)
            block(viewModel, source, other, access, repository)
        } finally {
            access.release.countDown()
            // 测试只放弃本次临时来源的草稿，删除排入后台事务后再移除独立的临时来源文件。 @author long
            withContext(Dispatchers.Main) {
                viewModel.closeTab(source.toString())
                viewModel.closeTab(other.toString())
                viewModelStore.clear()
            }
            root.deleteRecursively()
        }
    }

    private class PausedSafAccess(private val source: Uri) : SafDocumentAccess {
        val pauseNextRead = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun openInput(uri: Uri): InputStream {
            if (uri == source && pauseNextRead.compareAndSet(true, false)) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "测试未及时恢复文件读取" }
            }
            return File(requireNotNull(uri.path)).inputStream()
        }

        override fun openOutput(uri: Uri, mode: String): OutputStream? = null
    }
}
