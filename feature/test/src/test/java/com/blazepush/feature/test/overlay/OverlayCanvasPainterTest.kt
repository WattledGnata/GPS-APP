// @IgnoreFormatCheck
package com.blazepush.feature.test.overlay

import com.blazepush.feature.test.usecase.GaugeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [OverlayCanvasPainter] 共享绘制层单测（round video-export-burned-overlay · Round A）。
 *
 * ## 为何只测纯几何 + 契约 grep（不像素断言）
 *
 * 本工程 JVM 单测**无 Robolectric**（见 feature/test/build.gradle.kts test deps），
 * `android.graphics.Bitmap`/`Canvas`/`Paint` 在 JVM 下是 stub（方法抛 `RuntimeException("Stub!")`），
 * 无法真正画到 Bitmap 断言像素。tasks 1.6 done condition 已预判 → 降级断言纯几何。
 *
 * 故本测试分两层：
 * 1. **纯几何/格式化断言**：共享层 internal 最后一跳几何（needleTip / tickEndpoints / ballDotCenter /
 *    gMagnitude / withAlpha）与格式化（formatElapsed / formatDelta）—— 这些不触碰 android.graphics，
 *    JVM 可验证，且与回放端原 DrawScope/Text 逻辑 1:1（防重构漂移）。
 * 2. **契约 grep**：共享层 MUST 复用 GaugeMath/TrackMiniMapProjection（不重写数学）、MUST 无 Compose
 *    依赖（便于导出端 Bitmap 复用）、回放端三组件 MUST 已经 drawIntoCanvas{nativeCanvas} 接线
 *    （视觉零漂移防回归）。
 */
class OverlayCanvasPainterTest {

    private val eps = 1e-4f

    // ── 1. needleTip：指针端点 = 中心 + 沿角度 len 像素 ──────────────────────────

    @Test
    fun `Scenario - needleTip 正向沿角度延伸 len 像素`() {
        // 0° = 3 点钟（+x 方向）
        val (x, y) = OverlayCanvasPainter.needleTip(100f, 50f, 0.0, 30f)
        assertEquals(130f, x, eps)
        assertEquals(50f, y, eps)
    }

    @Test
    fun `Scenario - needleTip 负 len 即反向（尾翼）`() {
        // 0° 反向 → -x
        val (x, y) = OverlayCanvasPainter.needleTip(100f, 50f, 0.0, -20f)
        assertEquals(80f, x, eps)
        assertEquals(50f, y, eps)
    }

    @Test
    fun `Scenario - needleTip 90度沿 y 正向（Compose 角度系顺时针）`() {
        val (x, y) = OverlayCanvasPainter.needleTip(100f, 50f, 90.0, 40f)
        assertEquals(100f, x, eps)
        assertEquals(90f, y, eps)
    }

    // ── 2. tickEndpoints：刻度内外端点 ───────────────────────────────────────

    @Test
    fun `Scenario - tickEndpoints 外端在 outerR 内端在 outerR 减 len`() {
        val (inner, outer) = OverlayCanvasPainter.tickEndpoints(0f, 0f, 0.0, 100f, 16f)
        // 0° → +x
        assertEquals(100f, outer.first, eps)
        assertEquals(0f, outer.second, eps)
        assertEquals(84f, inner.first, eps)
        assertEquals(0f, inner.second, eps)
    }

    // ── 3. ballDotCenter：动点像素 = 中心 + 归一化偏移 × 半径（复用 GaugeMath） ──────

    @Test
    fun `Scenario - ballDotCenter 零 G 落圆心`() {
        val (x, y) = OverlayCanvasPainter.ballDotCenter(60f, 60f, 50f, 0.0, 0.0)
        assertEquals(60f, x, eps)
        assertEquals(60f, y, eps)
    }

    @Test
    fun `Scenario - ballDotCenter 复用 GaugeMath gForceToBallOffset 结果一致`() {
        val cx = 60f; val cy = 60f; val r = 50f
        val latG = 0.8; val lonG = -0.5
        val (nx, ny) = GaugeMath.gForceToBallOffset(latG, lonG)
        val expectedX = cx + nx.toFloat() * r
        val expectedY = cy + ny.toFloat() * r
        val (x, y) = OverlayCanvasPainter.ballDotCenter(cx, cy, r, latG, lonG)
        assertEquals(expectedX, x, eps)
        assertEquals(expectedY, y, eps)
    }

    @Test
    fun `反例 - ballDotCenter 合成 G 超量程时 clamp 在单位圆边界（不越出半径）`() {
        val cx = 60f; val cy = 60f; val r = 50f
        // 5G 远超 1.5G 量程 → clamp 到单位圆
        val (x, y) = OverlayCanvasPainter.ballDotCenter(cx, cy, r, 5.0, 5.0)
        val dist = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble())
        assertEquals(r.toDouble(), dist, 1e-3)
    }

    // ── 4. 速度表指针角度复用 GaugeMath（不重写映射） ────────────────────────────

    @Test
    fun `Scenario - 速度表指针端点角度由 GaugeMath speedToNeedleAngle 决定`() {
        val cx = 60f; val cy = 60f; val len = 40f
        val maxKmh = 200.0
        val speed = 100.0
        val angle = GaugeMath.speedToNeedleAngle(speed, maxKmh = maxKmh)
        val rad = Math.toRadians(angle)
        val expectedX = cx + cos(rad).toFloat() * len
        val expectedY = cy + sin(rad).toFloat() * len
        val (x, y) = OverlayCanvasPainter.needleTip(cx, cy, angle, len)
        assertEquals(expectedX, x, eps)
        assertEquals(expectedY, y, eps)
    }

    // ── 5. gMagnitude / withAlpha ──────────────────────────────────────────

    @Test
    fun `Scenario - gMagnitude 等于平方和开方`() {
        assertEquals(sqrt(0.3 * 0.3 + 0.4 * 0.4), OverlayCanvasPainter.gMagnitude(0.3, 0.4), 1e-9)
    }

    @Test
    fun `Scenario - withAlpha 保留 RGB 只缩放 alpha`() {
        val argb = 0xFF67E8F9.toInt() // Cyan 不透明
        val faded = OverlayCanvasPainter.withAlpha(argb, 0.3f)
        // alpha = round(255 * 0.3) = 77 = 0x4D
        assertEquals(0x4D, faded ushr 24)
        // RGB 不变
        assertEquals(argb and 0x00FFFFFF, faded and 0x00FFFFFF)
    }

    // ── 6. 圈速 / delta 格式化（与 LapVideoPlaybackScreen 原函数 1:1，含 null 降级反例） ──

    @Test
    fun `Scenario - formatElapsed 正常分秒毫秒`() {
        assertEquals("1:32.457", OverlayCanvasPainter.formatElapsed(92457L))
    }

    @Test
    fun `反例 - formatElapsed null 降级`() {
        assertEquals("--:--.---", OverlayCanvasPainter.formatElapsed(null))
    }

    @Test
    fun `反例 - formatElapsed 负值降级`() {
        assertEquals("--:--.---", OverlayCanvasPainter.formatElapsed(-1L))
    }

    @Test
    fun `Scenario - formatDelta 快为负号`() {
        assertEquals("-0.50", OverlayCanvasPainter.formatDelta(-500L))
    }

    @Test
    fun `Scenario - formatDelta 慢为正号`() {
        assertEquals("+1.23", OverlayCanvasPainter.formatDelta(1230L))
    }

    @Test
    fun `反例 - formatDelta null 降级`() {
        assertEquals("--", OverlayCanvasPainter.formatDelta(null))
    }

    // ── 7. 契约 grep：共享层复用纯函数 + 无 Compose 依赖 + 回放端已接线 ────────────

    @Test
    fun `Contract - OverlayCanvasPainter 复用 GaugeMath 与 TrackMiniMapProjection（不重写数学）`() {
        val src = readSource(PAINTER_PATH)
        assertTrue(
            "OverlayCanvasPainter MUST 复用 GaugeMath.speedToNeedleAngle（不重写速度→角度映射）",
            src.contains("GaugeMath.speedToNeedleAngle"),
        )
        assertTrue(
            "OverlayCanvasPainter MUST 复用 GaugeMath.gForceToBallOffset（不重写 G→偏移映射）",
            src.contains("GaugeMath.gForceToBallOffset"),
        )
        assertTrue(
            "OverlayCanvasPainter MUST 复用 TrackMiniMapProjection.project（不重写赛道投影）",
            src.contains("TrackMiniMapProjection.project"),
        )
    }

    @Test
    fun `Contract - OverlayCanvasPainter MUST NOT 依赖 Compose（便于导出端 Bitmap 复用）`() {
        val src = readSource(PAINTER_PATH)
        FORBIDDEN_COMPOSE_IMPORTS.forEach { forbidden ->
            assertFalse(
                "OverlayCanvasPainter MUST NOT import `$forbidden`（共享层须纯 android.graphics，导出端无 Compose）",
                src.contains(forbidden),
            )
        }
        assertTrue(
            "OverlayCanvasPainter MUST 吃 android.graphics.Canvas",
            src.contains("import android.graphics.Canvas"),
        )
    }

    @Test
    fun `Contract - 回放端三组件已 drawIntoCanvas nativeCanvas 接共享层（视觉零漂移防回归）`() {
        listOf(
            SPEEDOMETER_PATH to "OverlayCanvasPainter.drawSpeedometer",
            GFORCE_PATH to "OverlayCanvasPainter.drawGForceBall",
            MINIMAP_PATH to "OverlayCanvasPainter.drawTrackMiniMap",
        ).forEach { (path, call) ->
            val src = readSource(path)
            assertTrue("$path MUST 经 drawIntoCanvas 调共享层", src.contains("drawIntoCanvas"))
            assertTrue("$path MUST 用 nativeCanvas（android.graphics.Canvas）", src.contains("nativeCanvas"))
            assertTrue("$path MUST 调 $call", src.contains(call))
        }
    }

    @Test
    fun `Contract - 回放端不再保留旧的内联 DrawScope 图元（已彻底下沉）`() {
        // 三组件改薄壳后，原内联的 drawCircle/drawLine/drawPath/drawArc 应已移出 Composable
        // （仅 drawIntoCanvas 一处）；以"内联图元关键字数 == 0"锁死下沉彻底（防半重构）。
        listOf(SPEEDOMETER_PATH, GFORCE_PATH).forEach { path ->
            val src = readSource(path)
            // DrawScope 风格调用形如 `drawCircle(color =` / `drawLine(` 已不应出现在薄壳里
            assertFalse(
                "$path 不应残留内联 DrawScope drawCircle（已下沉到共享层）",
                src.contains("drawCircle(color ="),
            )
        }
    }

    private fun readSource(rel: String): String {
        val f = File(projectRoot(), rel)
        assertTrue("source not found: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    companion object {
        private const val PAINTER_PATH =
            "src/main/java/com/blazepush/feature/test/overlay/OverlayCanvasPainter.kt"
        private const val SPEEDOMETER_PATH =
            "src/main/java/com/blazepush/feature/test/ui/tracktech/SpeedometerGauge.kt"
        private const val GFORCE_PATH =
            "src/main/java/com/blazepush/feature/test/ui/tracktech/GForceBall.kt"
        private const val MINIMAP_PATH =
            "src/main/java/com/blazepush/feature/test/ui/tracktech/TrackMiniMap.kt"

        private val FORBIDDEN_COMPOSE_IMPORTS = listOf(
            "import androidx.compose.ui.graphics.drawscope",
            "import androidx.compose.foundation.Canvas",
            "import androidx.compose.runtime",
            "import androidx.compose.ui.geometry.Offset",
        )

        /**
         * gradle test working dir = 模块根（feature/test/）。返回该模块根目录。
         * mirror 既有 contract test 的 projectRoot() helper（处理 worktree 副本 / 相对路径解析）。
         */
        private fun projectRoot(): File {
            var dir: File? = File(".").absoluteFile
            // 已在 feature/test 模块根时 src/ 直接可见
            while (dir != null) {
                if (File(dir, "src/main/java/com/blazepush/feature/test").exists()) return dir
                dir = dir.parentFile
            }
            return File(".").absoluteFile
        }
    }
}
