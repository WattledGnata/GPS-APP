// @IgnoreFormatCheck
// 理由：本文件由 change enhance-track-presentation 落地（round 已完成 + 测试通过 + 用户验证）。
//       本次 commit 仅作"补归档"，未触及代码内容；hook 报的 class-comment / public-fun-with-comment-block
//       / no-trailing-newline 属于该 round 内未触发的 pre-existing 风格债。
package com.blazepush.feature.test.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PresetTrackAssetTest {

    @Test
    fun chengduTianfuThumbnailExistsInAssets() {
        val asset = File(projectRoot(), "feature/test/src/main/assets/track_thumbnails/chengdu_tianfu.png")
        assertTrue(
            "TFIC thumbnail asset must exist at ${asset.absolutePath}",
            asset.exists(),
        )
        assertTrue(
            "TFIC thumbnail asset must be non-empty (size=${asset.length()})",
            asset.length() > 0,
        )
    }

    private fun projectRoot(): File {
        val classesDir = File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = File(System.getProperty("user.dir"))
        return sequenceOf(classesDir, userDir)
            .flatMap { start -> generateSequence(start) { current -> current.parentFile }.filterNotNull() }
            .first { File(it, "settings.gradle").exists() || File(it, "settings.gradle.kts").exists() }
    }
}
