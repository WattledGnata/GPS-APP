package com.blazepush.feature.test.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GrepGateTest {

    private val projectRoot = File(System.getProperty("user.dir")).let { dir ->
        // Gradle test working dir = module root (feature/test/)
        // Navigate up to project root
        var d = dir
        while (d != null && !File(d, "settings.gradle.kts").exists() && !File(d, "settings.gradle").exists()) {
            d = d.parentFile
        }
        d ?: dir
    }

    private fun findFile(relativePath: String): File {
        return File(projectRoot, relativePath)
    }

    private fun readFile(relativePath: String): String {
        return findFile(relativePath).readText()
    }

    private fun grepCount(file: File, pattern: String): Int {
        if (!file.exists()) return 0
        return Regex(pattern).findAll(file.readText()).count()
    }

    private fun grepCountInDir(dir: File, pattern: String, extension: String = ".kt"): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        return dir.walkTopDown()
            .filter { it.extension == "kt" }
            .sumOf { file -> Regex(pattern).findAll(file.readText()).count() }
    }

    private val prodDir: File
        get() = findFile("feature/test/src/main/java/com/blazepush/feature/test/ui/components")

    private val testDir: File
        get() = findFile("feature/test/src/test/java/com/blazepush/feature/test/ui/components")

    // §8.1: 4 component signature literals — exactly 1 hit each
    @Test
    fun `grep gate - 4 component signature lock`() {
        val signatures = mapOf(
            "SpeedTimeChart.kt" to """fun SpeedTimeChart\(""",
            "AccelTimeChart.kt" to """fun AccelTimeChart\(""",
            "SectorBar.kt" to """fun SectorBar\(""",
            "TrackPolylineMap.kt" to """fun TrackPolylineMap\(""" ,
        )
        signatures.forEach { (fileName, pattern) ->
            val file = findFile("feature/test/src/main/java/com/blazepush/feature/test/ui/components/$fileName")
            assertTrue("$fileName must exist", file.exists())
            val count = grepCount(file, pattern)
            assertEquals("$fileName: signature must appear exactly 1 time, found $count", 1, count)
        }
    }

    // §8.2: No internal MutableStateOf<Long?> for cursor
    @Test
    fun `grep gate - cursor state externalized (no internal MutableStateOf Long)`() {
        val pattern = """remember\s*\{\s*mutableStateOf<Long\?>"""
        prodDir.listFiles()?.filter { it.extension == "kt" }?.forEach { file ->
            val count = grepCount(file, pattern)
            assertEquals("${file.name}: must not contain cursor MutableStateOf", 0, count)
        }
    }

    // §8.3: SectorBar no onSectorClick; TrackPolylineMap no onCursorChange
    @Test
    fun `grep gate - SectorBar no onSectorClick and TrackPolylineMap no onCursorChange`() {
        val sectorBar = findFile("feature/test/src/main/java/com/blazepush/feature/test/ui/components/SectorBar.kt")
        assertEquals("SectorBar must not contain onSectorClick", 0, grepCount(sectorBar, "onSectorClick"))

        val trackMap = findFile("feature/test/src/main/java/com/blazepush/feature/test/ui/components/TrackPolylineMap.kt")
        assertEquals("TrackPolylineMap must not contain onCursorChange", 0, grepCount(trackMap, "onCursorChange"))
    }

    // §8.4: All bare Text( calls must have maxLines + Ellipsis (only new 4 components)
    // L1 R1 P0/C2 修订：从 300 字符 contextWindow 滑窗（跨多 Text 块 trivially-pass 风险，v3 #7+#8 翻版）
    // 改为 per-Text 块 paren balance 栈式匹配 — 锁定 Text( 起始 → 闭合 ) 区间内含 maxLines + Ellipsis。
    // Caveat: paren balance 不处理 string literal / comment 内的 () — 当前 4 组件源文件无此情况
    // （apply 期 grep 验证 4 文件无 Text 嵌套调用）。未来若引入嵌套 Text → 触发新 round 升级算法。
    private fun findTextCallBlocks(content: String): List<IntRange> {
        val textCalls = Regex("""(?<!\w)Text\s*\(""").findAll(content).toList()
        val blocks = mutableListOf<IntRange>()
        for (match in textCalls) {
            val start = match.range.first
            val openParenIdx = match.range.last  // '(' index
            var parenDepth = 1
            var idx = openParenIdx + 1
            while (idx < content.length && parenDepth > 0) {
                when (content[idx]) {
                    '(' -> parenDepth++
                    ')' -> parenDepth--
                }
                if (parenDepth == 0) break
                idx++
            }
            if (parenDepth == 0) blocks.add(start..idx)
        }
        return blocks
    }

    @Test
    fun `grep gate - all bare Text calls have maxLines and Ellipsis`() {
        val targetFiles = listOf("SpeedTimeChart.kt", "AccelTimeChart.kt", "SectorBar.kt", "TrackPolylineMap.kt")
        targetFiles.forEach { fileName ->
            val file = findFile("feature/test/src/main/java/com/blazepush/feature/test/ui/components/$fileName")
            if (!file.exists()) return@forEach
            val content = file.readText()
            val blocks = findTextCallBlocks(content)
            blocks.forEach { range ->
                val textBlock = content.substring(range.first, range.last + 1)
                assertTrue(
                    "$fileName: bare Text( at offset ${range.first} must have maxLines (per-Text paren-balanced block)",
                    textBlock.contains("maxLines")
                )
                assertTrue(
                    "$fileName: bare Text( at offset ${range.first} must have TextOverflow.Ellipsis (per-Text paren-balanced block)",
                    textBlock.contains("TextOverflow.Ellipsis")
                )
            }
        }
    }

    // §8.5: No autoSize library
    @Test
    fun `grep gate - no autoSize library`() {
        val pattern = """BasicText\.autoSize|AutoSizeText|autoSize\s*=\s*true"""
        prodDir.listFiles()?.filter { it.extension == "kt" }?.forEach { file ->
            val count = grepCount(file, pattern)
            assertEquals("${file.name}: must not use autoSize", 0, count)
        }
    }

    // §8.6: Production code 0 refs to MockTelemetry; test code ≥4 imports
    @Test
    fun `grep gate - MockTelemetry isolation`() {
        // (a) Production code: 0 hits
        val prodPattern = """import.*MockTelemetry|mockSingleLap\(|mockMultiLap\("""
        val prodHits = grepCountInDir(prodDir, prodPattern)
        assertEquals("Production code must not reference MockTelemetry", 0, prodHits)

        // (b) Test code: ≥4 imports (match MockTelemetry, mockSingleLap, mockMultiLap)
        val testPattern = """import.*MockTelemetry|import.*mockSingleLap|import.*mockMultiLap"""
        val testHits = grepCountInDir(testDir, testPattern)
        assertTrue("ContractTest files must import MockTelemetry ≥4 times, found $testHits", testHits >= 4)
    }

    // §8.7: LapTelemetrySample field signature lock
    @Test
    fun `grep gate - LapTelemetrySample field signature lock`() {
        val file = findFile("core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt")
        assertTrue("LapTelemetry.kt must exist", file.exists())
        val content = file.readText()

        val fields = listOf(
            "val absoluteTsMs: Long",
            "val elapsedMsInLap: Long",
            "val lat: Double",
            "val lon: Double",
            "val speedKmh: Double",
            "val bearingDeg: Double?",
            "val accelerationG: Double?",
            "val flags: Int",  // L1 R1 P0/B1 修订 — W1 commit 3c2f2d9 追加 flags 字段；命中 1 次锁
        )
        fields.forEach { field ->
            val count = Regex(Regex.escape(field)).findAll(content).count()
            assertEquals("Field '$field' must appear exactly 1 time, found $count", 1, count)
        }
    }

    // §8.8: Each component has at least 1 internal pure function
    @Test
    fun `grep gate - each component has internal pure functions`() {
        val expectedFunctions = mapOf(
            "SpeedTimeChart.kt" to listOf("internal fun computeChartCoordinates", "internal fun findNearestSampleIndex"),
            "AccelTimeChart.kt" to emptyList(), // reuses from SpeedTimeChart (same package)
            "SectorBar.kt" to listOf("internal fun computeSectorBounds"),
            "TrackPolylineMap.kt" to listOf("internal fun computeMapBoundingBox", "internal fun mapLatLonToCanvas"),
        )
        expectedFunctions.forEach { (fileName, functions) ->
            val file = findFile("feature/test/src/main/java/com/blazepush/feature/test/ui/components/$fileName")
            assertTrue("$fileName must exist", file.exists())
            val content = file.readText()
            functions.forEach { func ->
                assertTrue("$fileName must contain '$func'", content.contains(func))
            }
        }
    }

    // §8.9: ContractTest @Test count lower bounds
    @Test
    fun `grep gate - ContractTest case count lower bounds`() {
        val lowerBounds = mapOf(
            "SpeedTimeChartContractTest.kt" to 4,
            "AccelTimeChartContractTest.kt" to 6,
            "SectorBarContractTest.kt" to 7,
            "TrackPolylineMapContractTest.kt" to 4,
        )
        lowerBounds.forEach { (fileName, minCount) ->
            val file = findFile("feature/test/src/test/java/com/blazepush/feature/test/ui/components/$fileName")
            assertTrue("$fileName must exist", file.exists())
            val testCount = grepCount(file, "@Test")
            assertTrue("$fileName: must have ≥$minCount @Test, found $testCount", testCount >= minCount)
        }
    }

    // §8.10: Cross-file escape grep gate
    @Test
    fun `grep gate - cross-file escape prevention`() {
        // (a) Positive: 4 prod files + 4 test files must exist
        val prodFiles = listOf("SpeedTimeChart.kt", "AccelTimeChart.kt", "SectorBar.kt", "TrackPolylineMap.kt")
        val testFiles = listOf("SpeedTimeChartContractTest.kt", "AccelTimeChartContractTest.kt",
            "SectorBarContractTest.kt", "TrackPolylineMapContractTest.kt")

        prodFiles.forEach { assertTrue("Prod file $it must exist", findFile("feature/test/src/main/java/com/blazepush/feature/test/ui/components/$it").exists()) }
        testFiles.forEach { assertTrue("Test file $it must exist", findFile("feature/test/src/test/java/com/blazepush/feature/test/ui/components/$it").exists()) }

        // (b) Negative: SpeedTimeChart literal outside components dir = 0 hits (exclude .worktrees/ and src/test)
        val searchRoot = findFile("feature/test/src/main/java")
        if (searchRoot.exists()) {
            val outsideHits = searchRoot.walkTopDown()
                .filter { it.extension == "kt" }
                .filter { !it.path.contains("/ui/components/") }
                .sumOf { file -> Regex("""SpeedTimeChart""").findAll(file.readText()).count() }
            assertEquals("SpeedTimeChart literal must not escape components dir", 0, outsideHits)
        }
    }
}
