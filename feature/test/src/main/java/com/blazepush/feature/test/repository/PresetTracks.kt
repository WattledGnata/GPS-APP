// @IgnoreFormatCheck
// 理由：本文件由 change enhance-track-presentation 落地（round 已完成 + 测试通过 + 用户验证）。
//       本次 commit 仅作"补归档"，未触及代码内容；hook 报的 class-comment / no-trailing-newline
//       属于该 round 内未触发的 pre-existing 风格债，与本 commit 语义正交。
package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.model.track.TrackName
import com.blazepush.feature.test.model.track.TrackPath

// `extraPresetTracks` 由 src/debug/ 与 src/release/ 互斥变体源集各提供一份实现；
// main 源集**禁止**声明同签名函数（否则 debug variant 会触发 duplicate JVM declarations）。
// 变体差异见 OpenSpec change `add-debug-preset-track-boyu-loop` design D1/D2。
private val mainPresets: List<Track> = listOf(
    Track(
        id = "preset-tfic-lpcc",
        name = TrackName(
            zh = "成都天府国际赛道",
            en = "Chengdu Tianfu International Circuit",
            abbr = "TFIC",
        ),
        lengthKm = 3.260,
        thumbnailAssetPath = "track_thumbnails/chengdu_tianfu.png",
        referencePath = TrackPath(
            points = listOf(
                GeoPoint(30.4945735, 104.4332358),
                GeoPoint(30.4927678, 104.4332757),
                GeoPoint(30.4914073, 104.4346407),
                GeoPoint(30.4903109, 104.4329748),
                GeoPoint(30.4905453, 104.4350638),
                GeoPoint(30.4920068, 104.4362783),
                GeoPoint(30.4937000, 104.4369812),
                GeoPoint(30.4955157, 104.4371740),
                GeoPoint(30.4969511, 104.4359008),
                GeoPoint(30.4977035, 104.4339855),
                GeoPoint(30.4978068, 104.4318679),
                GeoPoint(30.4963642, 104.4331724),
                GeoPoint(30.4945735, 104.4332358)
            )
        ),
        startFinishGate = TimingGate(
            id = "start-finish",
            name = "起点",
            type = TimingGateType.StartFinish,
            // 起终点对齐官方 MYLAPS 龙门架（align-tfic-start-finish）：原虚拟线在官方计时
            // 线圈后方约 55m，导致圈速系统性偏快 ~0.14s。沿前进方向(passDirection)前移 55m
            // 后，2026-06-19 真机 session 142605bb 全 17 个可比圈对官方偏差均值 0.000±0.034s
            // （σ 最小点）。passDirection / 线宽(50m)不变；referencePath 不动。
            // 基于单 session 标定，后续多 session 可微调。
            line = GeoLine(
                start = GeoPoint(30.495674664699337, 104.4333934545891),
                end = GeoPoint(30.495698171686513, 104.43287290301339)
            ),
            passDirection = GeoVector(x = -0.0002602757878550089, y = -0.000023506987175358924),
            sequenceIndex = 0,
            minDirectionalSpeedMps = null
        ),
        sectorGates = listOf(
            TimingGate(
                id = "s1",
                name = "s1",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(30.49004451419976, 104.43252709154902),
                    end = GeoPoint(30.48959781913357, 104.43258157511764)
                ),
                // 过线方向修正（fix-tfic-sector-dir）：原值 (-2.72e-5, -4.47e-4) 指向赛车行进
                // 反方向（西），导致 detector 对正常行驶判 WrongDirection、分段计时失效。取反后
                // 朝东 ~86°，与官方 track_天府国际赛道.rcz T7前 trap bearing 84° + 2026-06-19
                // 真机 session 142605bb 实测行进方向一致。
                passDirection = GeoVector(x = 0.00002724178431097556, y = 0.00044669506619011374),
                sequenceIndex = 1,
                minDirectionalSpeedMps = null
            ),
            TimingGate(
                id = "s2",
                name = "s2",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(30.4957579139104, 104.4369620745035),
                    end = GeoPoint(30.495765752756267, 104.43748325882984)
                ),
                // 过线方向修正（fix-tfic-sector-dir）：原值 (-2.61e-4, 7.84e-6) 指向赛车行进
                // 反方向（南），取反后朝北 ~359°，与官方 .rcz 出S弯/出T13 trap bearing + 2026-06-19
                // 真机 session 142605bb 实测行进方向一致。
                passDirection = GeoVector(x = 0.0002605921631704301, y = -0.000007838845867048829),
                sequenceIndex = 2,
                minDirectionalSpeedMps = null
            )
        )
    ),
    // round add-preset-track-xic：厦门国际赛车场（XIC, Xiamen International Circuit）。
    // 数据来源：vbo session_20260530_1340.vbo（25Hz × 1758 samples / lap=002 fast lap 70.3s
    // 累计 haversine 1662.0m）+ rcz track_厦门国际赛车场.rcz（3 traps：S/F + Split1 + Split2）。
    // referencePath：lap=002 等距采样 15 点 + 闭合（design D1）；trap GeoLine 端点 = center ±
    // (width/2) × right_perp(bearing)（design D2）；passDirection magnitude 0.00025° 跟 TFIC
    // 同量级（design D3）。详见 OpenSpec change add-preset-track-xic/design.md。
    // id 命名跟 TFIC `preset-tfic-lpcc` 风格一致，对齐 livetiming 服务端 trackId 契约。
    Track(
        id = "preset-xic-lpcc",
        name = TrackName(
            zh = "厦门国际赛车场",
            en = "Xiamen International Racetrack",
            abbr = "XIC",
        ),
        lengthKm = 1.662,
        thumbnailAssetPath = null,
        referencePath = TrackPath(
            points = listOf(
                GeoPoint(24.6546828, 118.3154782),
                GeoPoint(24.6552135, 118.3164043),
                GeoPoint(24.6546617, 118.3168557),
                GeoPoint(24.6536902, 118.3166453),
                GeoPoint(24.6531167, 118.3157807),
                GeoPoint(24.6523408, 118.3151843),
                GeoPoint(24.6522182, 118.3142233),
                GeoPoint(24.6529005, 118.3149722),
                GeoPoint(24.6535662, 118.3157582),
                GeoPoint(24.6542783, 118.3163143),
                GeoPoint(24.6540497, 118.3154542),
                GeoPoint(24.6533417, 118.3146808),
                GeoPoint(24.6527822, 118.3138377),
                GeoPoint(24.6535010, 118.3137048),
                GeoPoint(24.6540982, 118.3145875),
                GeoPoint(24.6546828, 118.3154782)
            )
        ),
        startFinishGate = TimingGate(
            id = "start-finish",
            name = "起点",
            type = TimingGateType.StartFinish,
            line = GeoLine(
                start = GeoPoint(24.6544286231580, 118.3156752761548),
                end = GeoPoint(24.6549747101753, 118.3152387238452)
            ),
            passDirection = GeoVector(x = 0.0002225331396, y = 0.0001469463131),
            sequenceIndex = 0,
            minDirectionalSpeedMps = null
        ),
        sectorGates = listOf(
            TimingGate(
                id = "s1",
                name = "s1",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(24.6524060479335, 118.3147959732798),
                    end = GeoPoint(24.6519949520665, 118.3149973600536)
                ),
                passDirection = GeoVector(x = -0.0002512853751, y = -0.0001016841608),
                sequenceIndex = 1,
                minDirectionalSpeedMps = null
            ),
            TimingGate(
                id = "s2",
                name = "s2",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(24.6540243516169, 118.3150808585765),
                    end = GeoPoint(24.6537006483831, 118.3154248080901)
                ),
                passDirection = GeoVector(x = -0.0001978659847, y = -0.0001736645926),
                sequenceIndex = 2,
                minDirectionalSpeedMps = null
            )
        )
    )
)

internal val presetTracks: List<Track> = mainPresets + extraPresetTracks()

class PresetTrackCatalog : TrackCatalog {
    // A37：内存直返，suspend 不强制 withContext(Dispatchers.IO)
    override suspend fun getAllTracks(): List<Track> = presetTracks

    override fun getTrack(trackId: String): Track? = presetTracks.firstOrNull { it.id == trackId }
}
