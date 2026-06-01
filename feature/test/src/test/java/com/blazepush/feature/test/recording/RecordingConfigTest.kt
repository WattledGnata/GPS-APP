// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * recording-params-config-screen round · spec 清晰度降级 + 曝光 clamp + 持久化默认值。
 * 纯函数（CameraX-free），不依赖设备。
 */
class RecordingConfigTest {

    @Test
    fun default_hasExpectedValues() {
        val d = RecordingConfig.DEFAULT
        assertEquals(RecordingResolution.FHD_1080P, d.resolution)
        assertEquals(30, d.targetFps)
        assertEquals(true, d.audioEnabled)
        assertEquals(CameraFacing.BACK, d.cameraFacing)
        assertEquals(FocusMode.CONTINUOUS_AUTO, d.focusMode)
        assertEquals(0, d.exposureCompensationEv)
    }

    // ---- resolveEffectiveResolution（spec 4K 降级三 scenario）----

    @Test
    fun resolve_uhdSupported_returnsUhd() {
        assertEquals(
            RecordingResolution.UHD_4K,
            resolveEffectiveResolution(
                RecordingResolution.UHD_4K,
                setOf(RecordingResolution.UHD_4K, RecordingResolution.FHD_1080P, RecordingResolution.HD_720P),
            ),
        )
    }

    @Test
    fun resolve_uhdUnsupported_downgradesToFhd() {
        assertEquals(
            RecordingResolution.FHD_1080P,
            resolveEffectiveResolution(
                RecordingResolution.UHD_4K,
                setOf(RecordingResolution.FHD_1080P, RecordingResolution.HD_720P),
            ),
        )
    }

    @Test
    fun resolve_onlyHdSupported_returnsHd() {
        assertEquals(
            RecordingResolution.HD_720P,
            resolveEffectiveResolution(RecordingResolution.UHD_4K, setOf(RecordingResolution.HD_720P)),
        )
    }

    @Test
    fun resolve_emptySupported_fallbackToFhd() {
        assertEquals(
            RecordingResolution.FHD_1080P,
            resolveEffectiveResolution(RecordingResolution.UHD_4K, emptySet()),
        )
    }

    @Test
    fun resolve_fhdRequested_whenSupported_returnsFhd() {
        assertEquals(
            RecordingResolution.FHD_1080P,
            resolveEffectiveResolution(
                RecordingResolution.FHD_1080P,
                setOf(RecordingResolution.FHD_1080P, RecordingResolution.HD_720P),
            ),
        )
    }

    // ---- clampEv（spec 曝光反例）----

    @Test
    fun clamp_within_unchanged() {
        assertEquals(1, clampEv(1, -4..4))
    }

    @Test
    fun clamp_overUpper_clampsToUpper() {
        assertEquals(2, clampEv(5, -2..2))
    }

    @Test
    fun clamp_underLower_clampsToLower() {
        assertEquals(-2, clampEv(-5, -2..2))
    }

    @Test
    fun clamp_zeroRange_returnsZero() {
        assertEquals(0, clampEv(3, 0..0))
    }
}
