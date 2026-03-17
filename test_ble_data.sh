#!/bin/bash

# GPS蓝牙数据传输测试脚本
# 用于自动部署、测试和监控两台设备的BLE通信

set -e  # 遇到错误立即退出

echo "======================================"
echo "GPS蓝牙数据传输测试"
echo "======================================"
echo ""

# 检查ADB连接
echo "1. 检查设备连接..."
adb devices -l
echo ""

# 获取设备列表
DEVICES=$(adb devices | grep -v "List" | awk '{print $1}' | grep -v "^$")
DEVICE_COUNT=$(echo "$DEVICES" | wc -l)

if [ "$DEVICE_COUNT" -lt 2 ]; then
    echo "❌ 错误: 需要至少2台设备连接"
    echo "当前连接设备数: $DEVICE_COUNT"
    exit 1
fi

echo "✓ 检测到 $DEVICE_COUNT 台设备"
echo ""

# 获取设备列表（数组）
readarray -t DEVICE_ARRAY <<<"$DEVICES"
XIAOMI_DEVICE=""
VIVO_DEVICE=""

# 识别设备
for DEVICE in "${DEVICE_ARRAY[@]}"; do
    MODEL=$(adb -s "$DEVICE" shell getprop ro.product.model | tr -d '\r')
    echo "设备 $DEVICE: $MODEL"

    if [[ "$MODEL" == *"Xiaomi"* ]] || [[ "$MODEL" == *"小米"* ]] || [[ "$MODEL" == *"Redmi"* ]] || [[ "$MODEL" == *"POCO"* ]]; then
        XIAOMI_DEVICE="$DEVICE"
        echo "  → 识别为小米手机（模拟器）"
    elif [[ "$MODEL" == *"vivo"* ]] || [[ "$MODEL" == *"VIVO"* ]]; then
        VIVO_DEVICE="$DEVICE"
        echo "  → 识别为vivo手机（接收端）"
    fi
done

echo ""

if [ -z "$XIAOMI_DEVICE" ]; then
    echo "❌ 错误: 未找到小米设备"
    exit 1
fi

if [ -z "$VIVO_DEVICE" ]; then
    echo "❌ 错误: 未找到vivo设备"
    exit 1
fi

echo "✓ 设备识别成功"
echo "  模拟器: $XIAOMI_DEVICE"
echo "  接收端: $VIVO_DEVICE"
echo ""

# 安装APK
echo "2. 安装应用..."
echo "安装模拟器到小米手机..."
adb -s "$XIAOMI_DEVICE" install -r simulator/build/outputs/apk/debug/simulator-debug.apk || {
    echo "❌ 模拟器安装失败"
    exit 1
}

echo "安装接收端到vivo手机..."
adb -s "$VIVO_DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk || {
    echo "❌ 接收端安装失败"
    exit 1
}

echo "✓ 应用安装完成"
echo ""

# 清除日志缓冲
echo "3. 清除日志缓冲..."
adb -s "$XIAOMI_DEVICE" logcat -c
adb -s "$VIVO_DEVICE" logcat -c
echo "✓ 日志缓冲已清除"
echo ""

# 启动日志监控（后台）
echo "4. 启动日志监控..."
echo "日志将保存到当前目录:"
echo "  - xiaomi_simulator.log (小米模拟器日志)"
echo "  - vivo_receiver.log (vivo接收端日志)"
echo ""

# 启动日志监控（后台运行）
adb -s "$XIAOMI_DEVICE" logcat -v time -s GpsDataGenerator:D GattServerManager:D > xiaomi_simulator.log 2>&1 &
XIAOMI_LOG_PID=$!

adb -s "$VIVO_DEVICE" logcat -v time -s BleConnection:D RaceChronoParser:D > vivo_receiver.log 2>&1 &
VIVO_LOG_PID=$!

echo "✓ 日志监控已启动 (PID: $XIAOMI_LOG_PID, $VIVO_LOG_PID)"
echo ""

# 提示用户操作
echo "======================================"
echo "测试步骤"
echo "======================================"
echo ""
echo "请按以下步骤操作:"
echo ""
echo "【小米手机】(模拟器)"
echo "1. 打开 'GPSSimulator' 应用"
echo "2. 授予蓝牙和位置权限"
echo "3. 点击 '开始广播' 按钮"
echo "4. 确认显示 '广播中'"
echo ""
echo "【vivo手机】(接收端)"
echo "1. 打开 'GPS测试' 应用"
echo "2. 授予蓝牙和位置权限"
echo "3. 点击 '扫描设备' 按钮"
echo "4. 找到小米设备并连接"
echo "5. 观察数据是否正常显示"
echo ""
echo "======================================"
echo "监控日志 (按 Ctrl+C 停止)"
echo "======================================"
echo ""

# 实时显示日志
trap "echo ''; echo '停止日志监控...'; kill $XIAOMI_LOG_PID $VIVO_LOG_PID 2>/dev/null; echo ''; echo '测试完成'; echo ''; echo '日志文件:'; ls -lh xiaomi_simulator.log vivo_receiver.log 2>/dev/null || echo '无日志文件生成'; exit 0" INT TERM

# 显示小米日志（左）和vivo日志（右）
# 使用tail -f监控日志文件
(
    while true; do
        sleep 1

        # 清屏并显示分隔
        clear
        echo "======================================"
        date
        echo "======================================"
        echo ""

        # 显示最新的小米日志（最近5行）
        if [ -f xiaomi_simulator.log ]; then
            echo "【小米模拟器】最新日志:"
            tail -n 5 xiaomi_simulator.log 2>/dev/null || echo "等待数据..."
            echo ""
        fi

        # 显示最新的vivo日志（最近5行）
        if [ -f vivo_receiver.log ]; then
            echo "【vivo接收端】最新日志:"
            tail -n 5 vivo_receiver.log 2>/dev/null || echo "等待数据..."
            echo ""
        fi

        echo "按 Ctrl+C 停止监控..."
    done
)

# 等待用户中断
wait
