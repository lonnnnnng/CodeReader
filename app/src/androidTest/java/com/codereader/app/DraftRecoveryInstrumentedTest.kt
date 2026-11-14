package com.codereader.app

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import com.codereader.app.data.DraftStore
import com.codereader.app.data.RecentProjectRecord
import com.codereader.app.ui.AppScreen
import com.codereader.app.ui.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 草稿恢复必须覆盖真实 ViewModel 重建，而不是只验证内存标签切换。 @author long */
@RunWith(AndroidJUnit4::class)
class DraftRecoveryInstrumentedTest {
    @Test
    fun clearingViewModelBeforeDebounceStillPersistsLatestDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("draft-clear-before-debounce-", ".kt", context.cacheDir).apply {
            writeText("val saved = true\n")
        }
        val viewModelStore = ViewModelStore()
        val restoredStore = ViewModelStore()
        val viewModel = withContext(Dispatchers.Main) {
            ReaderViewModel(context.applicationContext as Application).also { viewModelStore.put("reader", it) }
        }
        val store = DraftStore(DraftStore.defaultDirectory(context))
        val documentId = android.net.Uri.fromFile(source).toString()
        try {
            withContext(Dispatchers.Main) { viewModel.openUri(android.net.Uri.fromFile(source)) }
            withTimeout(5_000) { viewModel.state.first { it.document?.id == documentId } }
            withContext(Dispatchers.Main) {
                viewModel.updateDraft("val latest = true\n")
                // 在同一主线程调用中清理，确保尚未等到 500ms 自动保存窗口。 @author long
                viewModelStore.clear()
            }
            val restoredViewModel = withContext(Dispatchers.Main) {
                ReaderViewModel(context.applicationContext as Application).also { restoredStore.put("reader", it) }
            }
            withContext(Dispatchers.Main) { restoredViewModel.openUri(android.net.Uri.fromFile(source)) }
            val restored = withTimeout(5_000) {
                restoredViewModel.state.first { it.document?.id == documentId && it.dirty }
            }
            assertEquals("val latest = true\n", restored.draftText)
            withTimeout(5_000) {
                while (store.load(documentId)?.draftText != "val latest = true\n") delay(20)
            }
            assertEquals("val saved = true\n", source.readText())
        } finally {
            withContext(Dispatchers.Main) {
                viewModelStore.clear()
                restoredStore.clear()
            }
            store.delete(documentId)
            source.delete()
        }
    }

    @Test
    fun unsavedDraftReturnsAfterViewModelRecreationAndProjectReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DraftStore.defaultDirectory(context).deleteRecursively()
        context.getSharedPreferences("reader_preferences", Application.MODE_PRIVATE)
            .edit()
            .remove("reading_states")
            .commit()
        val root = File(context.filesDir, "projects/draft-recovery-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = File(root, "src/OrderService.kt").apply {
            parentFile?.mkdirs()
            writeText("fun status() = \"saved\"\n")
        }
        val draftText = "fun status() = \"draft\"\n// 尚未保存\n"
        val recent = RecentProjectRecord("local", root.name, root.absolutePath)
        val application = context.applicationContext as Application
        val firstViewModel = ReaderViewModel(application)

        firstViewModel.openRecentProject(recent)
        val browser = withTimeout(5_000) {
            firstViewModel.state.first { it.screen == AppScreen.BROWSER && it.projectEntries.isNotEmpty() }
        }
        firstViewModel.openEntry(requireNotNull(browser.projectEntries.firstOrNull { it.source.id == source.absolutePath }).source)
        withTimeout(5_000) {
            firstViewModel.state.first { it.document?.id == source.absolutePath }
        }
        firstViewModel.setEditable(true)
        firstViewModel.updateDraft(draftText)

        val store = DraftStore(DraftStore.defaultDirectory(context))
        withTimeout(5_000) {
            while (store.load(source.absolutePath)?.draftText != draftText) delay(50)
        }

        val restoredViewModel = ReaderViewModel(application)
        restoredViewModel.openRecentProject(recent)
        val restored = withTimeout(5_000) {
            restoredViewModel.state.first {
                it.screen == AppScreen.READER &&
                    it.document?.id == source.absolutePath &&
                    it.dirty
            }
        }

        assertEquals(draftText, restored.draftText)
        assertTrue(restored.editable)

        restoredViewModel.save()
        withTimeout(5_000) {
            restoredViewModel.state.first { it.document?.id == source.absolutePath && !it.dirty }
        }
        withTimeout(5_000) {
            while (store.load(source.absolutePath) != null) delay(50)
        }
        assertEquals(draftText, source.readText())
    }

    @Test
    fun externallyChangedFileRequiresExplicitChoiceBeforeDraftRecovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DraftStore.defaultDirectory(context).deleteRecursively()
        context.getSharedPreferences("reader_preferences", Application.MODE_PRIVATE)
            .edit()
            .remove("reading_states")
            .commit()
        val root = File(context.filesDir, "projects/draft-conflict-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val originalText = "server.port=8080\n"
        val externalText = "server.port=9090\n"
        val draftText = "server.port=8081\n"
        val source = File(root, "application.properties").apply { writeText(originalText) }
        val recent = RecentProjectRecord("local", root.name, root.absolutePath)
        val application = context.applicationContext as Application
        val firstViewModel = ReaderViewModel(application)

        firstViewModel.openRecentProject(recent)
        val browser = withTimeout(5_000) {
            firstViewModel.state.first { it.screen == AppScreen.BROWSER && it.projectEntries.isNotEmpty() }
        }
        firstViewModel.openEntry(requireNotNull(browser.projectEntries.firstOrNull { it.source.id == source.absolutePath }).source)
        withTimeout(5_000) { firstViewModel.state.first { it.document?.id == source.absolutePath } }
        firstViewModel.setEditable(true)
        firstViewModel.updateDraft(draftText)
        val store = DraftStore(DraftStore.defaultDirectory(context))
        withTimeout(5_000) {
            while (store.load(source.absolutePath)?.draftText != draftText) delay(50)
        }

        source.writeText(externalText)
        val restoredViewModel = ReaderViewModel(application)
        restoredViewModel.openRecentProject(recent)
        val conflicted = withTimeout(5_000) {
            restoredViewModel.state.first {
                it.screen == AppScreen.READER &&
                    it.document?.id == source.absolutePath &&
                    it.draftConflict?.documentId == source.absolutePath
            }
        }

        assertEquals(externalText, conflicted.draftText)
        assertTrue(!conflicted.dirty)

        restoredViewModel.restoreConflictingDraft()
        val recovered = restoredViewModel.state.value
        assertEquals(draftText, recovered.draftText)
        assertTrue(recovered.dirty)
        assertEquals(null, recovered.draftConflict)
    }
}
