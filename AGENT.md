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
- `DISPLAY_LANGUAGE_OPTIONS` 只放**翻译完整**的语言；条目用各语言自身写法（简体中文 / 繁體中文 / English /
  日本語 / 한국어 / Deutsch / Español / Français / हिन्दी / Русский），不写国家/地区名。
- 现状：10 种语言全部 1092/1092。加一条新文案就要同步 10 个目录，否则该语言覆盖率跌破 100%——
  列表里放的是"已完整"的承诺，别在没补齐时先加进去。
- 批量补翻译的口径：占位符（`%1$s` / `%2$d`）数量和顺序必须与原条目一致；法语等语言的撇号必须写成 `'`；
  裸 `&` / `<` / `>` 要转实体。合并后跑一次 `compile*Resources`（或整包构建）才算验证过。

## 3. Mobile Bridge

- `bindMode` / `port` 只在 `MobileBridgeServer.start()` 读取一次 → 必须重建 server 才生效；
  保存时由 `MobileBridgeService.restartIfRunning()` 触发（`ACTION_RESTART`：stop → 400ms → start，
  留出端口释放时间）。
- 运行时真实绑定状态记在 `MobileBridgeConfig.serverRunning`（`MobileBridgeServer` 在 bind 成功 / stop 时写）。
  **界面上所有"是否在运行"的信号（开关、状态点、API chip）都读它，不要读持久化的 `cfg.enabled`**：
  没有开机/启动时自动拉起桥的逻辑，所以重启 app 后设置值仍是 true 而服务确实没跑，读设置值的开关会
  "显示开着但根本没监听"。设置页状态卡的「目前在 / 拟在」两行同样用它——改了配置但没重启时两行会不一致，
  这是判断"保存时重启是否生效"的依据。
- 桥的**开关在主页**「连接状态」卡片下方（`ui/bridge/MobileBridgeSwitchCard.kt`），拨动直接写设置并
  `MobileBridgeService.start/stop`、立即生效；设置页只留信息展示（端口 / 绑定模式 / 审批模式等仍需按保存生效）。
- `approvalMode` / `allowedCapabilityGroups` / token 是每请求读取，改完即时生效。
- **能力组改动立即生效，不需要重启桥**：`manifestResponse()` / `executeResponse()` 各自现读
  `allowedCapabilityGroups`，`BridgeRegistry.visible()` 当场过滤，没有任何快照缓存；只有 `port` / `bindMode`
  需要"保存 → `restartIfRunning()`"。组检查还排在审批门**之前**：组没开就回 `unsupported_capability`，
  连批准框都不会弹。"看着没生效"通常是调用方缓存了 `/manifest`，重读即可。
- 组归属容易记错：`location.get` 属于 **`location`** 组（不是 `device`）；`screen.screenshot` /
  `screen.screenshot.fetch` 属于 **`screen`** 组（不是 `accessibility`）。

### 3.1 三条执行路径，准入各归谁管

| 路径 | 谁发起 | 通道 | 准入由谁决定 |
| --- | --- | --- | --- |
| ① 节点原生 | OpenClaw 网关 | 网关 WebSocket（`node/ConnectionManager` + `node/InvokeDispatcher`） | **默认全通**：只按 Android 运行时权限声明与执行；`canvas` / `camera` / `screen` 另要求应用在前台 |
| ② 桥接 HTTP | Hermes 侧脚本（任何持 token 的客户端） | 手机上的本地 HTTP（`/manifest`、`/execute`） | 桥接开关 + `allowedCapabilityGroups` + 审批模式 + 运行时权限 |
| ③ 网关 → 桥接 | OpenClaw 网关 | 网关 `bridge.status / manifest / execute / grants / revoke` → 进程内 `MobileBridgeServer.dispatch()` | 同 ②（同一份允许组与审批），另需桥接开关为开 |

- 主页「Android 设备功能」那四个开关（相机 / 位置 / 短信 / 屏幕）**管的是 ②③ 的允许组**：读写同一份
  `allowedCapabilityGroups`，与「设置 → 移动桥接 → 允许的能力组」双向同步（同一 StateFlow）；**不管 ①**。
- Hermes 不是网关，所以 **Hermes 只走 ②**：`backend/PrimaryBackendDispatcher.kt` 里 `HERMES_API_SERVER` /
  `OPENCLAW_HTTP` → `sendViaAgentClient`（纯 HTTP，`HermesApiServerClient` 打 `/v1/chat/completions` 与
  `/v1/runs`，没有 WebSocket）；只有 `OPENCLAW_GATEWAY` 才走节点会话。
- 高风险能力里只剩 `apps.launch` 是已知受限项：它会被 Android 10+ 的后台启动限制静默拦截（BAL），
  只做「看起来成功」的处理。
- 截图已实现，**两段式，不要退回成一段**：`screen.screenshot`（HIGH）只做抓屏 + 缩放 + JPEG，把字节塞进
  `ScreenCaptureStore`（内存、TTL 5 分钟、最多 4 张、≤48MB、不落盘），返回元数据 + `id`；
  `screen.screenshot.fetch {id}`（MEDIUM）才回 base64。把像素内联进第一段会让每个只想判断"画面变没变"的
  调用方都拖一份 base64 过 JSON 桥。抓屏走 `AccessibilityService.takeScreenshot()`（API 30+，
  服务配置必须保留 `android:canTakeScreenshot="true"`），失败时回平台 `errorCode`
  （`3` = 调用太快，平台有频率限制；`6` = 目标页面 `FLAG_SECURE`）—— 别把它吞成通用失败。
  Hermes 侧封装在 `integrations/hermes-mobile-bridge/capture_screen.py`。
- 拍照走 `bridge/camera/CameraCaptureService.kt`（manifest 里 `foregroundServiceType="camera"` +
  `FOREGROUND_SERVICE_CAMERA` 权限）。**别在后台启动它**：Android 12+ 明确禁止「需要 while-in-use 权限的前台服务」
  在应用处于后台时启动，即使别的豁免满足也不行；唯一合法时机是应用有可见界面，所以由批准界面在用户点批准时
  `arm()` 它（`BridgeApprovalActivity`）。授权窗口内保持服务存活（"直到撤销" 上限 12 小时），这样后续拍照不必重新启动。
  服务内部自带 `LifecycleRegistry` 的 `LifecycleOwner`（CameraX 需要），只在拍摄瞬间推到 RESUMED 打开相机，
  拍完退回 CREATED 释放 —— 存活的是服务，不是相机。
  拍照复用 `node/CameraCaptureManager.snap()`（facing/quality/maxWidth + EXIF 旋转 + 限幅压缩），
  结果同样进 `ScreenCaptureStore`，第二段是 `camera.capture_photo.fetch`。错误码：`CAMERA_SERVICE_NOT_ARMED`、
  `CAMERA_TIMEOUT`、`CAMERA_CAPTURE_FAILED`。Hermes 侧封装 `integrations/hermes-mobile-bridge/capture_photo.py`。
- 两个已踩过的坑（都已在真机验证过修复）：①`arm()` 是异步的，批准后能力可能比服务 `onCreate` 先跑 ——
  所以能力里用 `CameraCaptureService.awaitArmed()` 等最多 3 秒，别直接 `get()`（否则第一次调用会误报未武装）；
  ②`keepAliveMs = 0`（一次性批准）时服务必须**拍完自行 `stopSelf()`**，否则通知一直挂着。

## 4. TTS（提供商 / 缺引擎时的提示）

- 提供商：本地（Android 系统 TTS，默认）/ ElevenLabs / **OpenAI 兼容**
  （`speech/OpenAiCompatProvider.kt`：`POST {base}/v1/audio/speech`，地址 / 模型 / 音色可配、API key 可留空）/
  **华为 HMS（ML Kit）**（`speech/HmsTtsProvider.kt`，云端 `TTS_ONLINE_MODE`，单次 ≤450 字切分）/
  VOICEVOX（仅 `full` flavor）。原来那个走自定义 `/tts` 契约的「Rig Qwen」provider 和独立的 OpenAI 条目都已删掉，
  OpenAI 系只留「OpenAI 兼容」一条。
- HMS TTS 只走云端：设备端要 `huawei_module_mlkit_tts_eagle/bee/fly` 动态模块，非华为 ROM 拿不到
  （`errcode:2`、服务端回 `The model does not exist`），别再往设备端方案上返工。
- HMS 的 API key 是**显式使用**：debug 包的默认值来自 `local.properties` 的 `hms.asr.key`
  （`BuildConfig.HMS_ASR_KEY`，`local.properties` 不进仓库），release 包默认空、要求用户自己填；
  provider 只读 `settings.ttsHmsApiKey`，不做隐式回退。
- 诊断文案在 `speech/diagnostics/VoiceDiagnostics.kt`，一律走字符串资源，**别写死英文**（中文界面下曾漏出英文）。
- 「本地 TTS + 系统里一个引擎都没有」→ 弹 `TtsEngineHintDialog`（`ui/settings/SettingsSaveSupport.kt`）：
  正文里的 http(s) 链接由 `linkifyUrls()` 自动标注成可点链接（不要写死字符下标，各语言文案位置不同），
  文案按语言指向对应语言的 sherpa-onnx 引擎包。主页诊断的「去设置修复」在没有引擎时也走同一个弹窗，
  **不跳系统设置**。

## 5. 构建 / 测试 / 装包

```bash
./gradlew assembleStandardDebug        # 产物在 app/build/outputs/apk/standard/debug/
./gradlew testStandardDebugUnitTest    # Robolectric 单测
adb -s <设备地址> install -r <apk>      # 装到真机（debug 包名 com.openclaw.assistant.debug）
```

- **装包优先用 LAN 地址**（如 `10.11.34.138:<端口>`）。走 tailnet 地址（`100.64.x.x:<端口>`）时
  `adb install`（streamed install）会以**空错误信息**失败（`failed to install <apk>:` 后面什么都没有），
  换 LAN 立刻成功；`--no-streaming` 走 tailnet 也会 `device offline`。同一个端口在两种地址上都可连。

- **装包（update）之后系统的语音助理绑定会掉**：`dumpsys voiceinteraction` 里变成 `(No active implementation)`，
  这时主页「语音对话」是**空操作**（服务侧只会把 showSession 挂进 30s 的等待队列，等 onReady，而系统没绑就永远不来）。
  恢复：`adb shell settings put secure assistant <pkg>/com.openclaw.assistant.service.OpenClawAssistantService`
  （写回同一个值就会触发系统重新绑定），或重启手机/在系统设置里重选数字助理。
  App 侧已加前置检查（`ui/HomePreflight`），点按钮前会先查这一项。

- 需要 JDK 17 与 Android SDK；`app/google-services.json` 可从 `.example` 复制并把 `FIREBASE_ENABLED=false`。
- Robolectric 需要 android-all 制品，国内可用 init 脚本把 `robolectric.dependency.repo.url` 指向镜像。
- 单测里**不要**碰 `SettingsRepository`：它基于 `EncryptedSharedPreferences`（AndroidKeyStore），Robolectric 下
  不可用。把纯逻辑抽成可直接调用的函数再测（例：`LocaleAwareActivity.wrapWithLocale`）。

## 6. 字符串资源

- aapt2 不认 `\r` 转义（报 `Invalid unicode escape sequence`），换行只用 `\n`。
- 字符串里的单引号必须写成 `\'`，裸写会构建失败。
- 缺翻译的条目运行时自动回落英文。新增界面文案时同步 `values` / `values-zh-rCN` / `values-zh-rTW`。

## 7. 排查"代码看着对但界面没反应"

- `LocaleAwareActivity.langDiag(context, line)` 同时写 logcat 和 `files/langdiag.log`；
  读法：`adb shell run-as com.openclaw.assistant.debug cat files/langdiag.log`。
- 部分 ROM（含本项目测试机）的 logcat 主缓冲区读出来是空的，`Log.i` 什么都留不下，别在那里浪费时间。
- 核对界面可用 `adb shell uiautomator dump` 读节点树、按 bounds 点按，比猜坐标可靠。

## 8. 不要做的事

- **不要自造上游不存在的协议/格式**。曾经自造过 `agentvoice://setup/<base64url>`（"path 形式"）深链，
  单测只验证了自己写的解析器就当成可用，结果真机上两种形式都报"配对 QR 缺少必要信息"——上游只有 query 形式。
  外部报文格式先按上游已有形式做，并端到端验证整条链。
- 不要把保存后的副作用只写在一个宿主里（见第 1 节）。
- 不要把界面上"是否在运行/是否启用"的信号接到持久化的**设置值**上：桥接一律读 `serverRunning`、
  能力开关一律读 `allowedCapabilityGroups`（见第 3 节）。
- 不要在保存流程里调用 `AppCompatDelegate.setApplicationLocales()`（见第 2 节）。

## 9. 按钮前置检查与错误提示（别让界面"点了没反应"）

- 主页「语音对话」「打开聊天」点下去先过 `ui/HomePreflight`：语音那条查
  麦克风权限 → 语音服务有没有被系统绑定（`VoiceInteractionService.isActiveService`）→
  选中的识别后端有没有填地址/密钥 → 这一轮要走的回复后端在不在；
  聊天那条只查有没有启用的后端。缺什么就用弹窗逐条列出来，并把「去处理」指到对应设置页。
- **移动桥接跟这两条链路无关**，别把它当门口闸：桥是 Hermes 主动调手机的**反向**通道（手机监听 8787）；
  语音和聊天都是手机主动往外连（识别服务、Hermes API Server、网关）。把桥加进前置检查只会误报。
- 错误提示统一带环节：`OpenClawSession.staged()` / `ChatViewModel.staged()` 把文案包成
  `【环节】细节`（环节＝语音识别 / 后端连接 / 配置）。新增错误分支时照这个包一层，
  只丢一个错误码（比如 `HTTP 502`）用户看不出是哪一环的问题。

## 10. 语音链路：后端怎么选、提示词怎么发

- **「语音会话连接」只服务语音**：`wakewordConnectionType`（网关 / Hermes HTTP）。没手动设置过时按**主后端**推导
  （`OPENCLAW_GATEWAY` → 网关；`HERMES_API_SERVER` / `OPENCLAW_HTTP` → HTTP；一个后端都没有时维持网关）。
  主页「语音对话」标题下面那两个 chip 和设置里是同一个键，改完下一条消息就生效。
- **文本聊天不看这个设置**：聊天只看会话选的后端类型，而 `OPENCLAW_GATEWAY` 这个类型没有 HTTP 兜底
  （`sendViaGateway` → `runtime.sendChat`，网关没连上直接报 `OpenClaw Gateway is not connected`）。
  所以「主后端 Hermes + 连接方式网关」在语音上是必然失败的组合，点按钮前会弹提示（`HomePreflight.hermesOnGatewayConflict`）。
- **两套提示词**：`backend/PromptDefaults.kt` 的 `VOICE` / `CHAT` 是默认值，设置 → 聊天 里的两个框可改，
  **留空 = 用默认**（保存时若内容与默认一致会写空串，这样以后默认值更新还能吃到）。
- 语音请求还会拼上 `backend/VoiceContext.kt` 的现场环境（时间 / 位置 / 电量 / 网络 / 屏幕），
  所以语音回答可以直接说「现在几点」「你手机还有多少电」，不需要再反查用户。
- **Runs API 必须用 `instructions`**：`/v1/runs` 只读 `input`（字符串）+ `instructions`，`input` 数组里的
  `system` 角色会被整个丢掉。`HermesApiServerClient.buildRunsRequestBody()` 会把 system 消息提到
  `instructions` 顶层字段，`HermesApiServerClientTest` 里有两个断言锁住这件事——改传输层时别把提示词又弄丢。

## 11. 定时任务（Hermes cron）

- 列表 / 新建 / 编辑走 `backend/HermesConfigApi.kt` 的 `/api/jobs`（要 gateway API key）。
- Hermes 的 job 支持 **`script`**（相对 `HERMES_HOME/scripts/` 的文件名，服务器会校验，绝对路径和 `../` 都拒），
  有脚本时结果由脚本产出、`prompt` 可为空。`JobEditDialog` 里那一行只在 Hermes 后端显示（OpenClaw 没有脚本概念）。
- `HermesCronSchedule.expr` 必须可空：`interval` / `once` 任务没有 `expr`，声明成非空会让**整个列表**反序列化失败。

## 12. 深色模式

- 应用主题是 `ui/theme/OpenClawAssistantTheme`：深色用品牌深色方案，浅色用同色系浅色方案，默认跟随系统。
- **所有 Activity 的 `setContent` 都要包这个主题**。裸 `MaterialTheme {}` 是 M3 默认浅色，永远不跟系统走
  （后端管理列表 / 编辑器曾因此一直是白的）。
- 设置向导（`ui/SetupGuideScreen.kt`）自带走一套 onboarding 配色（渐变 + 白卡片），不走主题；
  它的 token 现在按 `isSystemInDarkTheme()` 取 `Onboarding*Dark`，新增颜色时两边都要加。

## 13. 语音退出词

- 实现：`speech/ExitPhrases.kt`，在 `OpenClawSession.sendToOpenClaw()` 入口判断——识别出最终文本、
  还没发给后端的那一刻。命中就 `playTone` + `finish()`，这条话不会进聊天记录。
- 匹配口径故意保守：归一化（小写、去空白标点、去句尾语气词"吧/了/啊/呀/呗"）后**整句等于**某个退出词。
  "退出。""退出吧"会退，"退出这个应用怎么弄"不会。
- 默认词按**界面语言**取（`exit_phrases_default` 资源），用户自定义存在 `exitPhrasesRaw`，留空即回落默认。
- 设置入口在「设置 → 唤醒词」，输入框用逗号 / 顿号 / 换行分隔，占位提示显示当前语言的默认值。
