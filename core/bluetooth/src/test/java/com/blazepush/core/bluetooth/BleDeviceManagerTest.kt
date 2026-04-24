package com.blazepush.core.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * @description 战役 G R6 回归测试：autoReconnectLastDevice else 分支 fallback
 *              扫描（A29）。覆盖 Spec ble-connection Requirement 6。
 *              策略：`BleDeviceManager.init` 构造时立即 `scope.launch { ... }` 跑
 *              autoReconnectLastDevice，真实 IO dispatcher 虚拟时钟不生效，且
 *              `BleDeviceScanner` 构造需要 Android BluetoothAdapter runtime，
 *              JVM 下无法 mock 住构造时序。本文件采用**源码结构断言**锁定关键
 *              代码形状（与 R2 race / R5 connect 清旧同策略）。行为正确性由
 *              commit diff review + 真机冷启动验证（评审方 2026-04-24 修订的
 *              A23 核销条件 (2a/2b/2c) 间接验证 fallback 扫描路径）。文档修订
 *              （R6 Scenario 3/4）走合流门槛 7.6 的 grep 命令级校验，不在此
 *              JVM 测试里验证（评审方第二轮明确此调整）。
 * @author haozhang93
 * @date 2026-04-24
 */
class BleDeviceManagerTest {

    /**
     * R6 主断言：autoReconnectLastDevice else 分支 MUST 调 startScan() 并带
     * "fallback 到扫描" log 文案。硬区分 v1（只 log 不 scan）vs v2（startScan 存在）。
     */
    @Test
    fun autoReconnectLastDevice_whenLastAddressNull_fallsBackToStartScan_sourceAssertion() {
        val source = File(
            "src/main/java/com/blazepush/core/bluetooth/BleDeviceManager.kt"
        ).readText()

        val fnStart = source.indexOf("fun autoReconnectLastDevice()")
        assertTrue("autoReconnectLastDevice 必须定义", fnStart > 0)

        val elseBranchIdx = source.indexOf("} else {", fnStart)
        assertTrue(
            "autoReconnectLastDevice 必须有 else 分支处理 lastDeviceAddress == null",
            elseBranchIdx > 0,
        )

        val elseEnd = source.indexOf("\n                }\n", elseBranchIdx)
        assertTrue("else 分支必须闭合", elseEnd > elseBranchIdx)
        val elseBody = source.substring(elseBranchIdx, elseEnd)

        assertTrue(
            "else 分支 MUST 调 startScan() fallback 扫描（v1 只 log 不 scan）",
            elseBody.contains("startScan()"),
        )
        assertTrue(
            "else 分支 log 文案 MUST 含 \"fallback 到扫描\" 方便 grep 核销",
            elseBody.contains("fallback 到扫描"),
        )
    }

    /**
     * R6 反向断言：防止未来 merge 误回退 —— log 文案 MUST 尾部含
     * "fallback 到扫描"，不能以原 v1 的 "没有上次连接的设备记录" 孤立结尾。
     * 保证真机 log 审计能直接溯源 R6 修复是否生效。
     */
    @Test
    fun autoReconnectLastDevice_elseBranchDoesNotRegressToLogOnly() {
        val source = File(
            "src/main/java/com/blazepush/core/bluetooth/BleDeviceManager.kt"
        ).readText()

        val logLineIdx = source.indexOf("没有上次连接的设备记录")
        assertTrue("log 起点文案存在", logLineIdx > 0)

        val logLineEnd = source.indexOf('\n', logLineIdx)
        val logLine = source.substring(logLineIdx, logLineEnd)

        assertTrue(
            "log 文案 MUST 包含 \"fallback 到扫描\" 后缀，让真机 log 可溯源 R6 修复",
            logLine.contains("fallback 到扫描"),
        )
        assertFalse(
            "log 文案不应以原 v1 的 \"没有上次连接的设备记录\" 孤立结尾（会误读为 R6 未修）",
            logLine.trim().endsWith("没有上次连接的设备记录\")"),
        )
    }
}