# video-storage-cleanup Delta Specification

> 修改 capability(change `video-segment-schema` ②a):多段模型废止"重录覆盖前删旧文件";清理语义平移到全段。

## MODIFIED Requirements

### Requirement: 重录覆盖前删除旧视频文件

**(②a 废止原行为)** `attachVideoToSession` 改为 append 语义(见 `video-segment-model` capability):同 session 再次 attach SHALL 新增 segment 行,**MUST NOT 删除任何旧段文件**——旧段是 `video_segments` 表登记的合法数据(停录再录 / ERROR 救援重录都是用户要保留的画面,2026-06-03 路测"圈 1 救援段被尾段覆盖"即原行为的事故)。原"从源头杜绝重录孤儿"诉求由以下承接:旧段有行引用不再是孤儿;用户主动清理走"成绩页删视频(全段)";session 删除走 deleteSession 全段 cascade。

#### Scenario: 同 session 重录两段都保留(原"删旧"scenario 语义反转)

- **WHEN** session 已有 segment(path=A),attach 新路径 B(B≠A)
- **THEN** 文件 A MUST 仍存在,`video_segments` 含 A、B 两行,session 旧字段=B(最新段)

#### Scenario: 首次 attach 无旧段(行为不变)

- **WHEN** session 无任何 segment,attach 路径 B
- **THEN** 新增 segmentIndex=0 行,MUST NOT 因无旧段报错

#### Scenario: 反例——删旧逻辑残留即数据丢失

- **WHEN** 实现残留"查旧 path → 删文件"分支(grep `attachVideoToSession-replaceOld` 命中 >0)
- **THEN** 重录场景静默删除合法旧段——测试断言"old file kept on re-record"失败
