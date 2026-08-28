## ADDED Requirements

### Requirement: 首次打开一次性车手名引导

用户**首次**打开 app(首次到达主页且 `hasShownDriverNamePrompt == false`)时,系统 SHALL 弹出一次性 dialog 询问是否设置车手名(说明用于 livetiming 榜单展示),提供两个路径:`去设置`(导航到 `SettingsScreen` 车手名输入)与 `以后再说`(关闭)。dialog 一旦弹出,系统 SHALL 立即将 `hasShownDriverNamePrompt` 置为 `true` 并持久化(`UserProfileRepository` DataStore),使其**只弹一次**——后续任何启动 MUST NOT 再弹,与用户选了哪个按钮、是否真的设了车手名**无关**。

#### Scenario: 首次打开弹出并置 flag

- **GIVEN** `hasShownDriverNamePrompt == false`(全新安装 / 从未弹过)
- **WHEN** 用户首次到达主页
- **THEN** 弹出车手名引导 dialog(含 `去设置` / `以后再说`)
- **AND** `hasShownDriverNamePrompt` 被置为 `true` 并持久化

#### Scenario: 反例锁——已弹过再开 MUST NOT 再弹

- **GIVEN** `hasShownDriverNamePrompt == true`(之前已弹过)
- **WHEN** 用户再次打开 app 到达主页
- **THEN** **不** 弹出引导 dialog(只弹一次的硬约束;违反 = 每次启动都弹)

#### Scenario: 去设置路径导航且不再弹

- **GIVEN** 引导 dialog 已弹出
- **WHEN** 用户点 `去设置`
- **THEN** 导航到 `SettingsScreen`(车手名输入)
- **AND** `hasShownDriverNamePrompt == true`(下次启动不再弹)

#### Scenario: 以后再说关闭且不再弹

- **GIVEN** 引导 dialog 已弹出
- **WHEN** 用户点 `以后再说`
- **THEN** dialog 关闭,不导航
- **AND** `hasShownDriverNamePrompt == true`(下次启动不再弹;即便用户始终没设车手名也不再打扰)
