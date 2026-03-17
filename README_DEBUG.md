# GPS蓝牙数据传输 - 临时调试说明

## 🔧 快速开始（明早测试）

### 1. 连接设备
```bash
adb devices
```
确认两台手机都已连接

### 2. 一键部署
```bash
./deploy.sh
```

### 3. 开始测试
```bash
./test_ble_data.sh
```

### 4. 查看结果
```bash
./analyze_logs.sh
```

## ✅ 预期结果

- **卫星数**: 12（不再是60-0循环）
- **频率**: 10.0 Hz（不再是0.0）
- **速度**: 根据设置正常显示
- **数据**: 双方hex完全一致

## 📚 详细文档

- `TESTING_CHECKLIST.md` - 完整测试清单
- `FIX_SUMMARY.md` - 修复总结
- `TESTING_GUIDE.md` - 详细测试指南

## 🐛 已修复的问题

1. ✅ 数据格式从20字节改为28字节
2. ✅ 字节序从小端序改为大端序
3. ✅ 字节偏移完全修正
4. ✅ 卫星数显示错误（60-0循环）
5. ✅ 添加详细调试日志

## 📝 最近提交

```
b0556f1 docs: 添加测试检查清单
5813d50 docs: 添加修复总结文档
63bcf91 test: 添加蓝牙数据传输自动化测试脚本和文档
1cdbb1a fix: 修复GPS模拟器和接收端为28字节大端序RaceChrono协议
```

## 🚨 如果遇到问题

1. 查看 `TESTING_GUIDE.md` 的故障排查章节
2. 检查 `xiaomi_simulator.log` 和 `vivo_receiver.log`
3. 确认双方hex数据是否一致
4. 运行 `./analyze_logs.sh` 自动诊断

---

**祝测试顺利！有问题随时查看文档。**
