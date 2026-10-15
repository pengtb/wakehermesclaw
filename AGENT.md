# AGENT.md — 给编码 agent 的工作说明

本仓库是 [OpenClaw Assistant](https://github.com/yuga-hashimoto/openclaw-assistant) 的 fork（MIT 许可）。
分支 `hermes-integration` 放的是"接通 Hermes Agent 后端 + 顺手修 bug"的改动，`main` 与上游保持同一点。
给使用者的说明在 [README.md](README.md)；本文只写 agent 需要、但不适合放进 README 的东西：
架构事实、容易犯的错误、验证手段。

## 1. 设置界面有两个宿主（最容易踩）

`SettingsScreen`（`SettingsActivity.kt` 里的顶层 composable）被两处渲染：

| 宿主 | 位置 | 用户实际走的 |
| --- | --- | --- |
| `MainActivity` 的「设置」标签页 | `MainActivity.kt` 里的顶层 composable `MainNavHost()` | 是 |
| `SettingsActivity` | `SettingsActivity.kt` | 少见 |

把保存后的副作用（提示弹窗、重启服务）只写在其中一个宿主的 `onSave` 里，另一个入口按保存就"什么都没发生"。
**规则**：保存后的收尾一律走 `ui/settings/SettingsSaveSupport.afterSave()`；重启确认弹窗用
`ui/settings/LanguageRestartDialog`。注意 `MainNavHost` 是顶层 composable，Activity 成员
（如 `appliedLanguageTag`）在里面不可见，要用 `LocalContext.current as? LocaleAwareActivity`。

历史教训：移动桥接的"保存时重启"和显示语言的"是否立即重启"都因为只写在一个宿主里而失效过。

## 2. 界面语言：怎么实现的，以及不要怎么做

- 所有 Activity 都继承 `ui/LocaleAwareActivity`，在 `attachBaseContext` 里按 `SettingsRepository.appLanguage`
  应用一次（空值 = 跟随系统）；`appliedLanguageTag` 记录创建时生效的标签。
- **不要用 `AppCompatDelegate.setApplicationLocales()` 做语言落地**（保存流程里尤其不要）：API 33+ 上它会写
  系统级 per-app locale 并立刻触发配置变更、重建当前界面，刚设上的 Compose 状态会落在已销毁的 composition 上
  （表现为弹窗永不出现）。`OpenClawApplication` 里只在进程启动时同步一次。
- 语言只在界面重新创建时生效，因此保存后询问"是否立即重启"，确认后 `LocaleAwareActivity.restartIntoMain()`
  清任务栈回主界面。
- `DISPLAY_LANGUAGE_OPTIONS` 只放**翻译完整**的语言；条目用各语言自身写法（简体中文 / 繁體中文 / English），
  不写国家/地区名。

## 3. Mobile Bridge

- `bindMode` / `port` 只在 `MobileBridgeServer.start()` 读取一次 → 必须重建 server 才生效；
  保存时由 `MobileBridgeService.restartIfRunning()` 触发（`ACTION_RESTART`：stop → 400ms → start，
  留出端口释放时间）。
- 运行时真实绑定状态记在 `MobileBridgeConfig.serverRunning`（`MobileBridgeServer` 在 bind 成功 / stop 时写），
  设置页状态卡用它显示「目前在 / 拟在」两行。改了配置但没重启时两行会不一致——这是判断"保存时重启是否生效"的依据。
- `approvalMode` / `allowedCapabilityGroups` / token 是每请求读取，改完即时生效。
- 能力组改了"看起来没生效"，通常是 Hermes 侧缓存的 `/manifest` 过期，重读 manifest 即可，不要重启桥。
- 高风险能力（相机、屏幕截图）在上游是占位实现，`/manifest` 里不列；`apps.launch` 会被 Android 10+
  的后台启动限制静默拦截。

## 4. 构建 / 测试 / 装包

```bash
./gradlew assembleStandardDebug        # 产物在 app/build/outputs/apk/standard/debug/
./gradlew testStandardDebugUnitTest    # Robolectric 单测
adb -s <设备地址> install -r <apk>      # 装到真机（debug 包名 com.openclaw.assistant.debug）
```

- 需要 JDK 17 与 Android SDK；`app/google-services.json` 可从 `.example` 复制并把 `FIREBASE_ENABLED=false`。
- Robolectric 需要 android-all 制品，国内可用 init 脚本把 `robolectric.dependency.repo.url` 指向镜像。
- 单测里**不要**碰 `SettingsRepository`：它基于 `EncryptedSharedPreferences`（AndroidKeyStore），Robolectric 下
  不可用。把纯逻辑抽成可直接调用的函数再测（例：`LocaleAwareActivity.wrapWithLocale`）。

## 5. 字符串资源

- aapt2 不认 `\r` 转义（报 `Invalid unicode escape sequence`），换行只用 `\n`。
- 字符串里的单引号必须写成 `\'`，裸写会构建失败。
- 缺翻译的条目运行时自动回落英文。新增界面文案时同步 `values` / `values-zh-rCN` / `values-zh-rTW`。

## 6. 排查"代码看着对但界面没反应"

- `LocaleAwareActivity.langDiag(context, line)` 同时写 logcat 和 `files/langdiag.log`；
  读法：`adb shell run-as com.openclaw.assistant.debug cat files/langdiag.log`。
- 部分 ROM（含本项目测试机）的 logcat 主缓冲区读出来是空的，`Log.i` 什么都留不下，别在那里浪费时间。
- 核对界面可用 `adb shell uiautomator dump` 读节点树、按 bounds 点按，比猜坐标可靠。

## 7. 不要做的事

- **不要自造上游不存在的协议/格式**。曾经自造过 `agentvoice://setup/<base64url>`（"path 形式"）深链，
  单测只验证了自己写的解析器就当成可用，结果真机上两种形式都报"配对 QR 缺少必要信息"——上游只有 query 形式。
  外部报文格式先按上游已有形式做，并端到端验证整条链。
- 不要把保存后的副作用只写在一个宿主里（见第 1 节）。
- 不要在保存流程里调用 `AppCompatDelegate.setApplicationLocales()`（见第 2 节）。
