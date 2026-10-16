> ### 这是 pengtb 的 fork（Hermes Agent 集成）
> 在上游基础上补齐 Hermes 侧配对/桥接体验并修了几个 bug。改动清单、Mobile Bridge 能力实测表、
> 构建说明见文末 [**Fork notes**](#fork-notes-hermes-pairing--mobile-bridge-status)。
> 上游：[yuga-hashimoto/openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant)（MIT）。

# WakeHermesClaw for Android 🦞

> **A native Android voice client for OpenClaw and Hermes Agent.**
>
> WakeHermesClaw is the successor to *OpenClaw Assistant*. It keeps every
> OpenClaw feature you already have — wake word, Voice Overlay, Gateway
> + HTTP backends, Wear OS, on-device node capabilities, continuous
> conversation — and adds first-class support for **Hermes Agent** as a
> peer backend that can be configured side-by-side with OpenClaw.
>
> **Supported backends** (configure any combination, pick one as primary):
> - OpenClaw Gateway (WebSocket, pairing, QR, TLS)
> - OpenClaw HTTP (OpenAI-compatible chat)
> - Hermes API Server (`/v1/chat/completions` streaming + `/v1/runs` Runs API)
>
> **Mobile Bridge (optional, off by default).** WakeHermesClaw can expose a
> bearer-token-protected local HTTP service that lets Hermes reach a
> curated set of Android capabilities. See
> [`docs/hermes-mobile-bridge.md`](docs/hermes-mobile-bridge.md) and
> [`integrations/hermes-mobile-bridge/`](integrations/hermes-mobile-bridge/).
>
> **Migration.** Existing OpenClaw Assistant installs upgrade in place
> (`applicationId` unchanged); the first launch silently migrates the
> previous Gateway / HTTP settings into the new multi-backend repository
> and marks the existing setup as **Primary**. Wake word, HotwordService,
> Voice Overlay, Wear OS, and node capabilities continue to target the
> Primary backend.
>
> **Hermes setup in 30 seconds.** Run `hermes gateway` with the API
> server enabled (default port `8642`), bind it to your LAN/VPN,
> generate an API key, and add it in **Settings → Backends → Add Hermes
> API Server**. WakeHermesClaw accepts both `http://host:8642` and
> `http://host:8642/v1`. Authentication uses `Authorization: Bearer
> <key>`; the default model name is `hermes-agent`. Connection test
> calls `GET /v1/models` and falls back to `/health`.
>
> **WakeHermesClaw shared agent controls.** The app includes 6-character
> bridge pairing codes, deep-link setup payloads, multi-endpoint candidates
> (LAN + VPN + public) raced in parallel on connect, per-capability TTL grants
> ("approve · 10 min / 1 hour / until revoked") with a destructive-verb
> override, `/revoke` and `/grants` endpoints, an Accessibility Bridge
> (tap / swipe / Home / Back / window describe — sideload-only),
> notifications.active.list backed by the existing NotificationListenerService,
> and a build-flag (`IS_SIDELOAD`)
> that gates Accessibility + SMS for a Play-track build.
>
> **Wear OS** uses its own per-watch backend URL + auth token. Point the
> watch at the same Hermes (`http://host:8642`, Bearer = your API key) or
> OpenClaw endpoint you use on the phone. The phone's Primary backend is not
> automatically synced to the watch yet.
>
> **Build:** `./gradlew testDebugUnitTest assembleDebug`.

---

# OpenClaw Assistant 🦞 (legacy README continues below)
![CI](https://github.com/yuga-hashimoto/OpenClawAssistant/actions/workflows/ci.yml/badge.svg)
[![Latest Release](https://img.shields.io/github/v/release/yuga-hashimoto/OpenClawAssistant?display_name=tag)](https://github.com/yuga-hashimoto/OpenClawAssistant/releases/latest)
[![License: MIT](https://img.shields.io/github/license/yuga-hashimoto/OpenClawAssistant)](https://github.com/yuga-hashimoto/OpenClawAssistant/blob/main/LICENSE)

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/R5R51S97C4)

**[日本語版はこちら](#日本語) | English below**

<div align="center">

<h3>Self-hosted Android voice assistant with offline wake word, system-level controls, and Wear OS support.</h3>

<p>Built for OpenClaw, designed to feel like a real assistant on your phone instead of a thin chat wrapper.</p>

<p>
  <a href="https://github.com/yuga-hashimoto/OpenClawAssistant/releases/latest">Download APK</a>
  ·
  <a href="#quick-start">5-Minute Quick Start</a>
  ·
  <a href="https://x.com/monsterggg/status/2019735968216146043">Watch Demo</a>
  ·
  <a href="https://github.com/openclaw/openclaw">Get OpenClaw</a>
</p>

### Demo

<a href="https://x.com/monsterggg/status/2019735968216146043">
  <img src="https://pbs.twimg.com/ext_tw_video_thumb/2019735943037763584/pu/img/jvM7qUNDl9LBLtu6.jpg" alt="OpenClaw Assistant Demo Video" width="270">
</a>

<p><em>Tap the image to watch the full demo video</em></p>

### Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/images/home.jpg" width="180"><br><em>Home</em></td>
    <td align="center"><img src="docs/images/voice_overlay.jpg" width="180"><br><em>Voice Overlay</em></td>
    <td align="center"><img src="docs/images/conversations.jpg" width="180"><br><em>Conversations</em></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/canvas.jpg" width="180"><br><em>Canvas (A2UI)</em></td>
    <td align="center"><img src="docs/images/settings.jpg" width="180"><br><em>Settings</em></td>
    <td align="center"><img src="docs/images/chat.jpg" width="180"><br><em>Chat</em></td>
  </tr>
</table>

</div>

---

## English

**Your AI Assistant in Your Pocket** - A self-hosted Android voice assistant for OpenClaw with offline wake word detection, real device actions, and a UI built for everyday use.

### Why OpenClaw Assistant

- **Feels native on Android** - Long press Home, use wake word detection, stream replies, and keep talking in continuous conversation mode
- **Actually controls your device** - Notifications, camera, contacts, calendar, apps, clipboard, WiFi, screen sharing, and more
- **Self-hosted, not locked to one cloud app** - Connects to your OpenClaw gateway over WebSocket or HTTP
- **Works beyond the phone** - Includes Wear OS support and a Canvas tab for richer AI-driven UI

<a id="quick-start"></a>

### 🚀 5-Minute Quick Start

1. Install the latest APK from [Releases](https://github.com/yuga-hashimoto/OpenClawAssistant/releases/latest)
2. On your server, run `openclaw qr`
3. Open the app and tap **Scan QR Code**
4. Approve the device on your server if pairing is requested
5. Long press Home or say your wake word

Need the server first? Start with [OpenClaw](https://github.com/openclaw/openclaw).

### 🔒 Privacy & Security at a Glance

- **Offline wake word detection** runs locally on-device with [Vosk](https://alphacephei.com/vosk/)
- **Sensitive settings** like server URLs and tokens are stored with AES256-GCM encryption
- **Powerful capabilities are opt-in** and guarded by Android permissions or explicit user actions

### ✨ Features

#### Voice & Speech
- 🎤 **Customizable Wake Word** - Choose from "OpenClaw", "Hey Assistant", "Jarvis", "Computer", or set your own custom phrase
- 📴 **Offline Wake Word Detection** - Always-on local processing powered by [Vosk](https://alphacephei.com/vosk/), no internet required
- 🗣️ **Speech Recognition** - Real-time speech-to-text with partial results display and configurable silence timeout
- 🔊 **Text-to-Speech** - Automatic voice output with adjustable speech speed, multi-engine support, and smart text chunking for long responses. Supports **Android native**, **ElevenLabs**, **OpenAI**, and **VOICEVOX** (full build) TTS providers
- 🔄 **Continuous Conversation Mode** - Auto-resumes listening after AI response for natural back-and-forth dialogue
- 🏠 **System Assistant Integration** - Long press Home button to activate via Android VoiceInteractionService
- 🔃 **Wake Word Sync** - Download wake words configured on the gateway server to your device
- ⌚ **Wear OS Support** - Use OpenClaw Assistant directly from your smartwatch

#### Chat & AI
- 💬 **In-App Chat Interface** - Full-featured chat UI with text and voice input, markdown rendering, and message timestamps
- 🤖 **Agent Selection** - Choose from multiple AI agents fetched dynamically from the gateway
- 📡 **Real-time Streaming** - See AI responses as they are generated via WebSocket gateway
- 💾 **Chat History** - Local message persistence with session management (create, switch, delete conversations)
- 🔔 **Thinking Sound** - Optional audio cue while waiting for AI response
- 🪟 **Dual Chat Modes** - Gateway Chat (via Node-Gateway connection) or HTTP Chat (direct HTTP endpoint)
- 📎 **File Attachments** - Attach images directly in the chat interface

#### Gateway & Connectivity
- 🌐 **WebSocket Gateway** - Persistent connection with auto-reconnect (exponential backoff), ping keep-alive, and RPC protocol
- 🔍 **Auto-Discovery** - Automatically find OpenClaw gateways on your local network via mDNS/Bonjour
- 🔌 **Manual Connection** - Specify host, port, and token for direct connection
- 🔒 **TLS Support** - Encrypted connections with SHA-256 fingerprint verification dialog for first-time trust
- 📋 **Agent Discovery** - Dynamically fetch available agents from the gateway
- 🔗 **Device Pairing** - Server-side device approval with Ed25519 cryptographic identity
- ✅ **Connection Testing** - Built-in connection test with live feedback in settings

#### Node Capabilities
- 📷 **Camera** - AI can capture photos and video clips via the device camera
- 📍 **Location** - Share your location (Off / Coarse / Precise) with the AI, including background tracking
- 📲 **SMS** - Allow the AI to send and read text messages with your permission
- 🖥️ **Screen Recording** - Let the AI see your screen when you explicitly ask it to
- 🔔 **Notifications** - AI can read and interact with your active notifications
- 🏃 **Motion & Activity** - Step counting and physical activity detection (walking, running, etc.)
- 📶 **WiFi** - AI can check WiFi status and list available networks
- 👥 **Contacts** - AI can search, add, update, and delete contacts
- 📅 **Calendar** - AI can query, add, update, and delete calendar events
- 📋 **Clipboard** - AI can read from and write to the clipboard
- 📱 **Apps** - AI can list installed apps and launch applications
- 🖼️ **Photos** - AI can retrieve recent photos from your device gallery
- 🔧 **System Control** - AI can adjust volume, screen brightness, and send notifications

#### System & Security
- 🔒 **Encrypted Settings** - All sensitive data (URL, tokens) stored with AES256-GCM encryption
- 🔑 **Device Identity** - Ed25519 key pair generation with Android Keystore integration
- 🚀 **Auto-Start on Boot** - Hotword service automatically resumes after device restart
- 📊 **Firebase Crashlytics** - Crash reporting with smart filtering of transient network errors
- 🔋 **Battery Optimization Exclusion** - Ensures wake word detection runs reliably in background

#### UI & Accessibility
- 🎨 **Material 3 Design** - Modern UI with Jetpack Compose and dynamic theming, 4-tab bottom navigation
- 🖼️ **Canvas Tab** - WebView-based interactive display for rich AI-driven UI
- 📝 **Markdown Rendering** - Rich text display in chat messages (bold, italic, code blocks, lists, links, inline images)
- 🩺 **Voice Diagnostics** - Built-in health check for STT/TTS engines with fix suggestions
- ❓ **Troubleshooting Guide** - In-app help for common issues (Circle to Search, gesture navigation, etc.)
- 🌍 **9-Language UI** - English, Japanese, Spanish, French, German, Hindi, Russian, Simplified Chinese, Traditional Chinese

### 📱 How to Use

1. **Long press Home button** or say the **wake word**
2. Ask your question or make a request
3. OpenClaw responds with voice
4. Continue the conversation (session maintained)

### 🚀 Detailed Setup

#### 1. Install the App

Download APK from [Releases](https://github.com/yuga-hashimoto/OpenClawAssistant/releases), or build from source.

For local source builds, see [BUILDING.md](BUILDING.md). It covers the checked-in debug Firebase stub, test commands, and release signing requirements.

#### 2. Gateway Connection (Recommended)

The app connects to your OpenClaw server via the Gateway protocol.

**Quick setup via QR code:**
1. On your server, run `openclaw qr` to display the setup QR code
2. Open the app and tap **Scan QR Code** in the setup guide to auto-configure

**Manual setup:**
1. Open the app and tap ⚙️ to open **Settings**
2. Under **Gateway Connection**:
   - The app will auto-discover gateways on your local network
   - Or enable **Manual Connection** and enter:
     - **Host**: Your OpenClaw server hostname/IP
     - **Port**: Gateway port (default: `18780`)
     - **Token**: Gateway auth token (from `gateway.auth.token` in `moltbot.json`)
     - **Use TLS**: Enable for encrypted connections
3. Tap **Connect**
4. If prompted, approve the device on your server:
   ```bash
   openclaw devices approve $(openclaw devices list --json | tr '{' '\n' | grep '"deviceId":"<DEVICE_ID>"' | grep -o '"requestId":"[^"]*"' | cut -d'"' -f4)
   ```
5. Enable **Use Gateway Chat** to route chat through the gateway

#### 3. HTTP Connection (Optional)

For direct HTTP chat completions without the Gateway:

1. Under **HTTP Connection** in Settings:
   - **Server URL**: Your OpenClaw HTTP endpoint
   - **Auth Token**: Bearer authentication token
2. Tap **Test Connection** to verify
3. In the chat screen, select **HTTP Chat** mode

To expose the gateway HTTP endpoint externally (e.g., via ngrok):
```bash
ngrok http 18789
```
- **Server URL**: `https://<ngrok-subdomain>.ngrok-free.dev`
- Ensure Chat Completions is enabled in `moltbot.json`:
```json
{
  "gateway": {
    "http": {
      "endpoints": {
        "chatCompletions": { "enabled": true }
      }
    }
  }
}
```

#### 4. Wake Word Setup

1. Open **Wake Word** section in Settings
2. Choose a preset:
   - **OpenClaw** (default)
   - **Hey Assistant**
   - **Jarvis**
   - **Computer**
   - **Custom...** (enter your own, 2-3 words)
3. Or tap **Get Wake Words from Gateway** to sync from server
4. Enable the Wake Word toggle on the home screen

#### 5. Set as System Assistant

1. Tap "Home Button" card in the app
2. Or: Device Settings → Apps → Default Apps → Digital Assistant
3. Select "OpenClaw Assistant"
4. Long press Home to activate

#### 6. Voice & Node Settings (Optional)

- **Speech Speed**: Adjust TTS playback rate (default 1.2x)
- **TTS Engine**: Select from available engines on your device
- **Continuous Mode**: Enable auto-resume listening after response
- **Silence Timeout**: Configure how long to wait for speech input
- **Thinking Sound**: Toggle audio cue during AI processing
- **Default Agent**: Choose which AI agent handles your requests
- **Camera**: Allow the AI to take photos
- **Location**: Set location sharing level (Off / Coarse / Precise)
- **SMS**: Allow the AI to send text messages
- **Screen**: Allow the AI to see your screen

### 🛠 Tech Stack

| Category | Technology |
|----------|-----------|
| **Language** | Kotlin |
| **UI** | Jetpack Compose + Material 3 |
| **Speech Recognition** | Android SpeechRecognizer |
| **Text-to-Speech** | Android TTS / ElevenLabs / OpenAI / VOICEVOX (full build) |
| **Wake Word** | [Vosk](https://alphacephei.com/vosk/) 0.3.75 (offline) |
| **System Integration** | VoiceInteractionService |
| **Networking** | OkHttp 4.12 + WebSocket |
| **Discovery** | mDNS/Bonjour (NsdManager) + dnsjava 3.6.3 |
| **QR Scanning** | ZXing 4.3.0 |
| **JSON** | Gson + kotlinx.serialization |
| **Database** | Room (SQLite) |
| **Security** | EncryptedSharedPreferences (AES256-GCM) |
| **Cryptography** | Tink (Ed25519) + Android Keystore |
| **Markdown** | multiplatform-markdown-renderer-m3 |
| **Crash Reporting** | Firebase Crashlytics |
| **Analytics** | Firebase Analytics |
| **Min SDK** | Android 12 (API 31) |
| **Target SDK** | Android 14 (API 34) |

### 📋 Required Permissions

| Permission | Purpose |
|------------|---------|
| `RECORD_AUDIO` | Speech recognition & wake word detection |
| `INTERNET` | Gateway & API communication |
| `FOREGROUND_SERVICE` | Always-on wake word detection |
| `FOREGROUND_SERVICE_MICROPHONE` | Microphone access in foreground service |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Screen capture in foreground service |
| `POST_NOTIFICATIONS` | Status notifications (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Auto-start hotword on boot |
| `WAKE_LOCK` | Keep CPU active during voice session |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Reliable background wake word detection |
| `CAMERA` | Camera capture for AI (optional) |
| `ACCESS_FINE_LOCATION` | Precise GPS for AI (optional) |
| `ACCESS_COARSE_LOCATION` | Approximate location for AI (optional) |
| `ACCESS_BACKGROUND_LOCATION` | Background location tracking for AI (optional) |
| `SEND_SMS` / `READ_SMS` | AI-assisted messaging (optional) |
| `READ_CONTACTS` / `WRITE_CONTACTS` | AI contact management (optional) |
| `READ_CALENDAR` / `WRITE_CALENDAR` | AI calendar access (optional) |
| `ACTIVITY_RECOGNITION` | Step counting & activity detection (optional) |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | Photo & video access for AI (optional) |
| `NEARBY_WIFI_DEVICES` | WiFi network discovery (Android 13+, optional) |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | WiFi status & control (optional) |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read & interact with notifications (optional, requires manual Settings grant) |

### 🤝 Contributing

Pull Requests welcome! Feel free to report issues.

Please follow [BUILDING.md](BUILDING.md) for local setup and run the documented lint/unit test commands before opening a PR.

### 📄 License

MIT License - See [LICENSE](LICENSE) for details.

Bundled third-party binaries and assets are documented in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

---

## Fork notes: Hermes pairing + Mobile Bridge status

本 fork 基于上游 `main`（提交 `8562cea`，`v2.4.9.1+58`），改动集中在 **Hermes 后端的配对流程**、**Mobile Bridge 运维体验**
和几个确定性 bug。仓库名保持 `openclaw-assistant` 以保留 fork 关系，App 名称仍是 WakeHermesClaw，`applicationId` 未改。

面向开发者的说明（架构约定、构建与测试、易犯的错误）见 [AGENT.md](AGENT.md)。

### 1. 配对流程（Hermes 后端）

桌面端助手在 [`integrations/agentvoice-pair/`](integrations/agentvoice-pair/)：
PowerShell 版 [`agentvoice-pair.ps1`](integrations/agentvoice-pair/agentvoice-pair.ps1)、
bash 版 [`agentvoice-pair.sh`](integrations/agentvoice-pair/agentvoice-pair.sh)、
安装器 [`install.ps1`](integrations/agentvoice-pair/install.ps1) / [`install.sh`](integrations/agentvoice-pair/install.sh)。
零依赖：二维码编码器（Reed-Solomon / mask / 格式位）与 PNG 写入（含 CRC32/Adler32）都在脚本里，自带 `-SelfTest` 向量校验。

装好后默认生成**深链**二维码并打开图片：

```powershell
# Windows
agentvoice-pair -DeepLinkQr -Open
# Linux / macOS
agentvoice-pair --deeplink-qr --open
```

深链形如
`agentvoice://setup?hu=<API地址>&hk=<key>&hm=default&hr=1&hs=1&htu=<dashboard地址>&htt=<session token>`，
**App 内相机与系统相机都能扫**，点开链接即回到 App 完成导入。

App 侧另有一条不依赖相机的路：首启向导 "One QR sets up WakeHermesClaw" 一节末尾有"粘贴配对信息"输入框，
粘 JSON 载荷或 `agentvoice://` 深链都能解析（复用同一个 `parsePairingPayload`），
适合相机/中转 App 会截断或再编码长链接的机型。

> 二维码里含 Hermes API key 与 dashboard session token，等同密码，请勿外发。

### 2. 改动清单（相对上游 `8562cea`）

| 位置 | 改动 |
| --- | --- |
| `backend/HermesConfigApi.kt`, `ui/cron/CronScreen.kt` | cron 修复：`HermesCronSchedule.expr` 改为可空，补 `minutes` / `run_at`（Hermes 的 interval / once 任务没有 `expr`，原定义会让整个 cron 列表反序列化失败） |
| `ui/backend/BackendEditorActivity.kt` | 新增 Terminal（Hermes dashboard）URL 与 session token 字段；"Hermes 目标"（profile）提到 Agent Context 第一行 |
| `ui/SetupGuideScreen.kt` | 首启向导首页"下一步"下方加 Skip（小字下划线）；Hermes 卡加"粘贴配对信息"；向导文案按真实流程重写 |
| `ui/SetupGuideActivity.kt`（新增） | 设置 → 连接 里"重新运行设置向导"的宿主 Activity |
| `ui/setup/DesktopOs.kt`（新增） | Windows / Linux·macOS 命令切换（集中管理，避免各屏口径不一） |
| `ui/setup/ScanHelp.kt`（新增） | 扫码不可用/取消/权限/内容不符分别给可操作弹窗，替代原来一句 "Please update Google Play Services" |
| `ui/setup/HermesImportActivity.kt` | URL 参数容忍被再编码一次（`hu=https%3A%2F%2F…`）；解析失败时显示收到的链接（key/token 掩码） |
| `ui/settings/SettingsSaveSupport.kt`（新增） | 设置页保存后的统一收尾：语言改动提示 + 移动桥接按新配置重启，供两个入口共用 |
| `bridge/MobileBridgeServer.kt`, `bridge/MobileBridgeConfig.kt`, `ui/bridge/MobileBridgeSettingsActivity.kt` | 桥接状态卡分两行显示「目前在」（server 实际绑定的端口与绑定模式，取自 `ServerSocket.localPort`）与「拟在」（设置里已保存、重启后生效的值），改完配置保存后可直接对照 |
| `app/build.gradle.kts`, `SettingsActivity.kt` | build 标记：设置页底部显示 `<git 短哈希>.<构建时间>`，用于区分同一 tag 下的不同编译产物 |
| `integrations/agentvoice-pair/*` | 桌面端助手（PowerShell + bash，含 README 与安装器） |
| `app/src/test/.../PairingUriParseTest.kt`（新增） | 深链解析的 Robolectric 单测（query 形式、再编码容忍、非法输入拒绝） |
| `ui/LocaleAwareActivity.kt`（新增）+ 13 个 Activity | 界面语言统一处理：所有 Activity 都继承它，在 `attachBaseContext` 应用「设置 → 显示语言」（空 = 跟随系统）。此前只有 MainActivity / SettingsActivity 手写了这段，其余 11 个界面恒定跟系统语言 |
| `SettingsActivity.kt`（保存流程） | 语言改变时不再静默重启，改为弹确认框，点「立即重启」才清栈重启到主界面；点「稍后」下次新建界面时生效 |
| `SettingsActivity.kt`（语言列表） | 语言条目只用各语言自己的写法（简体中文 / 繁體中文 / English…），不写国家名；去掉与 `de-DE` 重复的 `de-AT`；只保留翻译完整的语言（见第 6 节） |
| `res/values-zh-rCN`, `res/values-zh-rTW` | 补齐 122 条缺失文案（含配对卡片、扫码提示、Mobile Bridge 页、Terminal 字段、Rig Qwen TTS），现 946/946 全覆盖 |
| `res/values/strings.xml`, `res/values-ja` | 默认（英文）文件里残留的 62 条日文改成英文（上游把英文文件当日文草稿写）；原本那条日文补进 `values-ja` |
| `app/src/test/.../LocaleAwareActivityTest.kt`（新增） | 语言映射单测：空 = 跟随系统、指定语言真的换翻译、无翻译回落英文；以及「立即重启」发出的 Intent 目标与 flag |
| `MainActivity.kt`（设置标签页 `MainNavHost`） | 底部导航的「设置」标签页同样支持"语言改动提示"与"保存时按新配置重启桥接"，与 `SettingsActivity` 共用同一份实现 |
| `SettingsActivity.kt`（保存流程） | 保存后若语言有变化，询问「是否立即重启」；语言的落地统一由 `LocaleAwareActivity` 处理 |
| `gradle/wrapper/gradle-wrapper.properties` | 指向腾讯云镜像（中国大陆构建速度考虑）；改回官方源只需还原 `distributionUrl` |

### 3. Mobile Bridge 能力实测表

设备：一台 Android 16 手机（自编译 debug 包，App `2.4.9.1-58`）。/manifest 暴露 32 项能力，源码里共 34 个实现。
自测脚本：[`integrations/hermes-mobile-bridge/tools/test_all_capabilities.py`](integrations/hermes-mobile-bridge/tools/test_all_capabilities.py)
（只测无副作用或可还原的：写操作先读原值、测完写回）。

| 能力 | 分组/风险 | 实测结果 | 结论 |
| --- | --- | --- | --- |
| device.info | device/LOW | completed：返回机型与系统信息（Android 16 / sdkInt 36） | 正常 |
| apps.list | apps/LOW | completed：19 个应用 | 正常 |
| wifi.status | device/LOW | completed：wifiEnabled=true | 正常 |
| location.get | device/MEDIUM | completed：返回经纬度 | 正常 |
| notifications.active.list | notifications/MEDIUM | completed：拿到实时通知 | 正常 |
| audio.volume.get / set | device/LOW/MEDIUM | 读到 50/150；设为 1 生效后还原 | 正常（可还原） |
| brightness.get | device/LOW | completed：175/255 | 正常 |
| clipboard.read / write | clipboard/LOW/MEDIUM | 读到原文；写入生效后还原 | 正常（可还原） |
| screen.window.describe | accessibility/LOW | completed：前台包名 + 标题 | 正常 |
| screen.hash / diff | accessibility/LOW | completed：返回 hash，可与上次比较 | 正常（链式） |
| screen.find_nodes / describe_node | accessibility/LOW | completed：按 text/class/clickable 查找并读节点 | 正常（链式） |
| screen.home | accessibility/LOW | completed：回到桌面 | 正常 |
| screen.screenshot / .fetch | accessibility/HIGH / MEDIUM | 本次仅构建，**尚未真机实测** | **已实现**：两段式（第一段回元数据 + 短时 id，第二段回 base64） |
| apps.launch | apps/MEDIUM | 回 `{"launched":true}`，但 1/3/6 秒后前台仍是桌面（两个 App 均如此） | **有 bug**：后台 `startActivity` 被 Android 10+ 静默拦截 |
| camera.capture_photo / .fetch | camera/HIGH / MEDIUM | **真机已验证**（Android 16，2026-10-16）：后摄 1568×2090、约 220KB；批准后 6.1s 出图、取图 5.6s；首次调用（服务未武装）正确回 `CAMERA_SERVICE_NOT_ARMED` | **已实现**：相机前台服务 + 两段式 |

以下能力代码是真实现，但本次**未实测**（都会产生真实副作用）：`screen.tap` / `swipe` / `long_press` / `drag`
（会真的点在手机上）、`screen.back`、`media.play_pause` / `next` / `previous`（会切歌）、`notification.dismiss`
（消除真实通知）、`sms.send`、`contacts.create` / `delete`、`calendar.create` / `delete`。另 `brightness.set`
运行时不可见（`isAvailable=false`，需 `WRITE_SETTINGS`）。

结论：设备/系统读取、剪贴板读写、通知读取、无障碍读屏与 Home/Back 这类全局动作都稳定可用；
图像两条路都已补上 —— 截图见 3.1（两段式，已真机验证）、拍照见 3.2（相机前台服务 + 两段式，尚未真机验证）；
`apps.launch` 静默失败。
验证能力是否真生效**必须**用 `screen.window.describe` 看前台包名，不能只看返回值。

#### 3.1 截图：两段式（本次实现）

上游的 `screen.screenshot` 是占位实现（bitmap 被丢弃）。这里把它做成两段式，理由是桥的传输是 JSON ——
把整张图的 base64 塞进一个 /execute 响应，会让每个调用方都为像素买单，即使它只想问"画面变了吗"。

1. `screen.screenshot {long_edge?:1568, quality?:80}`（风险 HIGH）—— 走
   `AccessibilityService.takeScreenshot(displayId, executor, callback)`（API 30+，服务配置里必须有
   `android:canTakeScreenshot="true"`）拿到 `HardwareBuffer` → `Bitmap.wrapHardwareBuffer` → 复制成
   ARGB_8888 → 按长边缩放 → JPEG → 存进**内存**缓冲，只返回元数据：

       {"ok":true,"id":"sc-<ts>-<n>","mime":"image/jpeg","width":706,"height":1568,
        "sourceWidth":1116,"sourceHeight":2480,"bytes":93000,"longEdge":1568,"quality":80,
        "ttlMs":300000,"expiresAt":…,"fetchWith":"screen.screenshot.fetch"}

2. `screen.screenshot.fetch {id}`（风险 MEDIUM）—— 返回 `{base64, mime, width, height, bytes}`；
   id 过期或未知时返回 `{ok:false, reason:"unknown or expired id"}`（不伪造空图）。

缓冲由 `ScreenCaptureStore` 管理：**只存内存、不落盘**，TTL 5 分钟、最多 4 张、总量 ≤ 48MB，
超出按最旧淘汰；进程重启即清空。默认长边 1568 / JPEG 80 是实测的口径（原生 1116×2480 缩到
706×1568 约 93KB，读屏上的小字够用）。

Hermes 侧封装：`integrations/hermes-mobile-bridge/capture_screen.py`（两段一次做完、解码写文件、
打印含 path/尺寸/耗时的 JSON），装包后可这样验证：

    python capture_screen.py --out D:\tmp\shot.jpg

失败时 `screen.screenshot` 会带上平台的 `errorCode`：`2`=没有无障碍权限、`3`=调用太快（平台有频率限制）、
`4`=display 无效、`5`=窗口无效、`6`=目标页面设置了 `FLAG_SECURE`。

#### 3.2 拍照：相机前台服务 + 两段式（本次实现）

上游的 `camera.capture_photo` 是占位（`isAvailable=false`）。这里的难点不在拍照本身（CameraX 与
`node/CameraCaptureManager.kt` 里那套「拍→EXIF 旋转→缩放→压缩」都已有），而在**权限模型**：

- Android 11 起，后台打开相机必须由一个声明了 `camera` 类型的前台服务来做；
- Android 12 起，这种前台服务**不能在应用处于后台时启动** —— 官方文档明确写「即使符合后台启动的其它豁免也不行」，
  必须在**应用有可见界面时**调用 `startForegroundService()`。

所以设计成「批准即武装」：`camera.capture_photo` 是 HIGH 风险，本来就要用户批准；用户在批准界面点下批准的那一刻，
应用正好有可见界面 —— 就在那里启动 `CameraCaptureService`（`bridge/camera/CameraCaptureService.kt`，
manifest 里 `foregroundServiceType="camera"` + `FOREGROUND_SERVICE_CAMERA` 权限）。之后 HTTP 请求在手机开着别的
应用时也能被服务接住。选「批准 10 分钟 / 1 小时 / 直到撤销」时，服务在整个授权窗口内存活（"直到撤销"按 12 小时上限），
这样后续拍照不需要重新后台启动服务；选「批准一次」则拍完即停。

存活的是**服务**而不是相机：只有拍摄瞬间把服务内部的 `LifecycleOwner` 推到 RESUMED 打开相机，拍完立刻退回 CREATED
释放相机（指示灯熄灭），两次拍照之间相机不被占用。

接口与截图对齐，同样两段式：

1. `camera.capture_photo {facing?="back", long_edge?=1568, quality?=85}`（HIGH）→ 元数据 + `id`：
   `{ok, id, mime, width, height, bytes, facing, longEdge, quality, ttlMs, expiresAt, fetchWith}`
2. `camera.capture_photo.fetch {id}`（MEDIUM）→ `{base64, mime, width, height, bytes}`

同样是仅内存缓冲（和截图共用 `ScreenCaptureStore`：TTL 5 分钟、最多 4 条、≤48MB、不落盘——拍照只在 CameraX 写入
后立即删除的临时文件里短暂落过一次盘）。出错时返回结构化原因：`CAMERA_SERVICE_NOT_ARMED`（还没武装，需要先在
App 里批准一次）、`CAMERA_TIMEOUT`（25 秒没出帧）、`CAMERA_CAPTURE_FAILED`（含平台原因，例如相机被其它应用占用）。

Hermes 侧封装：`integrations/hermes-mobile-bridge/capture_photo.py`（两段一次做完、解码写文件、打印 JSON）。

### 4. 构建

- JDK 17 + Android SDK（`compileSdk 35`、`build-tools 35.0.0`）。
- 在 `app/` 下放 `google-services.json`（可从 `google-services.json.example` 复制）；CI 或本地构建可设
  `FIREBASE_ENABLED=false` 跳过 Firebase。
- 构建 debug 包：`./gradlew assembleStandardDebug`，产物在 `app/build/outputs/apk/standard/debug/`。
- 单测：`./gradlew testStandardDebugUnitTest`（含 `PairingUriParseTest`，Robolectric 需要下载 android-all 制品，
  国内可加 init 脚本把 `robolectric.dependency.repo.url` 指向镜像）。

### 5. 已知问题（上游同样存在）

- `apps.launch` 后台启动 Activity 被系统拦截，返回值仍是 `launched: true`。
- 相机的两条限制来自系统，不是我们代码的问题，但会体现在调用结果上：**没先在 App 里批准过，`camera.capture_photo`
  会回 `CAMERA_SERVICE_NOT_ARMED`**；相机被其它应用占用时拍照失败（如实报错，不伪装成功）。
- 自编译包（不同包名）拿不到 Google Play 的 ML Kit 扫码模块，内置扫码可能不可用 —— 用深链二维码 + 系统相机即可绕过。

### 6. 界面语言（已实现 / 待补齐）

判定标准：**翻译完整（946/946 条）才算"已实现"**，才会出现在「设置 → 显示语言」列表里；
不完整的语言即使资源目录存在也先不进列表，避免界面里混着英文。

已实现（语言列表里可选）：

| 语言条目 | 资源目录 | 覆盖 |
| --- | --- | --- |
| 跟随系统（默认值，不写死语言） | — | 用系统语言；缺翻译时回落英文 |
| 简体中文 | `res/values-zh-rCN` | 946/946（100%） |
| 繁體中文 | `res/values-zh-rTW` | 946/946（100%） |
| English | `res/values`（默认） | 946/946（100%） |

待补齐（资源已在包内，但翻译不完整，故未列入语言列表）：

| 语言 | 资源目录 | 覆盖 | 缺 | 说明 |
| --- | --- | --- | --- | --- |
| 日本語 | `res/values-ja` | 908/946（96%） | 38 | 补齐后即可加回列表 |
| 한국어 | `res/values-ko` | 908/946（96%） | 38 | 补齐后即可加回列表 |
| Deutsch | `res/values-de` | 820/946（87%） | 126 | 上游只译到 87% |
| Español | `res/values-es` | 820/946（87%） | 126 | 同上 |
| Français | `res/values-fr` | 820/946（87%） | 126 | 同上 |
| हिन्दी | `res/values-hi` | 820/946（87%） | 126 | 同上 |
| Русский | `res/values-ru` | 820/946（87%） | 126 | 同上 |

统计口径：`values/strings.xml` 的 `<string>` 条目数（946）。缺的条目在运行时自动回落英文，不会崩。

生效流程：语言只在界面重新创建时读取「显示语言」，所以保存时若语言有变化会弹「重启以应用新语言？」，
点「立即重启」清任务栈回主界面后整体生效；点「稍后」则下次新建界面时生效。

### 7. 待实现 / 待测试

| 项目 | 现状 | 待做 / 待验 | 依赖 |
| --- | --- | --- | --- |
| ja / ko 文案 | 各缺 38 条 | 补齐后可加回语言列表 | 翻译 |
| de / es / fr / hi / ru 文案 | 各缺 126 条 | 补齐后可加回语言列表 | 翻译（工作量大，或改上游） |
| 语言切换的即时刷新 | 已实现「保存 → 询问 → 立即重启」；**真机已验证**（Android 16，2026-10-15）：弹窗出现、点「立即重启」后整体换语言 | 无 | — |
| 设置页保存后的收尾 | 统一到 `ui/settings/SettingsSaveSupport`；**真机已验证**（Android 16，2026-10-15）：改端口后保存，状态卡「目前在」更新为新端口，即"保存时按新配置重启桥接"生效 | 无 | — |
| `camera.capture_photo` | 已实现：相机前台服务（`camera` 类型）+ 两段式；**真机已验证**（Android 16，2026-10-16）：批准即武装、拍完自动停服务、未武装时回 `CAMERA_SERVICE_NOT_ARMED` | 无 | — |
- `screen.screenshot` | 已实现两段式（3.1）；**真机已验证**（Android 16，2026-10-15）：706×1568、约 78KB | 无 | — |
| `apps.launch` | 回 `launched:true` 但被 Android 10+ 后台启动限制静默拦截 | 改用前台服务/`PendingIntent` 或明确报错 | 需要设计决策 |
| `brightness.set` | 运行时不可见（`isAvailable=false`） | 引导授予 `WRITE_SETTINGS` 后再暴露 | 权限 |
| 内置二维码扫描 | 自编译包拿不到 Google Play 的 ML Kit 模块 | 捆绑 zxing + CameraX，或维持「系统相机扫深链」 | 改动较大 |
| 有副作用的 12 项能力 | `screen.tap/swipe/long_press/drag/back`、`media.*`、`notification.dismiss`、`sms.send`、`contacts.*`、`calendar.*` 只做了代码审查 | 需在受控场景逐项实测 | 会真的改手机状态 |
| cron 修复 | 已在本 fork 分支 | 建议单独提 PR 回上游（`HermesCronSchedule.expr` 必填会让整个 cron 列表反序列化失败） | 上游评审 |

### 8. 许可与出处

上游为 **MIT**（[`LICENSE`](LICENSE)，Copyright (c) 2026 yuga-hashimoto）；本 fork 保留原许可与版权声明，
新增改动同样以 MIT 发布。第三方组件与随包资源见 [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)。

---

## 日本語

**あなたのAIアシスタントをポケットに** - オフラインのウェイクワード、スマホ操作、Wear OS 連携まで備えた、OpenClaw向けのセルフホスト型 Android 音声アシスタントです。

### OpenClaw Assistant の強み

- **Android に深く統合** - ホームボタン長押し、ウェイクワード起動、ストリーミング応答、連続会話まで自然につながる
- **チャット止まりではなく端末を操作できる** - 通知、カメラ、連絡先、カレンダー、アプリ、クリップボード、WiFi、画面共有などに対応
- **クラウド依存ではなくセルフホスト前提** - OpenClaw Gateway に WebSocket または HTTP で接続
- **スマホ以外にも広がる** - Wear OS 対応と Canvas タブによるリッチな AI UI を搭載

### 🚀 5分クイックスタート

1. [Releases](https://github.com/yuga-hashimoto/OpenClawAssistant/releases/latest) から最新 APK をインストール
2. サーバー側で `openclaw qr` を実行
3. アプリで **QRコードをスキャン** をタップ
4. ペアリング要求が出たらサーバー側で承認
5. ホームボタン長押し、またはウェイクワードで起動

まだサーバーがない場合は [OpenClaw](https://github.com/openclaw/openclaw) から始めてください。

### 🔒 プライバシーとセキュリティ

- **ウェイクワード検知はオフライン** で、[Vosk](https://alphacephei.com/vosk/) による端末内処理
- **URL やトークンなどの機密設定は暗号化保存** される
- **強い権限が必要な機能は任意** で、Android の権限や明示操作が必要

### ✨ 機能

#### 音声・スピーチ
- 🎤 **カスタマイズ可能なウェイクワード** - 「OpenClaw」「Hey Assistant」「Jarvis」「Computer」から選択、または自由にカスタムフレーズを入力
- 📴 **オフライン対応のウェイクワード検知** - [Vosk](https://alphacephei.com/vosk/)によるローカル処理で常時待ち受け、インターネット不要
- 🗣️ **音声認識** - リアルタイムの音声テキスト変換、部分認識結果の表示、サイレンスタイムアウト設定
- 🔊 **音声読み上げ (TTS)** - 読み上げ速度調整、複数エンジン対応、長文の自動分割読み上げ。**Android標準**・**ElevenLabs**・**OpenAI**・**VOICEVOX**（fullビルド）に対応
- 🔄 **連続会話モード** - AI応答後に自動で聞き取り再開、自然な対話フロー
- 🏠 **システムアシスタント連携** - ホームボタン長押しでAndroid VoiceInteractionService経由で起動
- 🔃 **ウェイクワード同期** - ゲートウェイサーバーで設定されたウェイクワードをデバイスにダウンロード
- ⌚ **Wear OSサポート** - スマートウォッチからOpenClaw Assistantを直接利用

#### チャット・AI
- 💬 **アプリ内チャットUI** - テキスト＆音声入力対応のフル機能チャット画面、Markdownレンダリング、タイムスタンプ表示
- 🤖 **エージェント選択** - ゲートウェイから動的に取得したAIエージェントを切り替え
- 📡 **リアルタイムストリーミング** - WebSocketゲートウェイによるAI応答のリアルタイム表示
- 💾 **チャット履歴** - ローカルDBでメッセージ永続化、セッション管理（作成・切替・削除）
- 🔔 **思考サウンド** - AI処理中のオプション音声フィードバック
- 🪟 **2つのチャットモード** - Gateway Chat（Node-Gateway経由）またはHTTP Chat（直接HTTPエンドポイント）
- 📎 **ファイル添付** - チャット画面から画像ファイルを直接添付

#### ゲートウェイ・接続
- 🌐 **WebSocketゲートウェイ** - 自動再接続（指数バックオフ）、ping keep-alive、RPCプロトコル
- 🔍 **自動検出** - mDNS/BonjourによるローカルネットワークのOpenClawゲートウェイ自動検出
- 🔌 **手動接続** - ホスト・ポート・トークンを指定して直接接続
- 🔒 **TLSサポート** - 暗号化接続と初回接続時のSHA-256フィンガープリント検証ダイアログ
- 📋 **エージェント自動取得** - ゲートウェイから利用可能なエージェントを動的取得
- 🔗 **デバイスペアリング** - Ed25519暗号鍵によるデバイス認証とサーバー側承認
- ✅ **接続テスト** - 設定画面で接続確認をリアルタイムフィードバック付きで実行

#### ノード機能
- 📷 **カメラ** - AIがデバイスカメラで写真・動画を撮影
- 📍 **位置情報** - 位置情報をAIと共有（オフ / 大まか / 精密）、バックグラウンドトラッキング対応
- 📲 **SMS** - AIが許可を得てSMSの送受信を実行
- 🖥️ **スクリーンキャプチャ** - ユーザーの明示的な要求時にAIが画面を確認
- 🔔 **通知** - AIがアクティブな通知を読み取り・操作
- 🏃 **モーション・アクティビティ** - 歩数計測・アクティビティ認識（歩行・走行など）
- 📶 **WiFi** - AIがWiFi状態確認・利用可能なネットワーク一覧を取得
- 👥 **連絡先** - AIが連絡先の検索・追加・更新・削除を実行
- 📅 **カレンダー** - AIがカレンダーイベントの照会・追加・更新・削除を実行
- 📋 **クリップボード** - AIがクリップボードの読み取り・書き込みを実行
- 📱 **アプリ** - AIがインストール済みアプリの一覧取得・起動を実行
- 🖼️ **写真** - AIがデバイスのギャラリーから最新の写真を取得
- 🔧 **システム操作** - AIが音量・画面の明るさの調整、通知の送信を実行

#### システム・セキュリティ
- 🔒 **設定の暗号化保存** - URL・トークンなどの機密データをAES256-GCM暗号化で保存
- 🔑 **デバイスID** - Ed25519キーペア生成とAndroid Keystore連携
- 🚀 **起動時の自動開始** - デバイス再起動後にホットワードサービスを自動復帰
- 📊 **Firebase Crashlytics** - クラッシュレポートと一時的なネットワークエラーのスマートフィルタリング
- 🔋 **バッテリー最適化除外** - バックグラウンドでのウェイクワード検知の安定動作を保証

#### UI・アクセシビリティ
- 🎨 **Material 3デザイン** - Jetpack ComposeとダイナミックテーマによるモダンUI、4タブボトムナビゲーション
- 🖼️ **Canvasタブ** - WebViewベースのインタラクティブ表示でAIがリッチなUIを描画
- 📝 **Markdownレンダリング** - チャットメッセージのリッチテキスト表示（太字、斜体、コードブロック、リスト、リンク、インライン画像）
- 🩺 **音声診断** - STT/TTSエンジンのヘルスチェックと修正提案
- ❓ **トラブルシューティングガイド** - よくある問題のアプリ内ヘルプ（Circle to Search、ジェスチャーナビゲーションなど）
- 🌍 **9言語対応UI** - 英語・日本語・スペイン語・フランス語・ドイツ語・ヒンディー語・ロシア語・中国語（簡体・繁体）
  - 本 fork の設定に出るのは**翻訳が完了している言語だけ**：簡体中文・繁體中文・English（＋「システムに従う」）。他言語のリソースは同梱済みですが翻訳が未完了のため一覧から外しています（詳細は Fork notes 第6節）

### 📱 使い方

1. **ホームボタン長押し** または **ウェイクワード** を話す
2. 質問やリクエストを話す
3. OpenClawが音声で応答
4. 会話を続ける（セッション維持）

### 🚀 詳細セットアップ

#### 1. アプリのインストール

[Releases](https://github.com/yuga-hashimoto/OpenClawAssistant/releases) からAPKをダウンロード、またはソースからビルド。

ローカルでのソースビルド手順は [BUILDING.md](BUILDING.md) を参照してください。debug 用 Firebase スタブ、テストコマンド、release 署名の前提をまとめています。

#### 2. Gateway接続（推奨）

アプリはGatewayプロトコルを通じてOpenClawサーバーと接続します。

**QRコードでのクイックセットアップ：**
1. サーバー側で `openclaw qr` を実行してセットアップQRコードを表示
2. アプリのセットアップガイドで **QRコードをスキャン** をタップして自動設定

**手動セットアップ：**
1. アプリを開き、⚙️から **設定** を開く
2. **Gateway Connection** セクションで：
   - ローカルネットワーク上のゲートウェイを自動検出
   - または **Manual Connection** を有効にして手動入力：
     - **Host**: OpenClawサーバーのホスト名またはIP
     - **Port**: ゲートウェイポート（デフォルト: `18780`）
     - **Token**: ゲートウェイ認証トークン（`moltbot.json` の `gateway.auth.token`）
     - **Use TLS**: 暗号化接続を使用する場合はオン
3. **Connect** をタップ
4. ペアリングが必要な場合は、サーバー側で承認：
   ```bash
   openclaw devices approve $(openclaw devices list --json | tr '{' '\n' | grep '"deviceId":"<DEVICE_ID>"' | grep -o '"requestId":"[^"]*"' | cut -d'"' -f4)
   ```
5. **Use Gateway Chat** を有効にするとゲートウェイ経由でチャット

#### 3. HTTP接続（任意）

Gatewayを使わずに直接HTTP経由でチャットする場合：

1. Settings の **HTTP Connection** セクションで：
   - **Server URL**: OpenClawのHTTPエンドポイント
   - **Auth Token**: Bearer認証トークン
2. **接続テスト** をタップして確認
3. チャット画面で **HTTP Chat** モードを選択

ngrokなどでゲートウェイを外部公開する場合：
```bash
ngrok http 18789
```
Chat Completions APIが有効であることを `moltbot.json` で確認：
```json
{
  "gateway": {
    "http": {
      "endpoints": {
        "chatCompletions": { "enabled": true }
      }
    }
  }
}
```

#### 4. ウェイクワードの設定

1. 設定画面の **Wake Word** セクションを開く
2. プリセットから選択：
   - **OpenClaw** (デフォルト)
   - **Hey Assistant**
   - **Jarvis**
   - **Computer**
   - **Custom...** (自由入力、2〜3語)
3. または **Get Wake Words from Gateway** でサーバーから同期
4. ホーム画面でWake Wordトグルをオンに

#### 5. システムアシスタントとして設定

1. アプリの「Home Button」カードをタップ
2. または: 端末の設定 → アプリ → デフォルトアプリ → デジタルアシスタント
3. 「OpenClaw Assistant」を選択
4. ホームボタン長押しで起動可能に

#### 6. 音声・ノード設定（任意）

- **読み上げ速度**: TTS再生速度を調整（デフォルト1.2倍）
- **TTSエンジン**: 端末上で利用可能なエンジンを選択
- **連続会話モード**: 応答後に自動で聞き取り再開
- **サイレンスタイムアウト**: 音声入力の待ち時間を設定
- **思考サウンド**: AI処理中の音声フィードバックの切替
- **デフォルトエージェント**: リクエストを処理するAIエージェントの選択
- **カメラ**: AIによるカメラ撮影を許可
- **位置情報**: 位置情報の共有レベルを設定（オフ / 大まか / 精密）
- **SMS**: AIによるSMS送信を許可
- **スクリーン**: AIによる画面確認を許可

### 🛠 技術スタック

| カテゴリ | 技術 |
|---------|-----|
| **言語** | Kotlin |
| **UI** | Jetpack Compose + Material 3 |
| **音声認識** | Android SpeechRecognizer |
| **音声合成** | Android TTS / ElevenLabs / OpenAI / VOICEVOX (fullビルド) |
| **ウェイクワード** | [Vosk](https://alphacephei.com/vosk/) 0.3.75 (オフライン対応) |
| **システム連携** | VoiceInteractionService |
| **通信** | OkHttp 4.12 + WebSocket |
| **自動検出** | mDNS/Bonjour (NsdManager) + dnsjava 3.6.3 |
| **QRスキャン** | ZXing 4.3.0 |
| **JSON** | Gson + kotlinx.serialization |
| **データベース** | Room (SQLite) |
| **セキュリティ** | EncryptedSharedPreferences (AES256-GCM) |
| **暗号** | Tink (Ed25519) + Android Keystore |
| **Markdown** | multiplatform-markdown-renderer-m3 |
| **クラッシュレポート** | Firebase Crashlytics |
| **アナリティクス** | Firebase Analytics |
| **最小SDK** | Android 12 (API 31) |
| **ターゲットSDK** | Android 14 (API 34) |

### 📋 必要な権限

| 権限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 音声認識・ウェイクワード検知 |
| `INTERNET` | ゲートウェイ・API通信 |
| `FOREGROUND_SERVICE` | Wake Word常時検知 |
| `FOREGROUND_SERVICE_MICROPHONE` | フォアグラウンドサービスでのマイクアクセス |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | フォアグラウンドサービスでのスクリーンキャプチャ |
| `POST_NOTIFICATIONS` | ステータス通知 (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | 起動時のホットワード自動開始 |
| `WAKE_LOCK` | 音声セッション中のCPU維持 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | バックグラウンドでのウェイクワード検知安定化 |
| `CAMERA` | AIによるカメラ撮影（任意） |
| `ACCESS_FINE_LOCATION` | AIへの精密GPS共有（任意） |
| `ACCESS_COARSE_LOCATION` | AIへのおおよその位置共有（任意） |
| `ACCESS_BACKGROUND_LOCATION` | AIによるバックグラウンド位置情報追跡（任意） |
| `SEND_SMS` / `READ_SMS` | AIによるSMSアシスト（任意） |
| `READ_CONTACTS` / `WRITE_CONTACTS` | AIによる連絡先管理（任意） |
| `READ_CALENDAR` / `WRITE_CALENDAR` | AIによるカレンダーアクセス（任意） |
| `ACTIVITY_RECOGNITION` | 歩数計測・アクティビティ認識（任意） |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | AIによる写真・動画アクセス（任意） |
| `NEARBY_WIFI_DEVICES` | WiFiネットワーク探索 (Android 13+、任意) |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | WiFi状態確認・操作（任意） |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 通知の読み取り・操作（任意、設定画面での手動許可が必要） |

### 🤝 Contributing

Pull Requests歓迎！Issues報告もお気軽に。

PR 前のローカルセットアップと lint / unit test 手順は [BUILDING.md](BUILDING.md) を参照してください。

### 📄 ライセンス

MIT License - 詳細は [LICENSE](LICENSE) を参照。

同梱している第三者バイナリ・アセットは [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) にまとめています。

---

## Star History

[![Star History Chart](https://star-history.dera.page/svg?repos=yuga-hashimoto/openclaw-assistant&type=Date)](https://star-history.dera.page/#yuga-hashimoto/openclaw-assistant&type=date)

---

Made with ❤️ for [OpenClaw](https://github.com/openclaw/openclaw)
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/R5R51S97C4)
