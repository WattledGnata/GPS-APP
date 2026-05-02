package com.blazepush

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯文本 grep 风格 contract test —— 锁定 enforce-portrait-orientation round 在
 * `AndroidManifest.xml` 的 MainActivity 节强制 portrait 配置。
 *
 * 不依赖 Robolectric / Compose runtime / Android Context。仅读 manifest 文本断言。
 *
 * @author CC
 * @description MainActivity portrait orientation manifest contract test
 * @date 2026-05-02
 */
class MainActivityOrientationContractTest {

    @Test
    fun `manifest should contain MainActivity name and portrait orientation`() {
        val source = readSource(MANIFEST_PATH)
        assertTrue(
            "AndroidManifest.xml MUST contain `android:name=\".MainActivity\"`",
            source.contains("android:name=\".MainActivity\""),
        )
        assertTrue(
            "AndroidManifest.xml MUST contain `android:screenOrientation=\"portrait\"`",
            source.contains("android:screenOrientation=\"portrait\""),
        )
    }

    @Test
    fun `MainActivity activity block should declare portrait orientation`() {
        val source = readSource(MANIFEST_PATH)
        // 抓 MainActivity activity 块（从 `android:name=".MainActivity"` 行所在 <activity> 开标签到下一个 `>`）
        val mainActivityBlock = extractMainActivityBlock(source)
        assertTrue(
            "MainActivity activity block MUST contain portrait orientation；" +
                "命中区间：\n$mainActivityBlock",
            mainActivityBlock.contains("android:screenOrientation=\"portrait\""),
        )
    }

    @Test
    fun `manifest should not regress to landscape or sensor orientation on MainActivity`() {
        val source = readSource(MANIFEST_PATH)
        val mainActivityBlock = extractMainActivityBlock(source)
        // 防回退：MainActivity 不应被改为非 portrait 值
        listOf(
            "android:screenOrientation=\"landscape\"",
            "android:screenOrientation=\"sensor\"",
            "android:screenOrientation=\"sensorPortrait\"",
            "android:screenOrientation=\"unspecified\"",
            "android:screenOrientation=\"fullSensor\"",
        ).forEach { forbidden ->
            assertTrue(
                "MainActivity MUST NOT regress to `$forbidden`",
                !mainActivityBlock.contains(forbidden),
            )
        }
    }

    private fun readSource(path: String): String {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("source file not found via any candidate path: $path (tried ${candidates.map { it.absolutePath }})")
        return file.readText()
    }

    /**
     * 抓 `<activity ...>` 块包含 `android:name=".MainActivity"` 的整段（直到匹配的 `>` 关闭）。
     */
    private fun extractMainActivityBlock(source: String): String {
        // 找到含 ".MainActivity" 的 activity 起始位置
        val mainActivityIdx = source.indexOf("android:name=\".MainActivity\"")
        if (mainActivityIdx < 0) {
            error("MainActivity declaration not found in manifest")
        }
        // 向前找最近的 `<activity` 开标签
        val activityStart = source.lastIndexOf("<activity", mainActivityIdx)
        if (activityStart < 0) {
            error("<activity> open tag not found before MainActivity name attribute")
        }
        // 向后找该开标签对应的 `>`（自闭合或开标签结束）
        val tagEnd = source.indexOf(">", mainActivityIdx)
        if (tagEnd < 0) {
            error("> not found after MainActivity name attribute")
        }
        return source.substring(activityStart, tagEnd + 1)
    }

    companion object {
        private const val MANIFEST_PATH = "app/src/main/AndroidManifest.xml"
    }
}
