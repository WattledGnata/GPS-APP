# Tasks: fix-crossing-events-write-amplification

## 1. 实现

- [x] 1.1 锚点:`grep -n "toWrite.forEach" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`(~line 933)。
- [x] 1.2 internal 纯函数 `shouldPersistCrossing(crossing): Boolean` = `accepted || reason != CrossingReason.NoIntersection`;写入循环 `toWrite.filter(::shouldPersistCrossing)`;游标推进保持未过滤 size。
- [x] 1.3 单测:6 reason × accepted 组合 + spec 三场景谓词级断言。
- [x] 1.4 `:feature:test:testDebugUnitTest` 全绿;自审 #14/#16 空命中记录(消费方核查见 proposal)。

## 10. Follow-up backlog

- 引擎 in-memory `session.crossingEvents` 全量累积(2h ≈ 18 万对象):改 R4 契约面大,观察内存压力再立项。
