# Release 发布清单

每次生成并对外发布新的 Release APK，按以下顺序执行：

1. 核对本次发布范围，避免混入本地配置、探针和无关工具。
2. 在 `app/build.gradle` 中严格递增 `versionCode` 和 `versionName`。
3. 构建 `:app:assembleRelease`，核对 APK 包名、版本、文件名和正式签名。
4. 运行本次改动覆盖到的单元测试，并记录 APK SHA-256。
5. 提交 Release 对应的代码和验证记录。
6. 在 Release 提交上创建 `release+<versionName>` Git tag。
7. 推送当前分支及 Release tag；确认远端分支和 tag 均可见。
8. 上传分发平台，并在最终下载页核对版本和更新说明；仅上传成功不等于发布完成。

同一个已构建 APK 上传到新的分发渠道不算重新打包，无需再次递增版本或创建新 tag。
