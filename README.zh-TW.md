# WakeHermesClaw

把一支手機變成隨時能喚起的 AI 助理：說出喚醒詞、點一下快速設定磁貼、長按導覽列，或按一下耳機鍵，語音面板就會升起；你說的話交給在電腦上執行的後端（Hermes Agent 或 OpenClaw），回答則朗讀出來。除了說話，助理還能反過來操作這支手機——讀取螢幕畫面、點按、拍照、傳簡訊、定位。

這是在 [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant) 基礎上所做的自用分支，主要工作是接上 Hermes Agent，並把語音鏈路、後端選擇、手機控制做完整。

- 最新版本：v2.5.2（[Releases](https://github.com/pengtb/wakehermesclaw/releases)）
- 系統需求：Android 8.0 以上（minSdk 26），targetSdk 34
- 介面語言：簡體中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Español / Français / हिन्दी / Русский

[English](README.en.md) · 繁體中文 · [日本語](README.ja.md) · [한국어](README.ko.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [Français](README.fr.md) · [हिन्दी](README.hi.md) · [Русский](README.ru.md) · [简体中文](README.md)

---

## 螢幕截圖

| 首頁 | 語音對話 | 對話 |
| :---: | :---: | :---: |
| <img src="docs/images/home.jpg" width="220"> | <img src="docs/images/voice_overlay.jpg" width="220"> | <img src="docs/images/conversations.jpg" width="220"> |
| **畫布** | **設定** | **聊天** |
| <img src="docs/images/canvas.jpg" width="220"> | <img src="docs/images/settings.jpg" width="220"> | <img src="docs/images/chat.jpg" width="220"> |

---

## 它能做什麼

### 語音對話

- **四種喚起方式**：喚醒詞、快速設定磁貼、系統助理手勢（長按導覽列 / 電源鍵）、應用程式靜態捷徑；也可以直接點開 App 裡的「語音對話」。
- **連續對話**：回答完會自動繼續聆聽；也可以切成對講模式——按一下開始說話，再按一下結束並送出。
- **退出詞**：說「結束」「exit」這類詞就能直接結束對話，預設詞會隨介面語言變化，也可以在設定裡自行修改。
- **面板不遮通知**：語音面板是貼齊底部的卡片，頂端狀態列留給系統，對話過程中照樣可以下拉通知列、點選核准。
- **進行中的狀態**：對話期間狀態列會保留一則通知，點它可回到對話面板；面板在前景顯示「語音對話進行中」，切到背景則變成「點按回到語音對話」，對話結束後通知會自動消失。

### 語音辨識（STT）

四種後端，依需求選擇：

| 後端 | 說明 |
| --- | --- |
| 系統辨識 | 使用手機內建的辨識服務，零設定 |
| OpenAI 相容 | 自行架設 `/v1/audio/transcriptions` 服務，批次上傳 |
| FunASR 串流 | 本機 FunASR，邊說邊出字 |
| 華為 HMS（ML Kit） | 裝置端辨識，需要 HMS Core 和 AGC 的 API Key；**只支援中文和英文** |

辨識鏈上有兩層備援：整段都沒有字時的靜音超時，以及華為 SDK 在 final 幀沒有回呼時由用戶端補一次收尾。

### 語音合成（TTS）

| 供應商 | 說明 |
| --- | --- |
| 本機（系統 TTS） | 預設；未明確選擇引擎時優先使用 Google TTS，其次是系統預設 |
| OpenAI 相容 | `POST {base}/v1/audio/speech`，位址、模型、音色皆可設定 |
| 華為 HMS（ML Kit） | **雲端**合成，需要連網和 AGC API Key，每月前 100 萬字元免費；只支援中文和英文 |
| ElevenLabs | 音色清單隨語言在地化 |
| VOICEVOX | 僅 `full` 建置包含 |

系統中完全沒有 TTS 引擎時會彈出視窗提供兩條路：安裝 Google TTS，或安裝開源的 sherpa-onnx 引擎。

### 後端與連線方式

支援三類後端，語音和聊天各自獨立選擇要走哪一條：

| 類型 | 協定 | 用途 |
| --- | --- | --- |
| Hermes Agent | HTTP（`/v1/runs` 或 `/v1/chat/completions`） | 主要使用；支援系統提示詞、串流輸出、工作階段 |
| OpenClaw 閘道 | WebSocket 節點協定 | 手機以節點身分接入，閘道可直接下發工具呼叫 |
| OpenClaw HTTP | OpenAI 相容 HTTP | 不需長連線的輕量通道 |

- **語音工作階段連線**三選一：`OpenClaw 閘道` / `OpenClaw HTTP` / `Hermes HTTP`，首頁「語音對話」頂端和設定裡各有一份；未手動選擇過時會跟隨主要後端。
- **文字聊天**依工作階段所選的後端類型，不看上面這項設定。
- **提示詞**：語音和文字聊天各有一套系統提示詞，可在「設定 → 聊天」中修改，留空則回退到內建預設。
- **語音會附帶現場資訊**：時間、位置、電量、網路、螢幕狀態會隨每次語音請求一起送出，助理能直接回答「現在幾點」「手機還剩多少電」。
- **服務自動復原**：語音服務與系統的繫結若中斷，會進行三級重試——確認預設助理、重新要求系統繫結、最後重新啟動程序。

### 手機控制（Mobile Bridge）

手機本身會啟動一個本機 HTTP 服務（預設 8787），Hermes 透過它反向呼叫手機能力：讀取螢幕與介面操作（`screen.tap/swipe/long_press/drag/back/home`、`screen.find_nodes`、`screen.window.describe`）、截圖（兩段式，JPEG）、拍照、定位、簡訊、聯絡人、行事曆、通知讀取與清除、剪貼簿、媒體控制（`media.play_pause/next/previous`，另有 `media.status` 讀取曲名、歌手、專輯與播放進度）、音量與亮度、應用程式清單（`apps.list`，列出有桌面入口的應用並標示 `system` / `launchable`）與啟動（`apps.launch`，啟動後核對前景是否真的切換）。

- 一次性配對：在 App 裡開啟橋接後會出現 6 位數配對碼，電腦端用配對碼換取 token，之後免配對。
- 授權與核准：依能力分組授權，高風險動作（傳簡訊、拍照、介面操作）會走確認彈出視窗，另有速率限制。
- 只建議在區域網路或 Tailscale 內開啟；繫結模式要選 LAN/VPN 才連得進來。
- **它是選用元件**：走 OpenClaw 閘道時，閘道本身就能下發手機能力，橋接不必開啟。

### 其他

- **終端機**：內建終端機連線到電腦上的 Hermes dashboard，需要 `hermes dashboard --tui`。
- **排程工作**：檢視、新建、編輯 Hermes 的 cron 工作，支援填入腳本（相對於 `HERMES_HOME/scripts/`）。
- **診斷**：首頁和設定裡都有前置檢查，缺少權限、語音服務未繫結、後端未設定、無障礙未開啟都會逐條列出並提供處理位置。
- **深色模式**：所有介面都跟隨系統深淺色，包括設定精靈。
- **10 種介面語言**：每種語言 1092 條字串全部齊備，不會混到英文。

---

## 安裝

從 [Releases](https://github.com/pengtb/wakehermesclaw/releases) 下載 APK 直接安裝（套件名稱 `com.openclaw.assistant`）。

安裝完在 App 裡做三件事：

1. **新增後端**：在「設定 → 連線」填入 Hermes 的位址和 token（或 OpenClaw 閘道的主機 / 連接埠）。
2. **設為數位助理**：把本應用程式設為預設助理，否則長按導覽列會喚不起來。
3. **給予權限**：麥克風為必需；定位、相機、簡訊、通訊錄、行事曆依你要用的能力給予；想做手機控制再開啟無障礙服務。 sideload 安裝的套件要先在「應用程式資訊 → ⋮ → 允許受限制的設定」放行，否則系統不允許開啟無障礙。

語音辨識和合成預設使用系統能力，不設定也能出聲、能辨識。

---

## 配對

### Hermes 後端

`integrations/agentvoice-pair/` 裡有一個桌面小工具，PowerShell 與 bash 兩版等價、零依賴（QR code 編碼與 PNG 寫入都在腳本裡）。它會探測本機的 Hermes API 位址（優先 Tailscale Serve，其次區域網路）、API key，以及 dashboard 的 Terminal 位址與 session token，產生一張配對 QR code：

```bash
bash install.sh                # 裝成 agentvoice-pair；Windows 下會裝 .ps1 並產生 .cmd 包裝
agentvoice-pair --open --svg   # 產生 QR code 並開啟
```

QR code 裡是一條深層連結：

```
agentvoice://setup?hu=<API位址>&hk=<key>&hm=<profile>&hr=1&hs=1&htu=<dashboard位址>&htt=<session token>
```

`hu` / `hk` 是 Hermes 位址與 API key，`hm` 是目標 profile，`hr=1` 表示預設走 Runs API，`hs=1` 表示串流，`htu` / `htt` 是終端機用的 dashboard 位址與 session token。`hu` 可以重複給出，多個位址會作為候選並行測速。手機端三條路都行：系統相機掃碼（點開連結回到 App）、App 內掃碼，或在設定精靈裡直接貼上這段 JSON / 深層連結。

QR code 內含 API key 與 session token，等同密碼，不要外流。

### Mobile Bridge

1. 手機上開啟「設定 → 行動橋接」，開啟後頁面會顯示 6 位數配對碼，以及一條 `agentvoice://pair?u=<橋接位址>&c=<配對碼>` 深層連結。
2. 電腦上執行 `agentvoice-pair --code <配對碼> --write-env <路徑>`，或直接 `POST {"code":"<配對碼>"}` 到 `http://<手機>:8787/pair`。
3. 換回來的 token 寫進 `.env` 的 `AGENT_VOICE_BRIDGE_URL` / `AGENT_VOICE_BRIDGE_TOKEN`；之後每次呼叫都在 `Authorization` 標頭裡帶上 `Bearer` 和這個 token。

配對碼一次性、帶有效期限。拿到 token 後不再需要配對，但傳簡訊、拍照、點按這類高風險能力仍然每次都會跳出核准。

---

## 從原始碼建置

需要 JDK 17 和 Android SDK 35。

```bash
./gradlew assembleStandardDebug     # 偵錯包（套件名稱多一個 .debug，可與正式版共存）
./gradlew assembleFullDebug         # 含 VOICEVOX 的偵錯包
./gradlew assembleStandardRelease   # 正式版
```

產物在 `app/build/outputs/apk/<flavor>/<buildType>/`。

幾個建置上的注意事項：

- `app/google-services.json` 不納入版本控制。沒有真實 Firebase 設定時要用 `FIREBASE_ENABLED=false` 產出安裝檔，否則會以 mock 設定初始化 Firebase。
- release 建置需要本機簽章設定（`local.properties` 裡的 `storeFile / storePassword / keyAlias / keyPassword`）。
- release 建置要把 `uploadCrashlyticsMappingFileStandardRelease` 排除掉，否則會去連 Firebase。
- R8 需要放行 HMS SDK 引用的裝置端類別（`com.huawei.libcore.*`、`org.chromium.net.*`），規則已在 `app/proguard-rules.pro`。
- 華為的 key 從 `local.properties` 的 `hms.asr.key / hms.asr.appid / hms.asr.cert` 注入 `BuildConfig`，不納入版本控制；release 版不含 TTS 的 key，需要使用者在 App 內自行填入。

---

## 程式碼結構

```
app/src/main/java/com/openclaw/assistant/
├── speech/      辨識（系統 / OpenAI 相容 / FunASR / HMS）、合成（本機 / ElevenLabs / OpenAI 相容 / HMS / VOICEVOX）、退出詞
├── backend/     後端抽象與派送：Hermes HTTP、OpenClaw 閘道、OpenClaw HTTP
├── service/     語音互動服務與對話面板、喚醒詞、磁貼、通知、自動復原
├── bridge/      Mobile Bridge：HTTP 服務、能力實作、無障礙、核准與授權
├── node/        閘道節點協定與執行環境
├── ui/          首頁、聊天、設定、終端機、排程工作、診斷
└── data/        設定與本機儲存
```

- `AGENT.md` 是給維護者看的：踩過的坑、為什麼這樣寫、哪些地方一改就會無聲失效。
- `docs/hermes-mobile-bridge.md` 記錄橋接協定與配對流程。
- `integrations/` 放桌面端的配對工具。

---

## 相對原版改了什麼

上游是 `yuga-hashimoto/openclaw-assistant`。本分支在其上所做的改動，按功能分組：

**Hermes 後端**

- 把 Hermes Agent 接成一等後端，`/v1/runs` 與 `/v1/chat/completions` 都能用，工作階段、串流、系統提示詞都通。Runs API 的 system 訊息必須放在頂層 `instructions`（放在 `input` 裡伺服器不讀），這點已在用戶端補齊。
- 連線方式三選一：OpenClaw 閘道 / OpenClaw HTTP / Hermes HTTP，首頁和設定各放一份，依選擇嚴格比對，不再無聲回退。
- 位址沒寫 `/v1/` 時自動補全，修掉舊設定移轉後一送出訊息就 404。

**語音**

- 辨識改成可插拔多後端：系統 / OpenAI 相容批次 / FunASR 串流 / 華為 HMS，移除原本的百度後端。
- 新增華為 HMS 雲端合成；本機完全沒有 TTS 引擎時提供安裝引導。
- 語音與文字聊天的系統提示詞分開、可修改，留空則回退預設。
- 語音請求自動附帶時間、位置、電量、網路、螢幕狀態。
- 退出詞：說中即結束目前工作階段，預設詞隨介面語言。
- 喚起方式多三條路：快速設定磁貼、`VOICE_COMMAND` 跳板、靜態捷徑。
- 語音服務與系統的繫結若中斷，會三級自動復原：確認預設助理 → 重新要求繫結 → 重新啟動程序。
- 面板改成貼齊底部的卡片，不再遮住通知列；對話中的通知隨前景/背景變換文案，點按可回到面板，結束即消失。

**手機控制**

- 截圖與拍照都做成兩段式：先回傳中繼資料與短時 id，再依 id 取 base64；影像只存在記憶體，5 分鐘過期，不寫入磁碟。
- 拍照用「核准即武裝」的相機前景服務，繞開 Android 12 起不能在背景啟動相機服務的限制。
- 傳簡訊改為等待 `sentIntent` 結果再回傳，給出的是真實結果碼。
- 無障礙手勢改在主執行緒派送並等待回呼，修掉 tap / swipe 無聲失效；`screen.window.describe` 依作用中視窗取得，套件名稱不再誤報。
- 能力分組新增 `location` / `screen`；橋接狀態卡分成「目前在 / 擬在」兩行，改完設定可直接對照。
- 新增 `media.status`：讀取目前播放的應用程式、曲名、歌手、專輯、進度與狀態，搭配原有三個傳輸控制鍵。
- `apps.list` 改成依桌面入口列舉並標示 `system` / `launchable`（在 `<queries>` 宣告 MAIN/LAUNCHER，不依賴 `QUERY_ALL_PACKAGES`）。
- `apps.launch` 啟動後核對前景是否真的切換，不再無條件回報成功。
- 橋接的 accept 迴圈不再因一次 `IOException` 退出，避免出現「連接埠在聽、連線全部逾時」的假死。

**日常使用**

- 首頁、設定、精靈三處都有前置檢查，涵蓋執行時權限與無障礙服務，每條都帶「去處理」。
- 10 種介面語言全部 1092 條翻譯完成，語言清單中可選。
- 深色模式涵蓋設定精靈與後端管理頁。
- 終端機補上 dashboard 位址與 session token 欄位；排程工作補腳本列，並修掉 interval / once 工作因 `expr` 必填導致整個清單反序列化失敗的問題。
- 設定頁底部顯示建置短雜湊，同一版本號下的不同產物能分開辨識。

改動明細可以對照 `git log` 查看；架構上的取捨與踩過的坑寫在 `AGENT.md`。

---

## 已知限制

- `brightness.set` 需要 `WRITE_SETTINGS`，未授權時該能力不會顯示。
- 自行編譯的套件（套件名稱不同）拿不到 Google Play 的 ML Kit 掃碼模組，App 內掃碼無法使用；可改用系統相機掃描深層連結 QR code 繞過。
- 華為 HMS 的辨識與合成只支援中文和英文。
- OpenClaw 閘道與 OpenClaw HTTP 兩條通道在作者的環境裡沒有伺服器可測試，未做過實機驗證。

---

## 致謝

這個專案建立在兩個開源專案之上：

- [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant)（作者 yuga-hashimoto，MIT）——本分支的起點，介面、語音互動、閘道與節點能力都來自它。上游原本的 README 已由這份文件取代。
- [gpt-assistant-android](https://github.com/Skythinker616/gpt-assistant-android)（作者 Skythinker616，GPL-3.0）——語音辨識層「統一介面 + 多後端」的結構參考了它的 `AsrClientBase` 設計，華為 HMS ML Kit 的接入方式也參考了該專案。這裡依同樣思路自行實作，未複製其程式碼。

## 授權

MIT，見 [LICENSE](LICENSE)。原始著作權聲明 Copyright (c) 2026 [yuga-hashimoto](https://github.com/yuga-hashimoto) 保留在倉庫中；本分支新增的改動同樣以 MIT 釋出。第三方元件與隨包資源見 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
