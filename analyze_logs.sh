#!/bin/bash

# 日志分析脚本 - 检查数据传输是否正确

echo "======================================"
echo "GPS蓝牙数据传输日志分析"
echo "======================================"
echo ""

# 检查日志文件
if [ ! -f "xiaomi_simulator.log" ]; then
    echo "❌ 错误: 找不到 xiaomi_simulator.log"
    echo "请先运行 test_ble_data.sh 进行测试"
    exit 1
fi

if [ ! -f "vivo_receiver.log" ]; then
    echo "❌ 错误: 找不到 vivo_receiver.log"
    echo "请先运行 test_ble_data.sh 进行测试"
    exit 1
fi

echo "✓ 日志文件存在"
echo ""

# 分析小米模拟器日志
echo "======================================"
echo "【小米模拟器】数据分析"
echo "======================================"
echo ""

# 提取发送的数据
XIAOMI_DATA=$(grep "Transmitting - Main:" xiaomi_simulator.log | tail -n 1)
if [ -n "$XIAOMI_DATA" ]; then
    XIAOMI_HEX=$(echo "$XIAOMI_DATA" | grep -o "Main: [A-F0-9]*" | cut -d' ' -f2)
    echo "✓ 检测到数据发送"
    echo "  原始数据 (28字节): $XIAOMI_HEX"
    echo "  数据长度: $(echo $XIAOMI_HEX | wc -c) 字符 (应为56字符，即28字节)"

    if [ $(echo $XIAOMI_HEX | wc -c) -eq 57 ]; then
        echo "  ✓ 数据长度正确"
    else
        echo "  ❌ 数据长度错误"
    fi
else
    echo "❌ 未检测到数据发送"
fi
echo ""

# 提取字段值
XIAOMI_FIELDS=$(grep "Fields -" xiaomi_simulator.log | tail -n 1)
if [ -n "$XIAOMI_FIELDS" ]; then
    echo "✓ 字段解析:"
    echo "  $XIAOMI_FIELDS"
else
    echo "❌ 未找到字段解析日志"
fi
echo ""

# 统计发送次数
XIAOMI_COUNT=$(grep -c "Transmitting - Main:" xiaomi_simulator.log || echo "0")
echo "发送次数: $XIAOMI_COUNT"
echo ""

# 分析vivo接收端日志
echo "======================================"
echo "【vivo接收端】数据分析"
echo "======================================"
echo ""

# 提取接收的数据
VIVO_DATA=$(grep "Received GPS Main Data (28 bytes):" vivo_receiver.log | tail -n 1)
if [ -n "$VIVO_DATA" ]; then
    VIVO_HEX=$(echo "$VIVO_DATA" | grep -o ": [A-F0-9]*$" | cut -d' ' -f2)
    echo "✓ 检测到数据接收"
    echo "  原始数据 (28字节): $VIVO_HEX"
    echo "  数据长度: $(echo $VIVO_HEX | wc -c) 字符 (应为56字符，即28字节)"

    if [ $(echo $VIVO_HEX | wc -c) -eq 57 ]; then
        echo "  ✓ 数据长度正确"
    else
        echo "  ❌ 数据长度错误"
    fi
else
    echo "❌ 未检测到数据接收"
fi
echo ""

# 提取解析结果
VIVO_PARSED=$(grep "Parsed:" vivo_receiver.log | tail -n 1)
if [ -n "$VIVO_PARSED" ]; then
    echo "✓ 数据解析:"
    echo "  $VIVO_PARSED"

    # 提取卫星数
    VIVO_SATS=$(echo "$VIVO_PARSED" | grep -o "Sats=[0-9]*" | cut -d'=' -f2)
    if [ "$VIVO_SATS" = "12" ]; then
        echo "  ✓ 卫星数正确 (12)"
    else
        echo "  ❌ 卫星数错误 (预期: 12, 实际: $VIVO_SATS)"
    fi
else
    echo "❌ 未找到解析日志"
fi
echo ""

# 统计接收次数
VIVO_COUNT=$(grep -c "Received GPS Main Data (28 bytes):" vivo_receiver.log || echo "0")
echo "接收次数: $VIVO_COUNT"
echo ""

# 数据对比
echo "======================================"
echo "数据一致性检查"
echo "======================================"
echo ""

if [ -n "$XIAOMI_HEX" ] && [ -n "$VIVO_HEX" ]; then
    if [ "$XIAOMI_HEX" = "$VIVO_HEX" ]; then
        echo "✓ 数据完全一致"
        echo "  小米发送: $XIAOMI_HEX"
        echo "  vivo接收: $VIVO_HEX"
    else
        echo "❌ 数据不一致"
        echo "  小米发送: $XIAOMI_HEX"
        echo "  vivo接收: $VIVO_HEX"
        echo ""
        echo "差异分析:"
        # 逐字节对比
        for i in $(seq 0 27); do
            XIAOMI_BYTE=${XIAOMI_HEX:$i*2:2}
            VIVO_BYTE=${VIVO_HEX:$i*2:2}
            if [ "$XIAOMI_BYTE" != "$VIVO_BYTE" ]; then
                echo "  字节 $i: 小米=$XIAOMI_BYTE, vivo=$VIVO_BYTE"
            fi
        done
    fi
else
    echo "❌ 无法对比（缺少数据）"
fi
echo ""

# 总结
echo "======================================"
echo "测试总结"
echo "======================================"
echo ""

SUCCESS=true

# 检查各项指标
if [ "$XIAOMI_COUNT" -gt 0 ]; then
    echo "✓ 模拟器发送数据 ($XIAOMI_COUNT 次)"
else
    echo "❌ 模拟器未发送数据"
    SUCCESS=false
fi

if [ "$VIVO_COUNT" -gt 0 ]; then
    echo "✓ 接收端接收数据 ($VIVO_COUNT 次)"
else
    echo "❌ 接收端未接收数据"
    SUCCESS=false
fi

if [ -n "$XIAOMI_HEX" ] && [ -n "$VIVO_HEX" ]; then
    if [ "$XIAOMI_HEX" = "$VIVO_HEX" ]; then
        echo "✓ 数据传输一致"
    else
        echo "❌ 数据传输不一致"
        SUCCESS=false
    fi
fi

if [ -n "$VIVO_SATS" ]; then
    if [ "$VIVO_SATS" = "12" ]; then
        echo "✓ 卫星数解析正确"
    else
        echo "❌ 卫星数解析错误 ($VIVO_SATS)"
        SUCCESS=false
    fi
fi

echo ""

if [ "$SUCCESS" = true ]; then
    echo "🎉 测试通过！所有检查项均正常"
    exit 0
else
    echo "❌ 测试失败，请检查上述错误项"
    exit 1
fi
