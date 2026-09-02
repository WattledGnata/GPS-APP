# BlazePush v1.0.14

发布日期：2026-09-02

定位：修复圈速记录页 Session 历史展示数量受限的问题。建议直接覆盖安装旧版本，不要卸载，以保留历史 Session、圈速、性能成绩和录像关联数据。

## 本次更新

- Records → LAPS 的 Session History 不再只读取最近 5 条记录。
- Session 历史列表现在会完整展示当前赛道已有记录；超过一屏时可以纵向滚动查看更早的 Session。
- 保持原有 Session 排序、点击进入详情和历史数据结构不变。

## 使用建议

1. 请直接覆盖安装，不要卸载旧版本。
2. 打开 Records → LAPS，选择记录超过 5 条的赛道。
3. 在 Session History 区域向上滑动，即可查看更早的历史记录。

## 验收边界

- `feature:test` 共执行 748 个 JVM 单元测试：747 通过、1 跳过、0 失败。
- 定向验证覆盖 7 条历史 Session 全量输出，以及长列表页面的纵向滚动契约。
- Release 构建、APK manifest 和正式签名校验通过。
- 本轮发布时 vivo 未连接，因此 1.0.14 Release 覆盖安装和真机滚动验收待补；此前 vivo 上的 1.0.13 Debug 验证不作为本版本的 Release 真机证明。

## 安装包身份

- 应用 ID：`com.blazepush`
- 版本名：`1.0.14`
- versionCode：`15`
- SHA-256：`3291bc20afd27d2acd4bc8b2659c2a70cdb2ba0980738277021473173910020e`
