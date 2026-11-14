package com.codereader.app

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.codereader.app.data.RecentProjectRecord
import com.codereader.app.model.ReaderTheme
import com.codereader.app.ui.ReaderViewModel
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import io.github.rosemoe.sora.widget.CodeEditor

/** README 使用当前包在指定真机上的真实界面，示例项目只包含本次生成的公开演示内容。 @author long */
class ReadmeScreenshotsInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun captureHomeCodeAndMarkdownOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var viewModel: ReaderViewModel
        composeRule.activityRule.scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ReaderViewModel::class.java]
            viewModel.setTheme(ReaderTheme.HIGH_CONTRAST_LIGHT)
        }
        composeRule.onNodeWithText("CodeReader").assertIsDisplayed()
        composeRule.waitForIdle()
        capture("codereader-home.png")

        val root = File(context.filesDir, "projects/readme-showcase").apply { mkdirs() }
        File(root, "UserRepository.kt").writeText(
            """
            package demo

            data class User(val id: Long, val name: String)

            /**
             * 按稳定 ID 缓存资料，查询复用结果。
             * @author long
             */
            class UserRepository {
                private val users = mutableMapOf<Long, User>()

                fun findUser(id: Long): User? = users[id]

                fun remember(user: User) {
                    // 同一 ID 只保留最新资料，
                    // 查询不会混入过期昵称。 @author long
                    users[user.id] = user
                }
            }

            fun main() {
                val repository = UserRepository()
                repository.remember(User(1, "CodeReader"))
                println(repository.findUser(1))
            }
            """.trimIndent(),
        )
        File(root, "README.md").writeText(
            """
            # CodeReader

            在手机上读代码、看文档、继续上次阅读。

            ## 随手打开项目

            - 浏览项目目录与文件标签
            - 搜索内容并定位到行列
            - 使用书签继续上次阅读

            ## 离线 Markdown

            | 内容 | 支持 |
            | --- | --- |
            | 代码与表格 | 语法高亮 |
            | 数学公式 | KaTeX |
            | 流程图 | Mermaid |

            ```kotlin
            val reader = "CodeReader"
            println("Open. Read. Continue.")
            ```

            > 文档与源码都在本地阅读。
            """.trimIndent(),
        )
        composeRule.runOnIdle {
            viewModel.openRecentProject(RecentProjectRecord("local", "CodeReader 项目", root.absolutePath))
        }
        // 项目再次打开可能直接恢复上次文档，截图入口应等待索引，而不是要求停留在浏览页。 @author long
        composeRule.waitUntil(10_000) {
            viewModel.state.value.projectRoot?.stableId == root.absolutePath &&
                viewModel.state.value.projectEntries.isNotEmpty() && viewModel.state.value.operation == null
        }
        composeRule.runOnIdle {
            viewModel.state.value.tabs.map { it.document.id }.forEach(viewModel::closeTab)
            viewModel.setTheme(ReaderTheme.DARCULA)
            viewModel.openEntry(viewModel.state.value.projectEntries.first { it.source.name == "UserRepository.kt" }.source)
        }
        composeRule.waitUntil(10_000) { viewModel.state.value.document?.name == "UserRepository.kt" && viewModel.state.value.operation == null }
        composeRule.onNodeWithTag("reader-header").assertIsDisplayed()
        var highlightDetail = "未找到编辑器"
        try {
            composeRule.waitUntil(10_000) {
                var highlighted = false
                composeRule.runOnIdle {
                    val editor = findCodeEditor(composeRule.activity.findViewById(android.R.id.content))
                    val spans = editor?.styles?.spans
                    highlightDetail = "language=${editor?.editorLanguage?.javaClass?.name}, analyzer=${editor?.editorLanguage?.analyzeManager?.javaClass?.name}, lines=${spans?.lineCount}, text=${editor?.text?.toString()?.take(40)}"
                    if (spans != null) {
                        val reader = spans.read()
                        // Sora 的语法结果在后台发布，等真实 token 出现后截图，不能只等待 Compose 空闲。 @author long
                        highlighted = (0 until minOf(spans.lineCount, 8)).any { line ->
                            reader.getSpansOnLine(line).map { it.foregroundColorId }.distinct().size > 1
                        }
                        highlightDetail += (0 until minOf(spans.lineCount, 4)).joinToString { line ->
                            "line$line=" + reader.getSpansOnLine(line).joinToString { span ->
                                "${span.column}/${span.foregroundColorId}/${editor.colorScheme.getColor(span.foregroundColorId).toUInt().toString(16)}"
                            }
                        }
                    }
                }
                highlighted
            }
        } catch (error: Throwable) {
            capture("codereader-code-diagnostic.png")
            throw AssertionError("原生高亮未就绪：$highlightDetail", error)
        }
        composeRule.waitForIdle()
        capture("codereader-code.png")

        composeRule.runOnIdle {
            viewModel.setTheme(ReaderTheme.HIGH_CONTRAST_LIGHT)
            viewModel.openEntry(viewModel.state.value.projectEntries.first { it.source.name == "README.md" }.source)
        }
        composeRule.waitUntil(10_000) { viewModel.state.value.document?.name == "README.md" && viewModel.state.value.markdownPreview }
        composeRule.waitUntil(10_000) {
            var loaded = false
            composeRule.runOnIdle {
                loaded = findWebView(composeRule.activity.findViewById(android.R.id.content))?.progress == 100
            }
            loaded
        }
        composeRule.waitForIdle()
        capture("codereader-markdown.png")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir("readme-screenshots"), name)
        output.parentFile?.mkdirs()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "无法获取真机界面" }
        try {
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        repeat(view.childCount) { index -> findWebView(view.getChildAt(index))?.let { return it } }
        return null
    }

    private fun findCodeEditor(view: View): CodeEditor? {
        if (view is CodeEditor) return view
        if (view !is ViewGroup) return null
        repeat(view.childCount) { index -> findCodeEditor(view.getChildAt(index))?.let { return it } }
        return null
    }
}
