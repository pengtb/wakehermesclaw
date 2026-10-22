# WakeHermesClaw

휴대폰을 언제든 불러낼 수 있는 AI 어시스턴트로 만들어 줍니다: 웨이크 워드를 말하거나, 빠른 설정 타일을 누르거나, 내비게이션 바를 길게 누르거나, 이어폰 버튼을 한 번 누르면 음성 패널이 올라옵니다. 말한 내용은 컴퓨터에서 실행 중인 백엔드(Hermes Agent 또는 OpenClaw)로 전달되고, 답변은 음성으로 읽어 줍니다. 말하기뿐 아니라 어시스턴트가 이 휴대폰을 역으로 조작할 수도 있습니다 — 화면 읽기, 탭, 사진 촬영, 문자 발송, 위치 확인.

이 프로젝트는 [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant)를 기반으로 만든 개인용 브랜치이며, 주된 작업은 Hermes Agent를 연결하고 음성 파이프라인, 백엔드 선택, 휴대폰 제어를 완성하는 것이었습니다.

- 최신 버전: v2.5.2 ([Releases](https://github.com/pengtb/wakehermesclaw/releases))
- 시스템 요구 사항: Android 8.0 이상(minSdk 26), targetSdk 34
- 인터페이스 언어: 简体中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Español / Français / हिन्दी / Русский

[English](README.en.md) · [简体中文](README.md) · [繁體中文](README.zh-TW.md) · [日本語](README.ja.md) · 한국어 · [Deutsch](README.de.md) · [Español](README.es.md) · [Français](README.fr.md) · [हिन्दी](README.hi.md) · [Русский](README.ru.md)

---

## 스크린샷

| 홈 | 음성 대화 | 대화 목록 |
| :---: | :---: | :---: |
| <img src="docs/images/home.jpg" width="220"> | <img src="docs/images/voice_overlay.jpg" width="220"> | <img src="docs/images/conversations.jpg" width="220"> |
| **캔버스** | **설정** | **챗** |
| <img src="docs/images/canvas.jpg" width="220"> | <img src="docs/images/settings.jpg" width="220"> | <img src="docs/images/chat.jpg" width="220"> |

---

## 무엇을 하는가

### 음성 대화

- **네 가지 호출 방법**: 웨이크 워드, 빠른 설정 타일, 시스템 어시스턴트 제스처(내비게이션 바 길게 누르기 / 전원 버튼), 앱 정적 바로가기. 앱 안의 「음성 대화」를 직접 눌러도 됩니다.
- **연속 대화**: 답변이 끝나면 자동으로 계속 듣습니다. 누르면 말하기를 시작하고 다시 누르면 끝내고 전송하는 워키토키 모드로 바꿀 수도 있습니다.
- **종료 단어**: 「종료」「exit」같은 단어를 말하면 대화가 바로 끝납니다. 기본 단어는 인터페이스 언어에 따라 달라지며 설정에서 직접 바꿀 수 있습니다.
- **패널이 알림을 가리지 않음**: 음성 패널은 하단에 붙는 카드 형태이고 상단 상태 표시줄은 시스템에 남겨 두므로, 대화 중에도 알림창을 내리거나 승인을 누를 수 있습니다.
- **진행 중 상태**: 대화 중에는 상태 표시줄에 알림이 하나 남아 있고, 그것을 누르면 대화 패널로 돌아옵니다. 패널이 포그라운드일 때는 「음성 대화 진행 중」, 백그라운드로 가면 「탭하여 음성 대화로 돌아가기」로 바뀌며, 대화가 끝나면 알림이 자동으로 사라집니다.

### 음성 인식(STT)

네 가지 백엔드 중에서 필요에 맞게 선택합니다:

| 백엔드 | 설명 |
| --- | --- |
| 시스템 인식 | 휴대폰에 내장된 인식 서비스를 사용하며 설정이 필요 없습니다 |
| OpenAI 호환 | `/v1/audio/transcriptions` 서비스를 직접 띄워 배치 방식으로 업로드합니다 |
| FunASR 스트리밍 | 로컬 FunASR, 말하는 대로 글자가 나옵니다 |
| 화웨이 HMS(ML Kit) | 기기 측 인식, HMS Core와 AGC의 API Key가 필요하며 **중국어와 영어만 지원합니다** |

인식 파이프라인에는 두 가지 안전장치가 있습니다: 전체 구간에 글자가 없을 때의 무음 타임아웃, 그리고 화웨이 SDK가 final 프레임에서 콜백하지 않을 때 클라이언트가 한 번 마무리해 주는 처리입니다.

### 음성 합성(TTS)

| 제공자 | 설명 |
| --- | --- |
| 로컬(시스템 TTS) | 기본값. 엔진을 명시적으로 고르지 않으면 Google TTS를 우선하고, 다음으로 시스템 기본값을 사용합니다 |
| OpenAI 호환 | `POST {base}/v1/audio/speech`, 주소·모델·음색 설정 가능 |
| 화웨이 HMS(ML Kit) | **클라우드** 합성으로 네트워크와 AGC API Key가 필요하며 매월 첫 100만 자까지 무료입니다. 중국어와 영어만 지원합니다 |
| ElevenLabs | 음색 목록이 언어에 맞게 현지화됩니다 |
| VOICEVOX | `full` 빌드에만 포함됩니다 |

시스템에 TTS 엔진이 하나도 없으면 두 가지 방법을 안내하는 대화상자가 뜹니다: Google TTS를 설치하거나, 오픈소스 sherpa-onnx 엔진을 설치하는 것입니다.

### 백엔드와 연결 방식

세 종류의 백엔드를 지원하며, 음성과 채팅이 각각 독립적으로 어느 쪽을 쓸지 선택합니다:

| 유형 | 프로토콜 | 용도 |
| --- | --- | --- |
| Hermes Agent | HTTP(`/v1/runs` 또는 `/v1/chat/completions`) | 주로 사용. 시스템 프롬프트, 스트리밍 출력, 세션 지원 |
| OpenClaw 게이트웨이 | WebSocket 노드 프로토콜 | 휴대폰이 노드로 접속하며, 게이트웨이가 도구 호출을 직접 내려보낼 수 있습니다 |
| OpenClaw HTTP | OpenAI 호환 HTTP | 긴 연결이 필요 없는 경량 채널 |

- **음성 세션 연결**은 셋 중 하나입니다: `OpenClaw 게이트웨이` / `OpenClaw HTTP` / `Hermes HTTP`. 홈의 「음성 대화」 상단과 설정에 각각 하나씩 있으며, 직접 선택한 적이 없으면 주 백엔드를 따릅니다.
- **텍스트 채팅**은 세션이 선택한 백엔드 유형을 따르며, 위의 설정과는 무관합니다.
- **프롬프트**: 음성과 텍스트 채팅에 각각 시스템 프롬프트가 하나씩 있고 「설정 → 채팅」에서 바꿀 수 있으며, 비워 두면 내장 기본값으로 돌아갑니다.
- **음성에는 현장 정보가 함께 전달됩니다**: 시간, 위치, 배터리, 네트워크, 화면 상태가 매 음성 요청과 함께 전송되어 어시스턴트가 「지금 몇 시야」「휴대폰 배터리 얼마나 남았어」에 바로 답할 수 있습니다.
- **서비스 자가 복구**: 음성 서비스와 시스템의 바인딩이 끊기면 3단계로 재시도합니다 — 기본 어시스턴트 확인, 시스템 바인딩 재요청, 마지막으로 프로세스 재시작.

### 휴대폰 제어(Mobile Bridge)

휴대폰이 자체적으로 로컬 HTTP 서비스(기본 8787)를 띄우고, Hermes가 이를 통해 휴대폰 기능을 역으로 호출합니다: 화면 읽기와 인터페이스 조작(`screen.tap/swipe/long_press/drag/back/home`, `screen.find_nodes`, `screen.window.describe`), 스크린샷(2단계, JPEG), 카메라 촬영, 위치, 문자, 연락처, 캘린더, 알림 읽기와 지우기, 클립보드, 미디어 제어(`media.play_pause/next/previous`, 그리고 `media.status`로 곡명·아티스트·앨범·재생 위치), 볼륨과 밝기, 앱 목록(`apps.list`, 런처 진입점이 있는 앱을 `system` / `launchable` 표시와 함께 나열)과 실행(`apps.launch`, 실행 후 포그라운드가 실제로 바뀌었는지 확인).

- 일회성 페어링: 앱에서 브리지를 켜면 6자리 페어링 코드가 나타나고, 컴퓨터에서 페어링 코드로 token을 교환하면 이후에는 페어링이 필요 없습니다.
- 권한 부여와 승인: 기능별 그룹 단위로 권한을 부여하고, 고위험 동작(문자 발송, 사진 촬영, 인터페이스 조작)은 확인 대화상자를 거치며 별도의 속도 제한이 있습니다.
- LAN 또는 Tailscale 안에서만 켜는 것을 권장합니다. 바인딩 모드를 LAN/VPN으로 선택해야 접속할 수 있습니다.
- **선택 사항입니다**: OpenClaw 게이트웨이를 쓰면 게이트웨이 자체가 휴대폰 기능을 내려보낼 수 있어 브리지를 켜지 않아도 됩니다.

### 기타

- **터미널**: 내장 터미널이 컴퓨터의 Hermes dashboard에 연결되며 `hermes dashboard --tui`가 필요합니다.
- **예약 작업**: Hermes의 cron 작업을 조회·생성·편집할 수 있고, 스크립트 입력도 지원합니다(`HERMES_HOME/scripts/` 기준 상대 경로).
- **진단**: 홈과 설정에 사전 점검이 있으며, 권한 누락, 음성 서비스 미바인딩, 백엔드 미설정, 접근성 미활성화를 항목별로 나열하고 해결 방법을 안내합니다.
- **다크 모드**: 설정 마법사를 포함한 모든 화면이 시스템의 밝기/어두운 테마를 따릅니다.
- **10가지 인터페이스 언어**: 각 언어마다 1092개 문구가 모두 채워져 있어 영어가 섞이지 않습니다.

---

## 설치

[Releases](https://github.com/pengtb/wakehermesclaw/releases)에서 APK를 내려받아 바로 설치하면 됩니다(패키지 이름 `com.openclaw.assistant`).

설치 후 앱에서 세 가지를 하면 됩니다:

1. **백엔드 추가**: 「설정 → 연결」에서 Hermes의 주소와 token(또는 OpenClaw 게이트웨이의 호스트 / 포트)을 입력합니다.
2. **디지털 어시스턴트로 설정**: 이 앱을 기본 어시스턴트로 지정해야 합니다. 그렇지 않으면 내비게이션 바 길게 누르기로 호출할 수 없습니다.
3. **권한 부여**: 마이크는 필수입니다. 위치, 카메라, 문자, 연락처, 캘린더는 사용할 기능에 맞게 부여하고, 휴대폰 제어를 하려면 접근성 서비스를 켜세요. 사이드로드로 설치한 앱은 '앱 정보 → ⋮ → 제한된 설정 허용'을 먼저 해야 접근성 서비스를 켤 수 있습니다.

음성 인식과 합성은 기본적으로 시스템 기능을 사용하므로, 따로 설정하지 않아도 소리가 나고 인식이 됩니다.

---

## 페어링

### Hermes 백엔드

`integrations/agentvoice-pair/` 에는 데스크톱용 작은 도구가 있습니다. PowerShell 버전과 bash 버전이 동등하고 의존성이 없습니다(QR 코드 인코딩과 PNG 기록 모두 스크립트 안에서 처리합니다). 이 도구는 이 PC의 Hermes API 주소(Tailscale Serve를 우선하고, 다음으로 LAN), API key, 그리고 dashboard의 Terminal 주소와 session token을 탐지해 페어링 QR 코드 한 장을 생성합니다:

```bash
bash install.sh                # agentvoice-pair 로 설치됩니다. Windows 에서는 .ps1 을 설치하고 .cmd 래퍼를 생성합니다
agentvoice-pair --open --svg   # QR 코드를 생성하고 엽니다
```

QR 코드 안에는 딥링크가 하나 들어 있습니다:

```
agentvoice://setup?hu=<API 주소>&hk=<key>&hm=<profile>&hr=1&hs=1&htu=<dashboard 주소>&htt=<session token>
```

`hu` / `hk` 는 Hermes 주소와 API key, `hm` 은 대상 profile, `hr=1` 은 기본적으로 Runs API를 사용한다는 뜻, `hs=1` 은 스트리밍, `htu` / `htt` 는 터미널에서 쓰는 dashboard 주소와 session token입니다. `hu` 는 여러 번 줄 수 있으며, 여러 주소는 후보로 두고 동시에 속도를 측정합니다. 휴대폰에서는 세 가지 방법이 모두 가능합니다: 시스템 카메라로 스캔(링크를 열면 앱으로 돌아옵니다), 앱 내 스캔, 또는 설정 마법사에서 이 JSON / 딥링크를 직접 붙여넣기.

QR 코드에는 API key와 session token이 들어 있어 비밀번호와 같으므로 외부에 공유하지 마세요.

### Mobile Bridge

1. 휴대폰에서 「설정 → 모바일 브리지」를 열고 켜면 페이지에 6자리 페어링 코드와 `agentvoice://pair?u=<브리지 주소>&c=<페어링 코드>` 딥링크가 표시됩니다.
2. 컴퓨터에서 `agentvoice-pair --code <페어링 코드> --write-env <경로>`를 실행하거나, `POST {"code":"<페어링 코드>"}`를 `http://<휴대폰>:8787/pair`로 직접 보냅니다.
3. 돌려받은 token을 `.env`의 `AGENT_VOICE_BRIDGE_URL` / `AGENT_VOICE_BRIDGE_TOKEN`에 기록하고, 이후 모든 호출은 `Authorization` 헤더에 `Bearer` 와 토큰을 함께 담아 보냅니다.

페어링 코드는 일회성이며 유효 기간이 있습니다. token을 받은 뒤에는 페어링이 필요 없지만, 문자 발송·사진 촬영·탭 같은 고위험 기능은 여전히 매번 승인을 요구합니다.

---

## 소스에서 빌드

JDK 17과 Android SDK 35가 필요합니다.

```bash
./gradlew assembleStandardDebug     # 디버그 패키지(패키지 이름에 .debug가 붙어 정식 패키지와 공존 가능)
./gradlew assembleFullDebug         # VOICEVOX가 포함된 디버그 패키지
./gradlew assembleStandardRelease   # 정식 패키지
```

산출물은 `app/build/outputs/apk/<flavor>/<buildType>/`에 있습니다.

빌드 시 주의할 점 몇 가지:

- `app/google-services.json`은 저장소에 포함되지 않습니다. 실제 Firebase 설정이 없으면 `FIREBASE_ENABLED=false`로 빌드해야 하며, 그렇지 않으면 mock 설정으로 Firebase를 초기화합니다.
- release 빌드에는 로컬 서명 설정(`local.properties`의 `storeFile / storePassword / keyAlias / keyPassword`)이 필요합니다.
- release 빌드에서는 `uploadCrashlyticsMappingFileStandardRelease`를 제외해야 합니다. 그렇지 않으면 Firebase에 연결을 시도합니다.
- R8은 HMS SDK가 참조하는 기기 측 클래스(`com.huawei.libcore.*`, `org.chromium.net.*`)를 예외 처리해야 하며, 규칙은 이미 `app/proguard-rules.pro`에 있습니다.
- 화웨이 key는 `local.properties`의 `hms.asr.key / hms.asr.appid / hms.asr.cert`에서 `BuildConfig`로 주입되며 저장소에 포함되지 않습니다. release 패키지에는 TTS key가 없으므로 사용자가 앱에서 직접 입력해야 합니다.

---

## 코드 구조

```
app/src/main/java/com/openclaw/assistant/
├── speech/      인식(시스템 / OpenAI 호환 / FunASR / HMS), 합성(로컬 / ElevenLabs / OpenAI 호환 / HMS / VOICEVOX), 종료 단어
├── backend/     백엔드 추상화와 디스패치: Hermes HTTP, OpenClaw 게이트웨이, OpenClaw HTTP
├── service/     음성 인터랙션 서비스와 세션 패널, 웨이크 워드, 타일, 알림, 자가 복구
├── bridge/      Mobile Bridge: HTTP 서비스, 기능 구현, 접근성, 승인과 권한 부여
├── node/        게이트웨이 노드 프로토콜과 런타임
├── ui/          홈, 채팅, 설정, 터미널, 예약 작업, 진단
└── data/        설정과 로컬 저장소
```

- `AGENT.md`는 유지보수자를 위한 문서입니다: 겪었던 함정, 왜 이렇게 작성했는지, 어디를 고치면 조용히 동작하지 않는지.
- `docs/hermes-mobile-bridge.md`는 브리지 프로토콜과 페어링 절차를 기록합니다.
- `integrations/`에는 데스크톱용 페어링 도구가 있습니다.

---

## 원본에서 바뀐 점

업스트림은 `yuga-hashimoto/openclaw-assistant`입니다. 이 브랜치가 그 위에서 한 변경을 기능별로 묶으면 다음과 같습니다:

**Hermes 백엔드**

- Hermes Agent를 일급 백엔드로 연결했습니다. `/v1/runs`와 `/v1/chat/completions`를 모두 쓸 수 있고 세션, 스트리밍, 시스템 프롬프트가 모두 동작합니다. Runs API의 system 메시지는 최상위 `instructions`에 넣어야 하며(`input`에 넣으면 서버가 읽지 않습니다), 이 부분은 클라이언트에서 보완했습니다.
- 연결 방식은 셋 중 하나입니다: OpenClaw 게이트웨이 / OpenClaw HTTP / Hermes HTTP. 홈과 설정에 각각 하나씩 두고 선택한 값과 엄격히 일치시키며, 더 이상 조용히 되돌아가지 않습니다.
- 주소에 `/v1/`이 없으면 자동으로 채워 넣어, 예전 설정을 마이그레이션한 뒤 메시지를 보내면 404가 나던 문제를 고쳤습니다.

**음성**

- 인식을 교체 가능한 다중 백엔드로 바꿨습니다: 시스템 / OpenAI 호환 배치 / FunASR 스트리밍 / 화웨이 HMS. 기존의 바이두 백엔드는 제거했습니다.
- 화웨이 HMS 클라우드 합성을 추가했고, 기기에 TTS 엔진이 하나도 없을 때는 설치 안내를 제공합니다.
- 음성과 텍스트 채팅의 시스템 프롬프트를 분리해 수정할 수 있게 했고, 비워 두면 기본값으로 돌아갑니다.
- 음성 요청에 시간, 위치, 배터리, 네트워크, 화면 상태를 자동으로 함께 보냅니다.
- 종료 단어: 말하면 현재 세션이 바로 끝나며, 기본 단어는 인터페이스 언어를 따릅니다.
- 호출 경로가 세 가지 늘었습니다: 빠른 설정 타일, `VOICE_COMMAND` 점프보드, 정적 바로가기.
- 음성 서비스와 시스템의 바인딩이 끊기면 3단계로 자가 복구합니다: 기본 어시스턴트 확인 → 바인딩 재요청 → 프로세스 재시작.
- 패널을 하단에 붙는 카드로 바꿔 알림창을 가리지 않게 했습니다. 대화 중 알림은 포그라운드/백그라운드에 따라 문구가 바뀌고, 누르면 패널로 돌아오며 끝나면 사라집니다.

**휴대폰 제어**

- 화면 캡처와 사진 촬영을 모두 2단계로 만들었습니다: 먼저 메타데이터와 단기 id를 반환하고, id로 base64를 가져옵니다. 이미지는 메모리에만 보관하고 5분 후 만료되며 디스크에 남기지 않습니다.
- 사진 촬영은 「승인 즉시 무장」하는 카메라 포그라운드 서비스를 사용해, Android 12부터 백그라운드에서 카메라 서비스를 시작할 수 없는 제한을 우회했습니다.
- 문자 발송은 `sentIntent` 결과를 기다린 뒤 반환하도록 바꿔 실제 결과 코드를 돌려줍니다.
- 접근성 제스처를 메인 스레드에서 디스패치하고 콜백을 기다리도록 바꿔 tap / swipe가 조용히 실패하던 문제를 고쳤고, `screen.window.describe`는 활성 창 기준으로 가져와 패키지 이름을 더 이상 잘못 보고하지 않습니다.
- 기능 그룹에 `location` / `screen`을 추가했고, 브리지 상태 카드를 「현재 / 예정」두 줄로 나눠 설정을 바꾼 뒤 바로 대조할 수 있습니다.
- `media.status` 추가: 재생 중인 앱, 곡명, 아티스트, 앨범, 재생 위치와 상태를 읽고 기존 세 개의 조작 키와 함께 씁니다.
- `apps.list`는 런처 진입점 기준으로 나열하고 `system` / `launchable`을 표시합니다(`<queries>`에 MAIN/LAUNCHER 선언, `QUERY_ALL_PACKAGES` 불필요).
- `apps.launch`는 실행 후 포그라운드가 실제로 바뀌었는지 확인하며, 무조건 성공을 반환하지 않습니다.
- 브리지 accept 루프가 `IOException` 한 번으로 종료되지 않게 해 「포트는 열려 있는데 연결이 모두 시간 초과」되는 상태를 없앴습니다.

**일상 사용**

- 홈, 설정, 마법사 세 곳에 사전 점검이 있으며 런타임 권한과 접근성 서비스를 모두 포함하고, 각 항목에 「처리하기」가 붙습니다.
- 10가지 인터페이스 언어의 1092개 문구를 모두 번역해 언어 목록에서 선택할 수 있습니다.
- 다크 모드가 설정 마법사와 백엔드 관리 페이지까지 적용됩니다.
- 터미널에 dashboard 주소와 session token 필드를 추가했고, 예약 작업에는 스크립트 입력란을 추가했으며, interval / once 작업이 `expr` 필수 때문에 목록 전체 역직렬화에 실패하던 문제를 고쳤습니다.
- 설정 페이지 하단에 빌드 짧은 해시를 표시해 같은 버전 번호의 서로 다른 산출물을 구분할 수 있습니다.

변경 내역은 `git log`로 확인할 수 있고, 아키텍처상의 선택과 겪었던 함정은 `AGENT.md`에 정리되어 있습니다.

---

## 알려진 제한 사항

- `apps.launch`로 백그라운드에서 Activity를 시작하면 Android 10+의 백그라운드 시작 제한에 걸릴 수 있고, 통과 여부는 시스템에 따라 다릅니다. 이제 시작 후 검증합니다: 접근성이 켜져 있으면 `verified`와 실제 포그라운드 패키지를, 막히면 `BAL_BLOCKED`를, 접근성이 꺼져 있으면 `verified:false`를 반환합니다. 작성자의 Android 16 기기에서는 막히지 않았고 설정·NetEase Cloud Music·WeChat 모두 백그라운드에서 실행됐습니다.
- `brightness.set`에는 `WRITE_SETTINGS`가 필요하며, 권한이 없으면 해당 기능이 보이지 않습니다.
- 자체 빌드 패키지(패키지 이름이 다름)는 Google Play의 ML Kit 스캔 모듈을 받을 수 없어 앱 내 스캔을 사용할 수 없습니다. 시스템 카메라로 딥링크 QR 코드를 스캔하면 우회할 수 있습니다.
- 화웨이 HMS의 인식과 합성은 중국어와 영어만 지원합니다.
- OpenClaw 게이트웨이와 OpenClaw HTTP 두 채널은 작성자의 환경에 테스트할 서버가 없어 실제 기기 검증을 하지 못했습니다.

---

## 감사의 말

이 프로젝트는 두 오픈소스 프로젝트 위에 서 있습니다.

- [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant) (제작자 yuga-hashimoto, MIT) — 이 포크의 출발점이며, UI·음성 상호작용·게이트웨이·노드 기능이 여기서 비롯했습니다. 업스트림의 원래 README는 이 문서로 대체했습니다.
- [gpt-assistant-android](https://github.com/Skythinker616/gpt-assistant-android) (제작자 Skythinker616, GPL-3.0) — 음성 인식 계층의 "공통 인터페이스 + 다중 백엔드" 구조는 해당 프로젝트의 `AsrClientBase` 설계를 참고했고, Huawei HMS ML Kit 연동 방식도 참고했습니다. 구현은 같은 방식을 따라 직접 작성했으며 코드를 복사하지 않았습니다.

## 라이선스

MIT, [LICENSE](LICENSE) 참조. 원 저작권 표시 Copyright (c) 2026 [yuga-hashimoto](https://github.com/yuga-hashimoto) 는 저장소에 유지되며, 이 포크에서 추가한 변경도 MIT 로 배포합니다. 서드파티 구성 요소와 함께 배포되는 리소스는 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) 에 정리되어 있습니다.
