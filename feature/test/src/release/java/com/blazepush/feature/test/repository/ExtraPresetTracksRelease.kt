// 本函数 MUST 仅由 src/debug + src/release 双源集互斥各提供一份实现；
// main 源集禁止声明同签名函数（debug variant 编译时同包同签名 top-level
// 函数会触发 duplicate JVM declarations）。
// 本 round 由 OpenSpec change `add-debug-preset-track-boyu-loop` design D1/D2 锁定该机制。
package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.Track

internal fun extraPresetTracks(): List<Track> = emptyList()