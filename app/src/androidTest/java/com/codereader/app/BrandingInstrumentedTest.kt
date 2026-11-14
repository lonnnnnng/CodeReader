package com.codereader.app

import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 发布身份与真实 APK 资源共同校验，避免改名后留下旧包名、失效 Provider 或缺失桌面图标。 @author long */
@RunWith(AndroidJUnit4::class)
class BrandingInstrumentedTest {
    @Test
    fun applicationNamePackageAndProvidersUseCodeReaderIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.codereader.app.debug", context.packageName)
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertEquals("CodeReader", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals(
            "com.codereader.app.share.ReaderFileProvider",
            context.packageManager.resolveContentProvider("${context.packageName}.files", 0)?.name,
        )
        assertEquals(
            "com.codereader.app.update.UpdateFileProvider",
            context.packageManager.resolveContentProvider("${context.packageName}.updates", 0)?.name,
        )
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun squareRoundAndThemedLauncherResourcesAreAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.getDrawable(R.mipmap.ic_launcher)
        val round = context.getDrawable(R.mipmap.ic_launcher_round)
        assertTrue(icon is AdaptiveIconDrawable)
        assertTrue(round is AdaptiveIconDrawable)
        assertNotNull((icon as AdaptiveIconDrawable).foreground)
        assertNotNull((round as AdaptiveIconDrawable).background)
        if (Build.VERSION.SDK_INT >= 33) {
            assertNotNull(icon.monochrome)
            assertNotNull(round.monochrome)
        }
    }
}
