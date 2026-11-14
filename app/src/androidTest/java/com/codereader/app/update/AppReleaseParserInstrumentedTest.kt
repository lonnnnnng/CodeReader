package com.codereader.app.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** @author long */
@RunWith(AndroidJUnit4::class)
class AppReleaseParserInstrumentedTest {
    @Test
    fun latestRelease只接受约定Apk和GitHub摘要() {
        val release = AppReleaseParser.parse(validReleaseJson())

        assertEquals("v1.0.1", release.tagName)
        assertEquals("1.0.1", release.versionName)
        assertEquals("CodeReader-v1.0.1-arm64-v8a.apk", release.apk.name)
        assertEquals("a".repeat(64), release.apk.sha256)
        assertEquals(11_038_007L, release.apk.sizeBytes)
    }

    @Test
    fun 非GitHub下载地址和缺失摘要会被拒绝() {
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseParser.parse(validReleaseJson().replace("https://github.com/", "https://example.com/"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseParser.parse(validReleaseJson().replace("sha256:${"a".repeat(64)}", ""))
        }
    }

    @Test
    fun 仅接受唯一的CodeReaderArm64发行安装包() {
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseParser.parse(validReleaseJson().replace("-arm64-v8a.apk", ".apk"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseParser.parse(validReleaseJson().replace("-arm64-v8a.apk", "-armeabi-v7a.apk"))
        }
        val duplicate = JSONObject(validReleaseJson()).apply {
            val assets = getJSONArray("assets")
            assets.put(JSONObject(assets.getJSONObject(0).toString()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppReleaseParser.parse(duplicate.toString())
        }
    }

    private fun validReleaseJson(): String =
        """
        {
          "tag_name": "v1.0.1",
          "name": "CodeReader v1.0.1",
          "body": "增加在线更新",
          "html_url": "https://github.com/lonnnnnng/CodeReader/releases/tag/v1.0.1",
          "assets": [
            {
              "name": "CodeReader-v1.0.1-arm64-v8a.apk",
              "state": "uploaded",
              "content_type": "application/vnd.android.package-archive",
              "size": 11038007,
              "digest": "sha256:${"a".repeat(64)}",
              "browser_download_url": "https://github.com/lonnnnnng/CodeReader/releases/download/v1.0.1/CodeReader-v1.0.1-arm64-v8a.apk"
            }
          ]
        }
        """.trimIndent()
}
