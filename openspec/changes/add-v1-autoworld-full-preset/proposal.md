## Why

天津 V1 国际赛车场 4.29 km 完整布局已有用户实测 GPS、RaceChrono 赛道定义与同场 MYLAPS 官方成绩完成交叉验证，但 App 尚无可离线选择的正式预置。现需将经六个完整圈最小方差拟合的起终点和自有 GPS 轨迹固化为预置，同时避免把属于 2.4 km 布局的 RaceChrono 分段门误用于完整布局。

## What Changes

- 新增“天津V1国际赛车场”4.29 km 完整布局预置，使用稳定 ID、官网正式名称和自有 GPS referencePath。
- 起终点使用沿原 RaceChrono 行驶方向反向移动约 149.6 m 的最小 RMSE 拟合线；六圈相对官方 MYLAPS 的最大整圈残差不超过 55 ms。
- 4.29 km 预置暂不声明 S1/S2：原 RCZ 两个 Split 归属 2.4 km 布局，不能冒充完整布局官方分段。
- 新增符合 TrackTech Cyan/深色视觉体系的本地 VectorDrawable 缩略图，并更新 debug/release 目录及资源契约测试。
- 宁波预置名称核对为官网正式“宁波国际赛道 / Ningbo International Circuit”，保持现值。

## Capabilities

### New Capabilities

- `v1-autoworld-full-preset`: 天津 V1 国际赛车场 4.29 km 完整布局的身份、拟合起终点、参考轨迹与离线可用性契约。

### Modified Capabilities

- `track-presentation`: 赛道列表应使用天津 V1 专属静态矢量缩略图，并保持既有资源优先级和 TrackTech 视觉。

## Impact

- `feature/test/src/main/java/.../repository/PresetTracks.kt`：增加天津 V1 完整布局预置。
- `feature/test/src/main/res/drawable/`：增加天津 V1 VectorDrawable。
- `feature/test/src/test*/`：增加身份、几何、目录顺序与资源接线测试。
- 不修改 RaceChrono BLE 公共协议，不修改 simulator，不接入服务端下发，不引入网络或第三方图片依赖。
