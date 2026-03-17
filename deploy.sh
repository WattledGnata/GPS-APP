#!/bin/bash

# 快速部署脚本 - 将APK安装到两台设备

echo "======================================"
echo "GPS应用快速部署"
echo "======================================"
echo ""

# 检查设备连接
echo "检查设备连接..."
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

# 识别设备
XIAOMI_DEVICE=""
VIVO_DEVICE=""

for DEVICE in $DEVICES; do
    MODEL=$(adb -s "$DEVICE" shell getprop ro.product.model | tr -d '\r')
    echo "设备 $DEVICE: $MODEL"

    if [[ "$MODEL" == *"Xiaomi"* ]] || [[ "$MODEL" == *"小米"* ]] || [[ "$MODEL" == *"Redmi"* ]] || [[ "$MODEL" == *"POCO"* ]]; then
        XIAOMI_DEVICE="$DEVICE"
        echo "  → 识别为小米手机"
    elif [[ "$MODEL" == *"vivo"* ]] || [[ "$MODEL" == *"VIVO"* ]]; then
        VIVO_DEVICE="$DEVICE"
        echo "  → 识别为vivo手机"
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

# 安装应用
echo "======================================"
echo "安装应用"
echo "======================================"
echo ""

echo "【小米手机】安装模拟器..."
adb -s "$XIAOMI_DEVICE" install -r simulator/build/outputs/apk/debug/simulator-debug.apk
if [ $? -eq 0 ]; then
    echo "✓ 模拟器安装成功"
else
    echo "❌ 模拟器安装失败"
    exit 1
fi
echo ""

echo "【vivo手机】安装接收端..."
adb -s "$VIVO_DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk
if [ $? -eq 0 ]; then
    echo "✓ 接收端安装成功"
else
    echo "❌ 接收端安装失败"
    exit 1
fi
echo ""

echo "======================================"
echo "部署完成"
echo "======================================"
echo ""
echo "下一步:"
echo "1. 运行 ./test_ble_data.sh 开始测试"
echo "2. 或手动启动应用进行测试"
echo ""
