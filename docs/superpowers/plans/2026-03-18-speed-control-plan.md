# 动态速度模拟系统实现计划

**目标**: 实现实时速度控制和多种速度模式

**架构**:
- 模拟器端：速度控制器 + UI控制面板
- 接收端：实时显示速度数据

**技术栈**: Kotlin, Jetpack Compose, StateFlow

---

## 核心功能

### 1. 速度模式枚举
- STATIC (静止)
- CONSTANT (恒定)
- ACCELERATION (加速)
- DECELERATION (减速)
- WAVEFORM (波形)
- REALISTIC (真实驾驶)
- CUSTOM (自定义)

### 2. 速度控制器
- 实时速度计算
- 模式切换逻辑
- 参数调整接口

### 3. UI控制面板
- 速度模式选择
- 速度参数设置
- 实时速度显示
- 速度曲线图

---

## 实现步骤

### Step 1: 创建速度模式枚举
**文件**: `simulator/src/main/java/com/race/gps/simulator/data/SpeedMode.kt`

### Step 2: 实现速度控制器
**文件**: `simulator/src/main/java/com/race/gps/simulator/data/SpeedController.kt`

### Step 3: 扩展模拟器UI状态
**文件**: `simulator/src/main/java/com/race/gps/simulator/viewmodel/SimulatorViewModel.kt`

### Step 4: 创建速度控制UI
**文件**: `simulator/src/main/java/com/race/gps/simulator/ui/SpeedControlCard.kt`

### Step 5: 集成到模拟器数据生成
**文件**: `simulator/src/main/java/com/race/gps/simulator/data/GpsDataGenerator.kt`

### Step 6: 更新主界面
**文件**: `simulator/src/main/java/com/race/gps/simulator/ui/SimulatorScreen.kt`

### Step 7: 测试验证
- 编译测试
- 双机通信测试
- 速度显示验证

---

## 验收标准

1. ✅ 支持7种速度模式
2. ✅ 可以实时调整速度（0-300 km/h）
3. ✅ 速度曲线正确计算
4. ✅ 接收端速度显示正确
5. ✅ 模式切换平滑无卡顿

---

## 预计时间
2-3小时
