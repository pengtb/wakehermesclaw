# WakeHermesClaw

Turn a phone into an AI assistant you can summon at any time: say the wake word, tap a quick settings tile, long-press the navigation bar, or press a headset button, and the voice panel rises; whatever you say goes to the backend running on your computer (Hermes Agent or OpenClaw), and the reply is spoken aloud. Beyond talking, the assistant can also drive the phone in the other direction — read the screen, tap, take photos, send SMS, get the location.

This is a personal fork built on top of [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant). The main work is wiring it up to Hermes Agent and finishing out the voice pipeline, backend selection, and phone control.

- Latest version: v2.5.2 ([Releases](https://github.com/pengtb/wakehermesclaw/releases))
- Requirements: Android 8.0 and above (minSdk 26), targetSdk 34
- Interface languages: Simplified Chinese / Traditional Chinese / English / Japanese / Korean / German / Spanish / French / Hindi / Russian

English · [简体中文](README.md) · [繁體中文](README.zh-TW.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [Français](README.fr.md) · [हिन्दी](README.hi.md) · [Русский](README.ru.md)

---

## Screenshots

| Home | Voice conversation | Conversations |
| :---: | :---: | :---: |
| <img src="docs/images/home.jpg" width="220"> | <img src="docs/images/voice_overlay.jpg" width="220"> | <img src="docs/images/conversations.jpg" width="220"> |
| **Canvas** | **Settings** | **Chat** |
| <img src="docs/images/canvas.jpg" width="220"> | <img src="docs/images/settings.jpg" width="220"> | <img src="docs/images/chat.jpg" width="220"> |

---

## What it does

### Voice conversation

- **Four ways to summon**: wake word, quick settings tile, system assistant gesture (long-press the navigation bar / power button), app static shortcut; you can also just tap "Voice conversation" inside the app.
- **Continuous conversation**: after answering it keeps listening; you can also switch to push-to-talk — tap to start speaking, tap again to stop and send.
- **Exit words**: saying something like "退出" or "exit" ends the conversation immediately. The default words follow the interface language and can be changed in settings.
- **The panel doesn't block notifications**: the voice panel is a bottom-anchored card, leaving the top status bar to the system, so you can still pull down the notification shade and tap approvals mid-conversation.
- **In-progress state**: a notification stays in the status bar for the duration of the conversation; tapping it returns to the conversation panel. In the foreground the panel shows "Voice conversation in progress", in the background it becomes "Tap to return to voice conversation", and the notification disappears automatically when the conversation ends.

### Speech recognition (STT)

Four backends, pick what you need:

| Backend | Description |
| --- | --- |
| System recognition | Uses the phone's built-in recognition service, zero configuration |
| OpenAI-compatible | Run your own `/v1/audio/transcriptions` service, batch upload |
| FunASR streaming | Local FunASR, text appears as you speak |
| Huawei HMS (ML Kit) | On-device recognition, requires HMS Core and an AGC API Key; **Chinese and English only** |

The recognition pipeline has two fallbacks: a silence timeout for when a whole segment yields no text, and a client-side finish when the Huawei SDK doesn't call back on the final frame.

### Speech synthesis (TTS)

| Provider | Description |
| --- | --- |
| Local (system TTS) | Default; when no engine is explicitly chosen it prefers Google TTS, then the system default |
| OpenAI-compatible | `POST {base}/v1/audio/speech`, endpoint, model and voice are configurable |
| Huawei HMS (ML Kit) | **Cloud** synthesis, requires network access and an AGC API Key, first 1 million characters per month free; Chinese and English only |
| ElevenLabs | Voice list localized with the language |
| VOICEVOX | Included in the `full` build only |

If no TTS engine at all is present on the system, a dialog offers two paths: install Google TTS, or install the open-source sherpa-onnx engine.

### Backends and connection methods

Three kinds of backend are supported, and voice and chat each choose their route independently:

| Type | Protocol | Purpose |
| --- | --- | --- |
| Hermes Agent | HTTP (`/v1/runs` or `/v1/chat/completions`) | Primary; supports system prompts, streaming output, sessions |
| OpenClaw gateway | WebSocket node protocol | The phone joins as a node, the gateway can push tool calls directly |
| OpenClaw HTTP | OpenAI-compatible HTTP | Lightweight channel that doesn't need a persistent connection |

- **Voice session connection** is one of three: `OpenClaw gateway` / `OpenClaw HTTP` / `Hermes HTTP`, available both at the top of "Voice conversation" on the home screen and in settings; if you never chose one it follows the main backend.
- **Text chat** goes by the backend type selected for the session, not by the setting above.
- **Prompts**: voice and text chat each have their own system prompt, editable in Settings → Chat; leaving it empty falls back to the built-in default.
- **Voice carries live context**: time, location, battery, network and screen state are sent with every voice request, so the assistant can answer "what time is it" or "how much battery is left" directly.
- **Service self-healing**: if the voice service loses its binding to the system, it retries in three stages — confirm the default assistant, request the system binding again, and finally restart the process.

### Phone control (Mobile Bridge)

The phone runs its own local HTTP server (port 8787 by default), and Hermes calls phone capabilities through it: reading the screen and driving the UI (`screen.tap/swipe/long_press/drag/back/home`, `screen.find_nodes`, `screen.window.describe`), screenshots (two-stage, JPEG), camera capture, location, SMS, contacts, calendar, reading and dismissing notifications, clipboard, media control (`media.play_pause/next/previous`, plus `media.status` for title, artist, album and position), volume and brightness, the app list (`apps.list`, apps with a launcher entry, flagged `system` / `launchable`) and launching (`apps.launch`, which verifies the foreground actually changed).

- One-time pairing: after enabling the bridge in the app a 6-digit pairing code appears; the computer exchanges the code for a token and no further pairing is needed.
- Authorization and approval: permissions are grouped by capability, high-risk actions (sending SMS, taking photos, UI operation) go through a confirmation dialog, and there are rate limits.
- Only recommended over a LAN or Tailscale; the bind mode must be set to LAN/VPN for connections to get through.
- **It is optional**: when going through the OpenClaw gateway, the gateway itself can issue phone capabilities, so the bridge doesn't need to be on.

### Other

- **Terminal**: the built-in terminal connects to the Hermes dashboard on your computer, requiring `hermes dashboard --tui`.
- **Scheduled tasks**: view, create and edit Hermes cron jobs, with support for supplying scripts (relative to `HERMES_HOME/scripts/`).
- **Diagnostics**: pre-flight checks are available on both the home screen and in settings; missing permissions, an unbound voice service, an unconfigured backend, or accessibility being off are each listed with where to fix them.
- **Dark mode**: the whole UI follows the system light/dark setting, including the setup wizard.
- **10 interface languages**: all 1092 strings are complete in every language, so English never leaks through.

---

## Installation

Download the APK from [Releases](https://github.com/pengtb/wakehermesclaw/releases) and install it directly (package name `com.openclaw.assistant`).

After installing, do three things in the app:

1. **Add a backend**: in Settings → Connection, enter the Hermes address and token (or the OpenClaw gateway host / port).
2. **Set it as the digital assistant**: make this app the default assistant, otherwise the navigation-bar long-press won't summon it.
3. **Grant permissions**: microphone is required; location, camera, SMS, contacts and calendar as needed for the capabilities you want; enable the accessibility service if you want phone control. Apps installed via sideload must first allow restricted settings (App info → ⋮ → Allow restricted settings), otherwise the system won't let you turn on accessibility.

Speech recognition and synthesis use system capabilities by default — it will speak and recognize without any configuration.

---

## Pairing

### Hermes backend

`integrations/agentvoice-pair/` holds a small desktop helper: the PowerShell and bash versions are equivalent and dependency-free (QR encoding and PNG writing are both inside the script). It probes the Hermes API address on this machine (Tailscale Serve first, then the LAN), the API key, plus the dashboard Terminal address and session token, and produces a pairing QR code:

```bash
bash install.sh                # installs it as agentvoice-pair; on Windows it installs the .ps1 and generates a .cmd wrapper
agentvoice-pair --open --svg   # generate the QR code and open it
```

The QR code carries a deep link:

```
agentvoice://setup?hu=<API地址>&hk=<key>&hm=<profile>&hr=1&hs=1&htu=<dashboard地址>&htt=<session token>
```

`hu` / `hk` are the Hermes address and API key, `hm` is the target profile, `hr=1` means use the Runs API by default, `hs=1` means streaming, and `htu` / `htt` are the dashboard address and session token used by the terminal. `hu` may be given more than once — multiple addresses become candidates probed concurrently. All three routes work on the phone: scan with the system camera (tap the link to come back into the app), scan inside the app, or paste this JSON / deep link straight into the setup wizard.

The QR code contains the API key and session token, so it is equivalent to a password — don't hand it out.

### Mobile Bridge

1. On the phone open Settings → Mobile Bridge; once enabled, the page shows a 6-digit pairing code along with an `agentvoice://pair?u=<桥接地址>&c=<配对码>` deep link.
2. On the computer run `agentvoice-pair --code <配对码> --write-env <路径>`, or `POST {"code":"<配对码>"}` directly to `http://<手机>:8787/pair`.
3. Write the returned token into `.env` as `AGENT_VOICE_BRIDGE_URL` / `AGENT_VOICE_BRIDGE_TOKEN`; every call afterwards sends the `Authorization` header, whose value is `Bearer` followed by the token.

The pairing code is one-time and has an expiry. Once you have the token, pairing is no longer needed, but high-risk capabilities such as sending SMS, taking photos and tapping still prompt for approval on each call.

---

## Building from source

Requires JDK 17 and Android SDK 35.

```bash
./gradlew assembleStandardDebug     # debug build (package name gets an extra .debug, can coexist with the release build)
./gradlew assembleFullDebug         # debug build with VOICEVOX
./gradlew assembleStandardRelease   # release build
```

Outputs land in `app/build/outputs/apk/<flavor>/<buildType>/`.

A few build notes:

- `app/google-services.json` is not committed. Without a real Firebase config you must build with `FIREBASE_ENABLED=false`, otherwise Firebase is initialized with a mock config.
- Release builds need a local signing config (`storeFile / storePassword / keyAlias / keyPassword` in `local.properties`).
- Release builds must exclude `uploadCrashlyticsMappingFileStandardRelease`, otherwise they will try to reach Firebase.
- R8 needs to keep the device-side classes referenced by the HMS SDK (`com.huawei.libcore.*`, `org.chromium.net.*`); the rules are already in `app/proguard-rules.pro`.
- Huawei keys are injected from `hms.asr.key / hms.asr.appid / hms.asr.cert` in `local.properties` into `BuildConfig` and are not committed; release builds do not contain the TTS key, so users fill it in themselves in the app.

---

## Code structure

```
app/src/main/java/com/openclaw/assistant/
├── speech/      recognition (system / OpenAI-compatible / FunASR / HMS), synthesis (local / ElevenLabs / OpenAI-compatible / HMS / VOICEVOX), exit words
├── backend/     backend abstraction and dispatch: Hermes HTTP, OpenClaw gateway, OpenClaw HTTP
├── service/     voice interaction service and conversation panel, wake word, tile, notifications, self-healing
├── bridge/      Mobile Bridge: HTTP server, capability implementations, accessibility, approval and authorization
├── node/        gateway node protocol and runtime
├── ui/          home, chat, settings, terminal, scheduled tasks, diagnostics
└── data/        settings and local storage
```

- `AGENT.md` is written for maintainers: the pitfalls hit along the way, why things are written the way they are, and which places fail silently the moment you change them.
- `docs/hermes-mobile-bridge.md` documents the bridge protocol and pairing flow.
- `integrations/` holds the desktop-side pairing tool.

---

## What this fork changes

Upstream is `yuga-hashimoto/openclaw-assistant`. The changes this fork makes on top of it, grouped by feature:

**Hermes backend**

- Hermes Agent is wired in as a first-class backend: both `/v1/runs` and `/v1/chat/completions` work, with sessions, streaming and system prompts all functional. For the Runs API the system message must sit in the top-level `instructions` (the server doesn't read it from `input`), and the client fills that in.
- Connection type is one of three: OpenClaw gateway / OpenClaw HTTP / Hermes HTTP, exposed in both the home screen and settings; matching is strict against the selection, with no more silent fallback.
- When the address omits `/v1/` it is filled in automatically, fixing the 404 on the first message after migrating an old config.

**Voice**

- Recognition became pluggable with multiple backends: system / OpenAI-compatible batch / FunASR streaming / Huawei HMS, with the old Baidu backend removed.
- Added Huawei HMS cloud synthesis; when the machine has no TTS engine at all, it shows installation guidance.
- Voice and text chat now have separate, editable system prompts, falling back to the default when left empty.
- Voice requests automatically carry time, location, battery, network and screen state.
- Exit words: saying one ends the current conversation; the default words follow the interface language.
- Three more ways to summon: a quick settings tile, the `VOICE_COMMAND` trampoline, and a static shortcut.
- If the voice service loses its binding to the system, it self-heals in three stages: confirm the default assistant → request the binding again → restart the process.
- The panel became a bottom-anchored card that no longer covers the notification shade; the in-conversation notification changes its text with foreground/background, tapping it returns to the panel, and it disappears when the conversation ends.

**Phone control**

- Both screenshots and camera capture are two-stage: first return metadata and a short-lived id, then fetch base64 by that id; images live in memory only, expire after 5 minutes, and never touch disk.
- Camera capture uses an "approved means armed" camera foreground service, sidestepping the Android 12+ restriction on starting a camera service from the background.
- Sending SMS now waits for the `sentIntent` result before returning, so you get the real result code.
- Accessibility gestures are dispatched on the main thread and wait for the callback, fixing tap / swipe failing silently; `screen.window.describe` reads from the active window, so the package name is no longer misreported.
- Capability groups gained `location` / `screen`; the bridge status card splits into "currently at / intended" lines so you can compare them directly after changing config.
- Added `media.status`: reads the playing app, title, artist, album, position and state, alongside the three existing transport keys.
- `apps.list` now enumerates by launcher entry and flags `system` / `launchable` (MAIN/LAUNCHER declared in `<queries>`, no `QUERY_ALL_PACKAGES`).
- `apps.launch` verifies the foreground actually changed instead of always reporting success.
- The bridge's accept loop no longer exits on a single `IOException`, which used to leave the port listening while every connection timed out.

**Everyday use**

- Pre-flight checks exist in three places — home, settings and the wizard — covering runtime permissions and the accessibility service, each with a "go fix it" action.
- All 1092 strings are translated across 10 interface languages, selectable in the language list.
- Dark mode covers the setup wizard and the backend management page.
- The terminal gained dashboard address and session token fields; scheduled tasks gained a script line, and fixed interval / once jobs failing to deserialize the whole list because `expr` was mandatory.
- The bottom of the settings page shows the build short hash, so different artifacts under the same version number can be told apart.

For the detailed diff, follow `git log`; the architectural trade-offs and the pitfalls hit along the way are written up in `AGENT.md`.

---

## Known limitations

- `brightness.set` needs `WRITE_SETTINGS`; without the permission the capability isn't visible.
- Self-compiled builds (with a different package name) don't get the Google Play ML Kit barcode module, so in-app scanning is unavailable; you can work around it by scanning the deep-link QR code with the system camera.
- Huawei HMS recognition and synthesis support Chinese and English only.
- The OpenClaw gateway and OpenClaw HTTP channels had no server available to test against in the author's environment, and have not been verified on a real device.

---

## Acknowledgements

This project stands on two open-source projects:

- [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant) (by yuga-hashimoto, MIT) — the starting point of this fork: the UI, voice interaction, gateway and node capabilities all come from it. The upstream README has been replaced by this document.
- [gpt-assistant-android](https://github.com/Skythinker616/gpt-assistant-android) (by Skythinker616, GPL-3.0) — the "one interface, multiple backends" structure of the speech recognition layer follows its `AsrClientBase` design, and the Huawei HMS ML Kit integration was also informed by that project. The implementation here was written independently; no code was copied.

## License

MIT, see [LICENSE](LICENSE). The original copyright notice, Copyright (c) 2026 [yuga-hashimoto](https://github.com/yuga-hashimoto), is kept in the repository; changes added in this fork are released under MIT as well. Third-party components and bundled assets are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
