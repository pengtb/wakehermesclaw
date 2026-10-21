# WakeHermesClaw

把手机变成一个随时能唤起的 AI 助手：说唤醒词、点快捷设置磁贴、长按导航条，或者按一下耳机键，语音面板就会升起来；说的话交给你在电脑上跑的后端（Hermes Agent 或 OpenClaw），回答念出来。除了说话，助手还能反过来操作这台手机——读屏、点按、拍照、发短信、定位。

这是在 [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant) 基础上做的自用分支，主要工作是接上 Hermes Agent，并把语音链路、后端选择、手机控制做完整。

- 最新版本：v2.5.2（[Releases](https://github.com/pengtb/wakehermesclaw/releases)）
- 系统要求：Android 8.0 及以上（minSdk 26），targetSdk 34
- 界面语言：简体中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Español / Français / हिन्दी / Русский

[English](README.en.md) · [繁體中文](README.zh-TW.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [Français](README.fr.md) · [हिन्दी](README.hi.md) · [Русский](README.ru.md)

---

## 截图

| 主页 | 语音对话 | 会话 |
| :---: | :---: | :---: |
| <img src="docs/images/home.jpg" width="220"> | <img src="docs/images/voice_overlay.jpg" width="220"> | <img src="docs/images/conversations.jpg" width="220"> |
| **画布** | **设置** | **聊天** |
| <img src="docs/images/canvas.jpg" width="220"> | <img src="docs/images/settings.jpg" width="220"> | <img src="docs/images/chat.jpg" width="220"> |

---

## 它做什么

### 语音对话

- **四种唤起方式**：唤醒词、快捷设置磁贴、系统助理手势（长按导航条 / 电源键）、应用静态快捷方式；也可以直接点开 App 里的「语音对话」。
- **连续对话**：答完自动继续听；也可以切成对讲模式——点一下开始说，再点一下结束并发送。
- **退出词**：说「退出」「exit」这类词直接结束对话，默认词随界面语言变化，可以在设置里自己改。
- **面板不挡通知**：语音面板是贴底的卡片，顶部状态栏留给系统，对话过程中照样能下拉通知栏、点审批。
- **进行中的状态**：对话期间状态栏保留一条通知，点它回到对话面板；面板在前台显示「语音对话进行中」，切到后台变成「点按回到语音对话」，对话结束通知自动消失。

### 语音识别（STT）

四种后端，按需选择：

| 后端 | 说明 |
| --- | --- |
| 系统识别 | 用手机自带的识别服务，零配置 |
| OpenAI 兼容 | 自己起一个 `/v1/audio/transcriptions` 服务，批式上传 |
| FunASR 流式 | 本地 FunASR，边说边出字 |
| 华为 HMS（ML Kit） | 设备侧识别，需要 HMS Core 和 AGC 的 API Key；**只支持中文和英文** |

识别链条上有两个兜底：整段没有字时的静音超时，以及华为 SDK 在 final 帧不回调时由客户端补一次收尾。

### 语音合成（TTS）

| 提供商 | 说明 |
| --- | --- |
| 本地（系统 TTS） | 默认；未显式选引擎时优先 Google TTS，其次是系统默认 |
| OpenAI 兼容 | `POST {base}/v1/audio/speech`，地址、模型、音色可配 |
| 华为 HMS（ML Kit） | **云端**合成，需要联网和 AGC API Key，每月前 100 万字符免费；只支持中文和英文 |
| ElevenLabs | 音色列表随语言本地化 |
| VOICEVOX | 仅 `full` 构建包含 |

系统里一个 TTS 引擎都没有时会弹窗给两条路：装 Google TTS，或装开源的 sherpa-onnx 引擎。

### 后端与连接方式

支持三类后端，语音和聊天各自独立选择走哪条：

| 类型 | 协议 | 用途 |
| --- | --- | --- |
| Hermes Agent | HTTP（`/v1/runs` 或 `/v1/chat/completions`） | 主用；支持系统提示词、流式输出、会话 |
| OpenClaw 网关 | WebSocket 节点协议 | 手机作为节点接入，网关能直接下发工具调用 |
| OpenClaw HTTP | OpenAI 兼容 HTTP | 不需要长连接的轻量通道 |

- **语音会话连接**三选一：`OpenClaw 网关` / `OpenClaw HTTP` / `Hermes HTTP`，主页「语音对话」顶部和设置里各有一份；没手动选过时跟随主后端。
- **文本聊天**看会话选的后端类型，不看上面这个设置。
- **提示词**：语音和文字聊天各一套系统提示词，可在「设置 → 聊天」里改，留空回落到内置默认。
- **语音会带上现场信息**：时间、位置、电量、网络、屏幕状态随每次语音请求一起发，助手能直接回答「现在几点」「手机还有多少电」。
- **服务自愈**：语音服务与系统的绑定掉了会三级重试——确认默认助理、重新请求系统绑定、最后重启进程。

### 手机控制（Mobile Bridge）

手机自己起一个本地 HTTP 服务（默认 8787），Hermes 通过它反向调用手机能力：读屏与界面操作（`screen.tap/swipe/long_press/drag/back/home`、`screen.find_nodes`、`screen.window.describe`）、截屏（两段式，JPEG）、拍照、定位、短信、联系人、日历、通知读取与清除、剪贴板、媒体控制、音量与亮度、应用列表与启动。

- 一次性配对：App 里开启桥接后出现 6 位配对码，电脑端用配对码换 token，之后免配对。
- 授权与审批：按能力分组授权，高风险动作（发短信、拍照、界面操作）走确认弹窗，另有速率限制。
- 只建议在局域网或 Tailscale 里开；绑定模式要选 LAN/VPN 才连得进来。
- **它是可选件**：走 OpenClaw 网关时，网关本身就能下发手机能力，桥接不用开。

### 其它

- **终端**：内置终端连电脑上的 Hermes dashboard，需要 `hermes dashboard --tui`。
- **定时任务**：查看、新建、编辑 Hermes 的 cron 任务，支持填脚本（相对 `HERMES_HOME/scripts/`）。
- **诊断**：主页和设置里都有前置检查，缺权限、语音服务没绑定、后端没配、无障碍没开都会逐条列出来并给去处。
- **深色模式**：全部界面跟随系统深浅色，包括设置向导。
- **10 种界面语言**：每种语言 1092 条文案全齐，不会混英文。

---

## 安装

从 [Releases](https://github.com/pengtb/wakehermesclaw/releases) 下载 APK 直接安装（包名 `com.openclaw.assistant`）。

装完在 App 里做三件事：

1. **添加后端**：「设置 → 连接」里填 Hermes 的地址和 token（或 OpenClaw 网关的主机 / 端口）。
2. **设为数字助理**：把本应用设为默认助理，否则长按导航条唤不起来。
3. **给权限**：麦克风必需；定位、相机、短信、通讯录、日历按你要用的能力给；想做手机控制再开无障碍服务。

语音识别和合成默认用系统能力，不配置也能出声、能识别。

---

## 配对

### Hermes 后端

`integrations/agentvoice-pair/` 里有一个桌面小工具，PowerShell 与 bash 两版等价、零依赖（二维码编码和 PNG 写入都在脚本里）。它会探测本机的 Hermes API 地址（优先 Tailscale Serve，其次局域网）、API key，以及 dashboard 的 Terminal 地址与 session token，生成一张配对二维码：

```bash
bash install.sh                # 装成 agentvoice-pair；Windows 下会装 .ps1 并生成 .cmd 包装
agentvoice-pair --open --svg   # 生成二维码并打开
```

二维码里是一条深链：

```
agentvoice://setup?hu=<API地址>&hk=<key>&hm=<profile>&hr=1&hs=1&htu=<dashboard地址>&htt=<session token>
```

`hu` / `hk` 是 Hermes 地址与 API key，`hm` 是目标 profile，`hr=1` 表示默认走 Runs API，`hs=1` 表示流式，`htu` / `htt` 是终端用的 dashboard 地址与 session token。`hu` 可以重复给出，多个地址会作为候选并发测速。手机端三条路都行：系统相机扫码（点开链接回到 App）、App 内扫码、或者在设置向导里直接粘贴这段 JSON / 深链。

二维码里含 API key 与 session token，等同密码，不要外发。

### Mobile Bridge

1. 手机上打开「设置 → 移动桥接」，开启后页面显示 6 位配对码，以及一条 `agentvoice://pair?u=<桥接地址>&c=<配对码>` 深链。
2. 电脑上执行 `agentvoice-pair --code <配对码> --write-env <路径>`，或直接 `POST {"code":"<配对码>"}` 到 `http://<手机>:8787/pair`。
3. 换回来的 token 写进 `.env` 的 `AGENT_VOICE_BRIDGE_URL` / `AGENT_VOICE_BRIDGE_TOKEN`；之后每次调用都在 `Authorization` 头里带上 `Bearer` 和这个 token。

配对码一次性、带有效期。拿到 token 后不再需要配对，但发短信、拍照、点按这类高风险能力仍然逐次弹批准。

---

## 从源码构建

需要 JDK 17 和 Android SDK 35。

```bash
./gradlew assembleStandardDebug     # 调试包（包名多一个 .debug，可与正式包共存）
./gradlew assembleFullDebug         # 含 VOICEVOX 的调试包
./gradlew assembleStandardRelease   # 正式包
```

产物在 `app/build/outputs/apk/<flavor>/<buildType>/`。

几个构建上的注意点：

- `app/google-services.json` 不入库。没有真实 Firebase 配置时要用 `FIREBASE_ENABLED=false` 出包，否则会拿 mock 配置初始化 Firebase。
- release 构建需要本地签名配置（`local.properties` 里的 `storeFile / storePassword / keyAlias / keyPassword`）。
- release 构建要把 `uploadCrashlyticsMappingFileStandardRelease` 排除掉，否则会去连 Firebase。
- R8 需要放过 HMS SDK 引用的设备侧类（`com.huawei.libcore.*`、`org.chromium.net.*`），规则已在 `app/proguard-rules.pro`。
- 华为的 key 从 `local.properties` 的 `hms.asr.key / hms.asr.appid / hms.asr.cert` 注入 `BuildConfig`，不入库；release 包不含 TTS 的 key，需要用户在 App 里自己填。

---

## 代码结构

```
app/src/main/java/com/openclaw/assistant/
├── speech/      识别（系统 / OpenAI 兼容 / FunASR / HMS）、合成（本地 / ElevenLabs / OpenAI 兼容 / HMS / VOICEVOX）、退出词
├── backend/     后端抽象与派发：Hermes HTTP、OpenClaw 网关、OpenClaw HTTP
├── service/     语音交互服务与会话面板、唤醒词、磁贴、通知、自愈
├── bridge/      Mobile Bridge：HTTP 服务、能力实现、无障碍、审批与授权
├── node/        网关节点协议与运行时
├── ui/          主页、聊天、设置、终端、定时任务、诊断
└── data/        设置与本地存储
```

- `AGENT.md` 是给维护者看的：踩过的坑、为什么这么写、哪些地方一改就会静默失效。
- `docs/hermes-mobile-bridge.md` 记录桥接协议与配对流程。
- `integrations/` 放桌面端的配对工具。

---

## 相对原版改了什么

上游是 `yuga-hashimoto/openclaw-assistant`。这个分支在它上面做的改动，按功能分组：

**Hermes 后端**

- 把 Hermes Agent 接成一等后端，`/v1/runs` 与 `/v1/chat/completions` 都能用，会话、流式、系统提示词都通。Runs API 的 system 消息必须放在顶层 `instructions`（`input` 里服务端不读），这点在客户端补齐了。
- 连接方式三选一：OpenClaw 网关 / OpenClaw HTTP / Hermes HTTP，主页和设置各放一份，按选择严格匹配，不再静默回退。
- 地址没写 `/v1/` 时自动补全，修掉旧配置迁移后一发消息就 404。

**语音**

- 识别改成可插拔多后端：系统 / OpenAI 兼容批式 / FunASR 流式 / 华为 HMS，移除原来的百度后端。
- 新增华为 HMS 云端合成；本机一个 TTS 引擎都没有时给安装引导。
- 语音与文字聊天的系统提示词分开、可改，留空回落默认。
- 语音请求自动带上时间、位置、电量、网络、屏幕状态。
- 退出词：说中即结束当前会话，默认词随界面语言。
- 唤起多三条路：快捷设置磁贴、`VOICE_COMMAND` 跳板、静态快捷方式。
- 语音服务与系统的绑定掉了会三级自愈：确认默认助理 → 重新请求绑定 → 重启进程。
- 面板改成贴底卡片，不再遮通知栏；对话中的通知随前后台换文案，点按回到面板，结束即消失。

**手机控制**

- 截图与拍照都做成两段式：先回元数据与短时 id，再按 id 取 base64；图像只存内存，5 分钟过期，不落盘。
- 拍照用「批准即武装」的相机前台服务，绕开 Android 12 起不能在后台启动相机服务的限制。
- 发短信改为等 `sentIntent` 结果再返回，给的是真实结果码。
- 无障碍手势改在主线程派发并等回调，修掉 tap / swipe 静默失效；`screen.window.describe` 按活动窗口取，包名不再错报。
- 能力分组新增 `location` / `screen`；桥接状态卡分「目前在 / 拟在」两行，改完配置能直接对照。

**日常使用**

- 主页、设置、向导三处都有前置检查，覆盖运行时权限与无障碍服务，每条都带「去处理」。
- 10 种界面语言全部 1092 条译完，语言列表里可选。
- 深色模式覆盖设置向导与后端管理页。
- 终端补了 dashboard 地址与 session token 字段；定时任务补脚本行，并修掉 interval / once 任务因 `expr` 必填导致整个列表反序列化失败的问题。
- 设置页底部显示构建短哈希，同一版本号下的不同产物能分开认。

改动明细可以对着 `git log` 看；架构上的取舍与踩过的坑写在 `AGENT.md`。

---

## 已知限制

- `apps.launch` 从后台启动 Activity 会被 Android 10+ 的后台启动限制拦掉，调用返回成功但前台不切换。
- `brightness.set` 需要 `WRITE_SETTINGS`，没授权时能力不可见。
- 自编译包（包名不同）拿不到 Google Play 的 ML Kit 扫码模块，App 内扫码不可用；可以用系统相机扫深链二维码绕过。
- 华为 HMS 的识别与合成只支持中文和英文。
- OpenClaw 网关与 OpenClaw HTTP 两条通道在作者的环境里没有服务端可测，未做过真机验证。

---

## 致谢

这个项目站在两个开源项目上：

- [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant)（作者 yuga-hashimoto，MIT）——本分支的起点，界面、语音交互、网关与节点能力都来自它。上游原本的 README 已由这份文档替换。
- [gpt-assistant-android](https://github.com/Skythinker616/gpt-assistant-android)（作者 Skythinker616，GPL-3.0）——语音识别层「统一接口 + 多后端」的结构参考了它的 `AsrClientBase` 设计，华为 HMS ML Kit 的接入方式也参考了该项目。这里按同样思路自行实现，未复制其代码。

## 许可

MIT，见 [LICENSE](LICENSE)。原始版权声明 Copyright (c) 2026 [yuga-hashimoto](https://github.com/yuga-hashimoto) 保留在仓库中；本分支新增的改动同样以 MIT 发布。第三方组件与随包资源见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
