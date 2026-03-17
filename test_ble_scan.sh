#!/bin/bash

echo "=== GPS模拟器BLE广播检查 ==="
echo ""

echo "1. 检查蓝牙状态"
adb shell "settings get global bluetooth_on"
echo ""

echo "2. 检查位置服务状态"
adb shell "settings get secure location_providers_allowed"
echo ""

echo "3. 检查模拟器应用权限"
adb shell "dumpsys package com.race.gps.simulator" | grep -A 5 "granted=true"
echo ""

echo "4. 查看最新BLE广播日志"
adb logcat -d -s GpsPeripheral:* GattServer:* Simulator:* | tail -20
echo ""

echo "5. 检查BLE广播状态"
adb shell "dumpsys bluetooth_manager" | grep -E "Advertising|Advertise" | tail -10
echo ""

echo "=== 检查完成 ==="
