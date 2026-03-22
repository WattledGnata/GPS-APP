# GPS蓝牙数据传输项目 - 测试策略

**版本**: 1.0
**创建日期**: 2026-03-21
**测试工程师**: Test Engineer
**项目状态**: 第二阶段70%完成

---

## 1. 测试范围概述

### 1.1 项目模块

| 模块 | 状态 | 测试优先级 |
|------|------|------------|
| 28字节RaceChrono协议 | ✅ 已完成 | P0 - 高 |
| BLE连接管理 | ✅ 已完成 | P0 - 高 |
| 动态速度模拟系统 | 🔶 70%完成 | P0 - 高 |
| 智能启动系统 | ⏳ 待开发 | P1 - 中 |
| 数据质量监控 | ⏳ 待开发 | P1 - 中 |
| 离散数据处理 | ⏳ 待开发 | P2 - 低 |

### 1.2 测试类型

```
┌─────────────────────────────────────────────────────────────┐
│                    测试金字塔                                │
├─────────────────────────────────────────────────────────────┤
│                     ▲                                       │
│                    / \                                      │
│                   /   \                                     │
│                  / UI  \        10% - Espresso/Compose      │
│                 /───────\                                    │
│                / 集成   \      30% - BLE端到端               │
│               /─────────\                                   │
│              /  单元    \    60% - JUnit/MockK              │
│             /───────────\                                  │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 单元测试策略

### 2.1 SpeedController 测试

**测试文件**: `simulator/src/test/java/com/race/gps/simulator/data/SpeedControllerTest.kt`

#### 测试用例

| ID | 测试场景 | 输入 | 预期输出 | 优先级 |
|----|----------|------|----------|--------|
| SC-01 | STATIC模式 - 初始速度 | mode=STATIC, currentSpeed=0 | 返回0 | P0 |
| SC-02 | CONSTANT模式 - 目标速度 | mode=CONSTANT, targetSpeed=60 | 返回60 | P0 |
| SC-03 | ACCELERATION模式 - 加速计算 | mode=ACCELERATION, acc=2, dt=1s | 增加7.2 km/h | P0 |
| SC-04 | ACCELERATION模式 - 达到目标 | mode=ACCELERATION, currentSpeed=58, targetSpeed=60 | 停在60 | P0 |
| SC-05 | DECELERATION模式 - 减速计算 | mode=DECELERATION, acc=2, dt=1s | 减少7.2 km/h | P0 |
| SC-06 | DECELERATION模式 - 达到目标 | mode=DECELERATION, currentSpeed=62, targetSpeed=60 | 停在60 | P0 |
| SC-07 | WAVEFORM模式 - 正弦波 | mode=WAVEFORM, targetSpeed=60, t=0 | 在60±18范围 | P1 |
| SC-08 | REALISTIC模式 - 随机波动 | mode=REALISTIC, targetSpeed=60 | 在42-78范围 | P1 |
| SC-09 | 边界值 - 最大速度 | setTargetSpeed(400) | 限制为300 | P0 |
| SC-10 | 边界值 - 负速度 | setTargetSpeed(-10) | 限制为0 | P0 |
| SC-11 | 边界值 - 加速度限制 | setAcceleration(20) | 限制为10 | P0 |
| SC-12 | 边界值 - 最小加速度 | setAcceleration(0.01) | 限制为0.1 | P0 |
| SC-13 | 状态描述 - STATIC | getStatusDescription() | "静止 (0 km/h)" | P1 |
| SC-14 | reset() 重置 | 任意状态后调用reset() | 所有值恢复默认 | P1 |

### 2.2 RaceChronoParser 测试

**测试文件**: `app/src/test/java/com/race/gps/data/service/parser/RaceChronoParserTest.kt`

#### 测试用例

| ID | 测试场景 | 输入 | 预期输出 | 优先级 |
|----|----------|------|----------|--------|
| RP-01 | 正确解析28字节数据 | 有效28字节 | 正确GpsData对象 | P0 |
| RP-02 | 数据长度过短 | 20字节 | 返回原数据，记录错误 | P0 |
| RP-03 | 卫星数解析 | data[5]=0x4C (76) | satellites=12 | P0 |
| RP-04 | 速度解析 | data[18-21]=0x000005DC (1500) | speed=15.0 | P0 |
| RP-05 | 纬度解析 | data[6-9]对应60.1725897 | lat=60.1725897 | P0 |
| RP-06 | 经度解析 | data[10-13]对应24.9376543 | lon=24.9376543 | P0 |
| RP-07 | 频率计算 | 连续10次调用 | frequency≈10 | P0 |
| RP-08 | 大端序时间解析 | data[1-4]=0x002B4C12 | time=2868018 | P0 |
| RP-09 | HDOP解析 | data[26]=0x0A (10) | hdop=1.0 | P1 |
| RP-10 | VDOP解析 | data[27]=0x0A (10) | vdop=1.0 | P1 |
| RP-11 | isTestReady判断 | sats=6, hdop=1.5 | isTestReady=true | P0 |
| RP-12 | isTestReady判断 | sats=4, hdop=1.5 | isTestReady=false | P0 |
| RP-13 | reset() 清空状态 | reset()调用 | frequency=0, timestamps清空 | P1 |

### 2.3 GpsDataGenerator 测试

**测试文件**: `simulator/src/test/java/com/race/gps/simulator/data/GpsDataGeneratorTest.kt`

#### 测试用例

| ID | 测试场景 | 输入 | 预期输出 | 优先级 |
|----|----------|------|----------|--------|
| GD-01 | 生成28字节主数据 | generateGpsMainData() | 返回28字节数组 | P0 |
| GD-02 | 生成3字节时间数据 | generateGpsTimeData() | 返回3字节数组 | P0 |
| GD-03 | 设置速度 | setCurrentSpeed(60) | 数据中speed=6000 | P0 |
| GD-04 | 设置位置 | setCurrentPosition(x,y) | lat/lon正确编码 | P0 |
| GD-05 | 频率限制 | setFrequency(100) | frequency=25 | P0 |
| GD-06 | 卫星数限制 | setSatellites(30) | satellites=20 | P0 |
| GD-07 | 同步位循环 | 多次调用 | sync位0-7循环 | P1 |
| GD-08 | 大端序编码 | 检查各字段 | big endian正确 | P0 |

---

## 3. 集成测试策略

### 3.1 BLE端到端测试

**测试文件**: `app/src/androidTest/java/com/race/gps/bluetooth/BleEndToEndTest.kt`

#### 测试用例

| ID | 测试场景 | 步骤 | 预期结果 | 优先级 |
|----|----------|------|----------|--------|
| BLE-01 | 完整数据传输 | 1. 模拟器广播<br>2. 接收端扫描<br>3. 连接<br>4. 验证数据 | 接收端解析正确 | P0 |
| BLE-02 | MTU协商 | 连接后检查 | MTU≥28 | P0 |
| BLE-03 | 数据一致性 | 对比发送/接收hex | 100%一致 | P0 |
| BLE-04 | 连接断开恢复 | 断开重连 | 数据恢复传输 | P0 |
| BLE-05 | 多设备连接 | 模拟器同时连2设备 | 两设备都接收正确 | P1 |

### 3.2 速度控制端到端测试

**测试文件**: `simulator/src/androidTest/java/com/race/gps/simulator/SpeedControlEndToEndTest.kt`

#### 测试用例

| ID | 测试场景 | 步骤 | 预期结果 | 优先级 |
|----|----------|------|----------|--------|
| SE-01 | 恒定速度模式 | 设置CONSTANT 60km/h | 接收端显示60 | P0 |
| SE-02 | 加速模式 | 设置ACCELERATION到100 | 接收端看到速度递增 | P0 |
| SE-03 | 减速模式 | 设置DECELERATION到20 | 接收端看到速度递减 | P0 |
| SE-04 | 波形模式 | 设置WAVEFORM | 接收端看到速度波动 | P1 |
| SE-05 | 实时切换模式 | 运行中切换模式 | 速度平滑过渡 | P1 |

---

## 4. UI测试策略

### 4.1 SimulatorScreen UI测试

**测试文件**: `simulator/src/androidTest/java/com/race/gps/simulator/ui/SimulatorScreenTest.kt`

#### 测试用例

| ID | 测试场景 | 操作 | 验证 | 优先级 |
|----|----------|------|------|--------|
| UI-01 | 权限请求 | 启动应用 | 显示权限对话框 | P0 |
| UI-02 | 开始广播按钮 | 点击"开始广播" | 按钮状态变为停止 | P0 |
| UI-03 | 连接状态显示 | 设备连接后 | 显示"已连接"标识 | P0 |
| UI-04 | 频率设置 | 调整频率滑块 | 频率值更新 | P1 |
| UI-05 | 卫星数设置 | 调整卫星数 | 卫星数更新 | P1 |

### 4.2 SpeedControlCard UI测试

**测试文件**: `simulator/src/androidTest/java/com/race/gps/simulator/ui/SpeedControlCardTest.kt`

#### 测试用例

| ID | 测试场景 | 操作 | 验证 | 优先级 |
|----|----------|------|------|--------|
| SCUI-01 | 模式选择 | 点击"恒定"单选按钮 | 选中恒定模式 | P0 |
| SCUI-02 | 速度滑块 | 拖动速度滑块 | 速度值实时更新 | P0 |
| SCUI-03 | 加速度滑块 | 拖动加速度滑块 | 加速度值更新 | P1 |
| SCUI-04 | 当前速度显示 | 模式切换 | 显示当前速度值 | P0 |
| SCUI-05 | 状态描述 | 不同模式 | 显示对应描述文本 | P1 |

---

## 5. 兼容性测试策略

### 5.1 Android版本兼容性

| 设备 | Android版本 | 测试重点 | 优先级 |
|------|-------------|----------|--------|
| 小米 | Android 8 (API 26) | 权限请求、BLE扫描 | P0 |
| vivo | Android 13+ | 新权限模型、MTU | P0 |
| 模拟器 | Android 14 | 基本功能验证 | P1 |

### 5.2 屏幕尺寸兼容性

| 屏幕类型 | 分辨率 | 测试重点 |
|----------|--------|----------|
| 小屏 | 360x640 | 布局紧凑性 |
| 中屏 | 720x1280 | 标准布局 |
| 大屏 | 1080x1920+ | 组件间距 |

---

## 6. 边界和异常测试

### 6.1 边界值测试

| 参数 | 最小值 | 最大值 | 异常值 |
|------|--------|--------|--------|
| 速度 | 0 | 300 | -10, 500 |
| 加速度 | 0.1 | 10 | 0, 20 |
| 频率 | 1 | 25 | 0, 100 |
| 卫星数 | 4 | 20 | 0, 30 |

### 6.2 异常场景测试

| ID | 场景 | 预期行为 |
|----|------|----------|
| ERR-01 | 蓝牙关闭 | 显示提示，引导开启 |
| ERR-02 | 位置权限拒绝 | 显示权限说明 |
| ERR-03 | MTU<28 | 请求MTU更新，失败则提示 |
| ERR-04 | 连接超时 | 超时提示，允许重试 |
| ERR-05 | 数据格式错误 | 记录日志，忽略错误包 |
| ERR-06 | 速度突变>20km/h | 检测为异常，标记 |

---

## 7. 性能测试

### 7.1 性能指标

| 指标 | 目标 | 测量方法 |
|------|------|----------|
| 数据传输延迟 | <100ms | logcat时间戳对比 |
| 帧率 | ≥60fps | SurfaceFlinger跟踪 |
| 内存增长 | <1MB/小时 | LeakCanary监控 |
| BLE连接时间 | <5s | 连接日志分析 |

### 7.2 压力测试

| 场景 | 条件 | 验证 |
|------|------|------|
| 长时间运行 | 连续2小时 | 无内存泄漏 |
| 高频数据 | 25Hz持续 | 无丢包 |
| 快速重连 | 连接断开10次 | 恢复正常 |

---

## 8. 测试工具和依赖

### 8.1 需要添加的依赖

```kotlin
// 单元测试
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
testImplementation("io.mockk:mockk:1.13.5")
testImplementation("app.cash.turbine:turbine:1.0.0")

// Android测试
androidTestImplementation("androidx.compose.ui:ui-test-manifest:1.5.0")
androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.5.0")
androidTestImplementation("io.mockk:mockk-android:1.13.5")

// 测试工具
debugImplementation("androidx.compose.ui:ui-tooling:1.5.0")
```

### 8.2 现有测试工具

| 工具 | 用途 |
|------|------|
| `test_ble_data.sh` | 自动化端到端测试 |
| `analyze_logs.sh` | 日志一致性分析 |
| `deploy.sh` | 快速部署测试 |

---

## 9. 测试执行计划

### 9.1 Phase 1: 单元测试 (立即执行)

```
Week 1:
- Day 1-2: SpeedController测试
- Day 3: RaceChronoParser测试
- Day 4: GpsDataGenerator测试
- Day 5: 测试覆盖率分析
```

### 9.2 Phase 2: 集成测试 (工程师完成后)

```
Week 2:
- Day 1-2: BLE端到端测试
- Day 3: 速度控制端到端测试
- Day 4-5: 问题修复和回归
```

### 9.3 Phase 3: UI测试 (与工程师并行)

```
Week 3:
- 与UI开发并行编写UI测试
- 每完成一个Screen立即编写测试
```

---

## 10. 测试覆盖率目标

| 模块 | 目标覆盖率 | 当前 | 状态 |
|------|------------|------|------|
| SpeedController | 90% | 0% | 🔴 |
| RaceChronoParser | 85% | 0% | 🔴 |
| GpsDataGenerator | 80% | 0% | 🔴 |
| SimulatorViewModel | 70% | 0% | 🔴 |
| UI层 | 50% | 0% | 🔴 |

---

## 11. 验收标准

### 11.1 单元测试验收

- 所有P0用例100%通过
- 测试覆盖率≥70%
- 无Flaky测试

### 11.2 集成测试验收

- 双机数据传输成功率≥99%
- 数据一致性100%
- 速度变化延迟<200ms

### 11.3 UI测试验收

- 所有关键用户流程可执行
- 无明显UI卡顿
| 测试框架 | 版本 | 用途 |
|----------|------|------|
| JUnit | 4 | 单元测试框架 |
| MockK | 1.13.5 | Mock框架 |
| Turbine | 1.0.0 | Flow测试 |
| Espresso | 3.5.1 | Android UI测试 |
| Compose Testing | 1.5.0 | Compose UI测试 |

---

## 附录

### A. 测试环境配置

```bash
# 运行单元测试
./gradlew test

# 运行特定测试类
./gradlew test --tests SpeedControllerTest

# 运行Android测试
./gradlew connectedAndroidTest

# 生成覆盖率报告
./gradlew jacocoTestReport
```

### B. 设备准备清单

- [ ] 小米手机 (Android 8) - 充电>50%
- [ ] vivo手机 (Android 13+) - 充电>50%
- [ ] USB调试已启用
- [ ] 蓝牙已开启
- [ ] 位置服务已开启

---

**文档状态**: ✅ 已完成
**下一步**: 开始编写单元测试用例
