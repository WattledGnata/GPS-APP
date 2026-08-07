# BlazePush v1.0.9

发布日期：2026-08-07

定位：CTCC 现场测试版。建议直接覆盖安装旧版本，不要卸载，以保留历史 Session 和圈速数据。

## 本次更新

- 点击 START 后立即创建并保存 Session；GPS 尚未就绪时仍可先启动视频，计时继续严格等待可靠 GPS。
- 强化 BLE 自动重连：支持 App 回前台、进入圈速 Session、发现已保存设备、手机蓝牙重新开启等立即重试，并保留 1/2/4/8/16/30 秒退避。
- 每次重连重新完成 GPS Main、GPS Time 和 Battery 握手；只有新代次 Main + Time 基准和后续稳定 Main 能恢复有效计时。
- Battery 状态拆分为等待、可用、不支持和失败，不再影响 GPS 计时通道。
- 起终点可靠穿线后 5 秒内在 IO 线程刷盘，App 进入后台时立即刷盘；异常退出后可修复遥测文件尾部并收尾旧 Session。
- 新增 Clean / Reviewed / Estimated / Incomplete 圈速置信度。最佳圈、语音、比较和上传统一按同一质量策略消费。
- 缺口不会自动作废整圈；只有缺口覆盖起终点/必要扇区或存在跨缺口补线风险时才降级。
- 新增可导出的诊断时间线：BLE、connection generation、Main/Time、stale/RX age、相机、生命周期和 Battery 状态。

## 现场使用建议

1. 从旧版本升级时直接覆盖安装，不要清除数据。
2. 出发前打开手机蓝牙、GPS 设备和 App，确认页面显示 Main/Time 已恢复且 GPS 已进入 ARMED。
3. GPS 暂时未就绪也可以先点 START 开始 Session 和视频；首个完整圈从后续可靠起终点穿线开始。
4. 出现异常时保留 App 数据，并从诊断页面导出/上传日志。

## 验收边界与已知限制

- 已通过 1103 项 JVM 自动化测试，0 失败；release lint 0 error。
- Room v9→v10 迁移 SQL 已做自动化和 SQLite 验证，instrumentation 测试 APK 已编译；本版本未在 Android 设备上实际运行该 instrumentation。
- 当前没有指定 BLE GPS 硬件在手，本版本未完成实车路测、超距返回、GPS 关机重开、手机蓝牙关开和相机伴随 Main gap 的真机闭环。
- 非 Clean 圈目前保留在本地，不上传到服务端，避免服务端丢失质量标识。

## 文件校验

下载页同时提供 APK 的 SHA-256 校验文件。安装包应显示：

- 应用 ID：`com.blazepush`
- 版本名：`1.0.9`
- versionCode：`10`
- SHA-256：`6dbf5d80db8fd8ae8b81dc66e78d653654349b9acee755b71461fb95fb7fa7c8`
