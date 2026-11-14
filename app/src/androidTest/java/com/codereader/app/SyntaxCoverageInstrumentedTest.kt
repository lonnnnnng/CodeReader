package com.codereader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import com.codereader.app.qa.SampleCatalog
import com.codereader.app.qa.SyntaxCoverageVerifier
import org.eclipse.tm4e.core.internal.oniguruma.Oniguruma
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** @author long */
@RunWith(AndroidJUnit4::class)
class SyntaxCoverageInstrumentedTest {
    @Test
    fun allDeclaredSamplesLoadGrammarAndProduceSemanticTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val shardCount = arguments.getString("syntaxShardCount")?.toIntOrNull() ?: 1
        val shardIndex = arguments.getString("syntaxShardIndex")?.toIntOrNull() ?: 0
        require(shardCount in 1..SampleCatalog.all.size && shardIndex in 0 until shardCount) { "语法分片参数无效" }
        // 每个分片由独立 instrumentation 进程执行，释放上一组 grammar 缓存后再加载下一组，适配小堆真机。 @author long
        val cases = SampleCatalog.all.filterIndexed { index, _ -> index % shardCount == shardIndex }
        val result = SyntaxCoverageVerifier.verify(context, cases)
        InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
            putInt("syntaxPassed", result.passed)
            putInt("syntaxTotal", result.total)
            putInt("syntaxShardIndex", shardIndex)
        })
        assertTrue(result.failures.joinToString(separator = "\n"), result.isSuccess)
        assertTrue("Android 语法扫描必须使用 Joni，避免 native Oniguruma 崩溃", !Oniguruma().isUseNativeOniguruma)
    }
}
