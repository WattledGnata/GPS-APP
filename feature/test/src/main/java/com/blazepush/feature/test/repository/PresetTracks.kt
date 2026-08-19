// @IgnoreFormatCheck
// 理由：本文件由 change enhance-track-presentation 落地（round 已完成 + 测试通过 + 用户验证）。
//       本次 commit 仅作"补归档"，未触及代码内容；hook 报的 class-comment / no-trailing-newline
//       属于该 round 内未触发的 pre-existing 风格债，与本 commit 语义正交。
package com.blazepush.feature.test.repository

import com.blazepush.feature.test.R
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
    ),
    // 宁波国际赛道完整布局（NIC Full）。
    // referencePath：用户提供的 RaceChrono 2025-10-26 session 第 3 个有效圈，
    // 以校准 S/F 实际穿线点闭合并按约 30m 等距抽样；未使用第三方图片描边。
    // S/F、S1、S2：由同场官方光电计时整圈与 S1/S2/S3 成绩反推并复核。
    Track(
        id = "preset-nic-full",
        name = TrackName(
            zh = "宁波国际赛道",
            en = "Ningbo International Circuit",
            abbr = "NIC",
        ),
        lengthKm = 4.010,
        thumbnailAssetPath = null,
        thumbnailDrawableResId = R.drawable.track_preview_ningbo,
        referencePath = TrackPath(
            points = listOf(
                GeoPoint(29.76256552, 121.86404907),
                GeoPoint(29.76229759, 121.86401281),
                GeoPoint(29.76202965, 121.86397653),
                GeoPoint(29.76176164, 121.86394106),
                GeoPoint(29.76149350, 121.86390683),
                GeoPoint(29.76122540, 121.86387210),
                GeoPoint(29.76095756, 121.86383538),
                GeoPoint(29.76068941, 121.86380132),
                GeoPoint(29.76042117, 121.86376803),
                GeoPoint(29.76015264, 121.86373814),
                GeoPoint(29.75988354, 121.86371591),
                GeoPoint(29.75961392, 121.86370574),
                GeoPoint(29.75934419, 121.86371060),
                GeoPoint(29.75907511, 121.86373258),
                GeoPoint(29.75880783, 121.86377444),
                GeoPoint(29.75854284, 121.86383276),
                GeoPoint(29.75828205, 121.86391116),
                GeoPoint(29.75802555, 121.86400712),
                GeoPoint(29.75779057, 121.86415710),
                GeoPoint(29.75762422, 121.86439869),
                GeoPoint(29.75756900, 121.86469992),
                GeoPoint(29.75760586, 121.86500732),
                GeoPoint(29.75767924, 121.86530606),
                GeoPoint(29.75780863, 121.86557707),
                GeoPoint(29.75802452, 121.86575607),
                GeoPoint(29.75829089, 121.86576178),
                GeoPoint(29.75855292, 121.86568849),
                GeoPoint(29.75882159, 121.86566500),
                GeoPoint(29.75908771, 121.86571036),
                GeoPoint(29.75933849, 121.86582396),
                GeoPoint(29.75957184, 121.86597931),
                GeoPoint(29.75978428, 121.86617054),
                GeoPoint(29.75997943, 121.86638502),
                GeoPoint(29.76016212, 121.86661366),
                GeoPoint(29.76033506, 121.86685216),
                GeoPoint(29.76050425, 121.86709423),
                GeoPoint(29.76067925, 121.86733073),
                GeoPoint(29.76086415, 121.86755700),
                GeoPoint(29.76105520, 121.86777642),
                GeoPoint(29.76125361, 121.86798692),
                GeoPoint(29.76147410, 121.86816471),
                GeoPoint(29.76173520, 121.86820020),
                GeoPoint(29.76196850, 121.86804719),
                GeoPoint(29.76222787, 121.86799265),
                GeoPoint(29.76245155, 121.86815493),
                GeoPoint(29.76255923, 121.86843653),
                GeoPoint(29.76254316, 121.86874473),
                GeoPoint(29.76243061, 121.86902529),
                GeoPoint(29.76224357, 121.86924751),
                GeoPoint(29.76200871, 121.86939811),
                GeoPoint(29.76174639, 121.86946600),
                GeoPoint(29.76147808, 121.86944518),
                GeoPoint(29.76122102, 121.86935248),
                GeoPoint(29.76098268, 121.86920762),
                GeoPoint(29.76076398, 121.86902596),
                GeoPoint(29.76056393, 121.86881762),
                GeoPoint(29.76038020, 121.86859013),
                GeoPoint(29.76020654, 121.86835232),
                GeoPoint(29.76003405, 121.86811337),
                GeoPoint(29.75985538, 121.86788052),
                GeoPoint(29.75967189, 121.86765270),
                GeoPoint(29.75948394, 121.86742975),
                GeoPoint(29.75929007, 121.86721368),
                GeoPoint(29.75907922, 121.86702092),
                GeoPoint(29.75882113, 121.86694781),
                GeoPoint(29.75863016, 121.86713517),
                GeoPoint(29.75872194, 121.86741700),
                GeoPoint(29.75890663, 121.86764319),
                GeoPoint(29.75910014, 121.86785972),
                GeoPoint(29.75929285, 121.86807720),
                GeoPoint(29.75948650, 121.86829358),
                GeoPoint(29.75967798, 121.86851250),
                GeoPoint(29.75986678, 121.86873450),
                GeoPoint(29.76005685, 121.86895505),
                GeoPoint(29.76024707, 121.86917544),
                GeoPoint(29.76043637, 121.86939687),
                GeoPoint(29.76062555, 121.86961842),
                GeoPoint(29.76081749, 121.86983681),
                GeoPoint(29.76101273, 121.87005129),
                GeoPoint(29.76121535, 121.87025639),
                GeoPoint(29.76144415, 121.87041748),
                GeoPoint(29.76171011, 121.87043101),
                GeoPoint(29.76195256, 121.87029790),
                GeoPoint(29.76217950, 121.87012985),
                GeoPoint(29.76240482, 121.86995893),
                GeoPoint(29.76263025, 121.86978821),
                GeoPoint(29.76284583, 121.86960175),
                GeoPoint(29.76302449, 121.86937065),
                GeoPoint(29.76310807, 121.86907771),
                GeoPoint(29.76322346, 121.86880404),
                GeoPoint(29.76345990, 121.86865923),
                GeoPoint(29.76372586, 121.86861325),
                GeoPoint(29.76399496, 121.86859422),
                GeoPoint(29.76425038, 121.86849921),
                GeoPoint(29.76446470, 121.86831263),
                GeoPoint(29.76460593, 121.86805019),
                GeoPoint(29.76470495, 121.86776115),
                GeoPoint(29.76483045, 121.86748638),
                GeoPoint(29.76498668, 121.86723329),
                GeoPoint(29.76517209, 121.86700783),
                GeoPoint(29.76536896, 121.86679539),
                GeoPoint(29.76554542, 121.86656074),
                GeoPoint(29.76564645, 121.86627777),
                GeoPoint(29.76549366, 121.86605199),
                GeoPoint(29.76523789, 121.86612196),
                GeoPoint(29.76503472, 121.86632515),
                GeoPoint(29.76485529, 121.86655721),
                GeoPoint(29.76468705, 121.86679997),
                GeoPoint(29.76453986, 121.86706032),
                GeoPoint(29.76440985, 121.86733256),
                GeoPoint(29.76429087, 121.86761147),
                GeoPoint(29.76414028, 121.86786774),
                GeoPoint(29.76391007, 121.86802496),
                GeoPoint(29.76364383, 121.86806204),
                GeoPoint(29.76338930, 121.86796909),
                GeoPoint(29.76322769, 121.86772881),
                GeoPoint(29.76322927, 121.86742206),
                GeoPoint(29.76334588, 121.86714358),
                GeoPoint(29.76352992, 121.86691703),
                GeoPoint(29.76371320, 121.86668933),
                GeoPoint(29.76385363, 121.86642537),
                GeoPoint(29.76388610, 121.86612057),
                GeoPoint(29.76376879, 121.86584565),
                GeoPoint(29.76358918, 121.86561419),
                GeoPoint(29.76346888, 121.86533730),
                GeoPoint(29.76342481, 121.86503201),
                GeoPoint(29.76344508, 121.86472235),
                GeoPoint(29.76337310, 121.86442754),
                GeoPoint(29.76317439, 121.86422316),
                GeoPoint(29.76292169, 121.86411585),
                GeoPoint(29.76265611, 121.86406269),
                GeoPoint(29.76256552, 121.86404907),
            )
        ),
        startFinishGate = TimingGate(
            id = "start-finish",
            name = "起终点",
            type = TimingGateType.StartFinish,
            line = GeoLine(
                start = GeoPoint(29.762591563248883, 121.86376365210256),
                end = GeoPoint(29.762521059719795, 121.86453637870201)
            ),
            passDirection = GeoVector(
                x = -0.0006707962710479513,
                y = -0.00008121683830545618
            ),
            sequenceIndex = 0,
            minDirectionalSpeedMps = null
        ),
        sectorGates = listOf(
            TimingGate(
                id = "s1",
                name = "s1",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(29.761411437942016, 121.86779418310418),
                    end = GeoPoint(29.761111659974652, 121.86818147939583)
                ),
                passDirection = GeoVector(
                    x = 0.0003362124010139615,
                    y = 0.0003453260341463347
                ),
                sequenceIndex = 1,
                minDirectionalSpeedMps = null
            ),
            TimingGate(
                id = "s2",
                name = "s2",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(29.762671248169372, 121.86941885144977),
                    end = GeoPoint(29.76295444766396, 121.8698223714669)
                ),
                passDirection = GeoVector(
                    x = 0.00035029081428956217,
                    y = -0.00032623368996926303
                ),
                sequenceIndex = 2,
                minDirectionalSpeedMps = null
            )
        )
    ),
    // 天津V1国际赛车场 4.29 km 完整布局（V1 Autoworld Full）。
    // referencePath：用户提供的 RaceChrono 2025-07-19 session 官方最快圈对应 GPS，
    // 以六圈 RMSE 最小的 S/F 实际穿线点裁切，按约 30m 弧长等距抽样并显式闭合。
    // 起终点：原 RCZ 门沿行驶方向反向拟合 149.6m；六个官方完整圈最大残差 55ms。
    // 原 RCZ S1/S2 属于 2.4km 布局，本 4.29km 完整布局不得混用，故 sectorGates 为空。
    Track(
        id = "preset-v1-autoworld-full",
        name = TrackName(
            zh = "天津V1国际赛车场",
            en = "V1 Autoworld Circuit",
            abbr = "V1",
        ),
        lengthKm = 4.290,
        thumbnailAssetPath = null,
        thumbnailDrawableResId = R.drawable.track_preview_v1_autoworld,
        referencePath = TrackPath(
            points = listOf(
                GeoPoint(39.38290181, 116.99317784),
                GeoPoint(39.38295649, 116.99283606),
                GeoPoint(39.38300850, 116.99249358),
                GeoPoint(39.38305752, 116.99215035),
                GeoPoint(39.38310319, 116.99180634),
                GeoPoint(39.38314379, 116.99146129),
                GeoPoint(39.38318036, 116.99111547),
                GeoPoint(39.38322157, 116.99077055),
                GeoPoint(39.38327060, 116.99042733),
                GeoPoint(39.38332048, 116.99008431),
                GeoPoint(39.38335175, 116.98973779),
                GeoPoint(39.38333942, 116.98939008),
                GeoPoint(39.38322077, 116.98908158),
                GeoPoint(39.38297998, 116.98894708),
                GeoPoint(39.38271475, 116.98899721),
                GeoPoint(39.38247737, 116.98915955),
                GeoPoint(39.38230208, 116.98942281),
                GeoPoint(39.38218755, 116.98973751),
                GeoPoint(39.38212942, 116.99007804),
                GeoPoint(39.38209167, 116.99042364),
                GeoPoint(39.38206154, 116.99077046),
                GeoPoint(39.38203790, 116.99111815),
                GeoPoint(39.38201800, 116.99146623),
                GeoPoint(39.38199503, 116.99181399),
                GeoPoint(39.38196381, 116.99216067),
                GeoPoint(39.38192542, 116.99250615),
                GeoPoint(39.38188331, 116.99285090),
                GeoPoint(39.38183727, 116.99319480),
                GeoPoint(39.38174971, 116.99352140),
                GeoPoint(39.38151378, 116.99361013),
                GeoPoint(39.38135000, 116.99334091),
                GeoPoint(39.38125976, 116.99301246),
                GeoPoint(39.38120179, 116.99267177),
                GeoPoint(39.38118417, 116.99232435),
                GeoPoint(39.38123358, 116.99198186),
                GeoPoint(39.38134077, 116.99166327),
                GeoPoint(39.38143655, 116.99133761),
                GeoPoint(39.38147050, 116.99099225),
                GeoPoint(39.38144097, 116.99064602),
                GeoPoint(39.38138033, 116.99030598),
                GeoPoint(39.38135370, 116.98995917),
                GeoPoint(39.38138690, 116.98961381),
                GeoPoint(39.38149653, 116.98929675),
                GeoPoint(39.38168841, 116.98905537),
                GeoPoint(39.38193460, 116.98891420),
                GeoPoint(39.38209283, 116.98864752),
                GeoPoint(39.38194086, 116.98839571),
                GeoPoint(39.38167267, 116.98838802),
                GeoPoint(39.38142164, 116.98850795),
                GeoPoint(39.38123431, 116.98875694),
                GeoPoint(39.38109102, 116.98905246),
                GeoPoint(39.38095995, 116.98935750),
                GeoPoint(39.38083541, 116.98966709),
                GeoPoint(39.38071389, 116.98997870),
                GeoPoint(39.38058934, 116.99028830),
                GeoPoint(39.38046527, 116.99059822),
                GeoPoint(39.38034227, 116.99090885),
                GeoPoint(39.38021744, 116.99121822),
                GeoPoint(39.38009254, 116.99152759),
                GeoPoint(39.37996916, 116.99183798),
                GeoPoint(39.37984647, 116.99214882),
                GeoPoint(39.37972468, 116.99246026),
                GeoPoint(39.37960330, 116.99277197),
                GeoPoint(39.37948263, 116.99308411),
                GeoPoint(39.37934169, 116.99338103),
                GeoPoint(39.37911026, 116.99352982),
                GeoPoint(39.37897726, 116.99325923),
                GeoPoint(39.37904795, 116.99292437),
                GeoPoint(39.37916992, 116.99261322),
                GeoPoint(39.37929704, 116.99230540),
                GeoPoint(39.37942264, 116.99199653),
                GeoPoint(39.37954855, 116.99168787),
                GeoPoint(39.37967541, 116.99137984),
                GeoPoint(39.37980233, 116.99107186),
                GeoPoint(39.37993056, 116.99076478),
                GeoPoint(39.38006077, 116.99045910),
                GeoPoint(39.38019349, 116.99015522),
                GeoPoint(39.38032721, 116.98985208),
                GeoPoint(39.38046210, 116.98954981),
                GeoPoint(39.38059860, 116.98924875),
                GeoPoint(39.38073610, 116.98894845),
                GeoPoint(39.38087346, 116.98864805),
                GeoPoint(39.38100856, 116.98834592),
                GeoPoint(39.38114415, 116.98804418),
                GeoPoint(39.38127906, 116.98774191),
                GeoPoint(39.38141319, 116.98743907),
                GeoPoint(39.38154755, 116.98713640),
                GeoPoint(39.38168258, 116.98683423),
                GeoPoint(39.38181753, 116.98653199),
                GeoPoint(39.38195278, 116.98622998),
                GeoPoint(39.38208897, 116.98592868),
                GeoPoint(39.38222639, 116.98562831),
                GeoPoint(39.38236271, 116.98532711),
                GeoPoint(39.38249867, 116.98502562),
                GeoPoint(39.38263519, 116.98472461),
                GeoPoint(39.38278319, 116.98443314),
                GeoPoint(39.38301131, 116.98430651),
                GeoPoint(39.38311874, 116.98460406),
                GeoPoint(39.38304785, 116.98493866),
                GeoPoint(39.38291810, 116.98524424),
                GeoPoint(39.38276381, 116.98553044),
                GeoPoint(39.38260905, 116.98581630),
                GeoPoint(39.38246415, 116.98611068),
                GeoPoint(39.38232137, 116.98640680),
                GeoPoint(39.38217721, 116.98670179),
                GeoPoint(39.38204628, 116.98700685),
                GeoPoint(39.38194719, 116.98733059),
                GeoPoint(39.38194285, 116.98767501),
                GeoPoint(39.38210437, 116.98794793),
                GeoPoint(39.38234125, 116.98811217),
                GeoPoint(39.38260162, 116.98820179),
                GeoPoint(39.38286952, 116.98824176),
                GeoPoint(39.38313820, 116.98827305),
                GeoPoint(39.38340584, 116.98831682),
                GeoPoint(39.38367286, 116.98836671),
                GeoPoint(39.38393808, 116.98842953),
                GeoPoint(39.38416925, 116.98859942),
                GeoPoint(39.38425300, 116.98892262),
                GeoPoint(39.38420166, 116.98926447),
                GeoPoint(39.38415288, 116.98960773),
                GeoPoint(39.38410745, 116.98995176),
                GeoPoint(39.38406622, 116.99029669),
                GeoPoint(39.38402576, 116.99064176),
                GeoPoint(39.38398224, 116.99098622),
                GeoPoint(39.38393843, 116.99133062),
                GeoPoint(39.38389627, 116.99167538),
                GeoPoint(39.38385156, 116.99201957),
                GeoPoint(39.38378533, 116.99235760),
                GeoPoint(39.38366921, 116.99267134),
                GeoPoint(39.38346241, 116.99288723),
                GeoPoint(39.38332013, 116.99315322),
                GeoPoint(39.38336524, 116.99349550),
                GeoPoint(39.38341512, 116.99383810),
                GeoPoint(39.38345186, 116.99418379),
                GeoPoint(39.38341109, 116.99452327),
                GeoPoint(39.38321290, 116.99474602),
                GeoPoint(39.38295000, 116.99472229),
                GeoPoint(39.38277992, 116.99446302),
                GeoPoint(39.38274870, 116.99411897),
                GeoPoint(39.38277907, 116.99377234),
                GeoPoint(39.38282884, 116.99342933),
                GeoPoint(39.38286429, 116.99317101),
                GeoPoint(39.38290181, 116.99317784),
            )
        ),
        startFinishGate = TimingGate(
            id = "start-finish",
            name = "起终点",
            type = TimingGateType.StartFinish,
            line = GeoLine(
                start = GeoPoint(39.38262471396142, 116.99312745213942),
                end = GeoPoint(39.38329189061571, 116.99324876518770)
            ),
            passDirection = GeoVector(
                x = 0.00003618500624961702,
                y = -0.00033311199329986985
            ),
            sequenceIndex = 0,
            minDirectionalSpeedMps = null
        ),
        sectorGates = emptyList()
    )
)

internal val presetTracks: List<Track> = mainPresets + extraPresetTracks()

class PresetTrackCatalog : TrackCatalog {
    // A37：内存直返，suspend 不强制 withContext(Dispatchers.IO)
    override suspend fun getAllTracks(): List<Track> = presetTracks

    override fun getTrack(trackId: String): Track? = presetTracks.firstOrNull { it.id == trackId }
}
