# WakeHermesClaw

Ein Telefon wird zum jederzeit aufrufbaren KI-Assistenten: Wake-Wort sagen, die Kachel in den Schnelleinstellungen antippen, die Navigationsleiste lange drücken oder eine Kopfhörertaste betätigen — schon fährt das Sprachpanel hoch; das Gesagte geht an ein Backend, das auf dem eigenen Rechner läuft (Hermes Agent oder OpenClaw), und die Antwort wird vorgelesen. Außer Sprechen kann der Assistent auch umgekehrt dieses Telefon bedienen — Bildschirm lesen, tippen, fotografieren, SMS verschicken, orten.

Dies ist ein Fork für den Eigenbedarf auf Basis von [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant); die Hauptarbeit bestand darin, Hermes Agent anzubinden und Sprachkette, Backend-Auswahl und Telefonsteuerung vollständig zu machen.

- Aktuelle Version: v2.5.2 ([Releases](https://github.com/pengtb/wakehermesclaw/releases))
- Systemanforderung: Android 8.0 und neuer (minSdk 26), targetSdk 34
- Oberflächensprachen: 简体中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Español / Français / हिन्दी / Русский

[English](README.en.md) · [简体中文](README.md) · [繁體中文](README.zh-TW.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · Deutsch · [Español](README.es.md) · [Français](README.fr.md) · [हिन्दी](README.hi.md) · [Русский](README.ru.md)

---

## Screenshots

| Startseite | Sprachdialog | Unterhaltungen |
| :---: | :---: | :---: |
| <img src="docs/images/home.jpg" width="220"> | <img src="docs/images/voice_overlay.jpg" width="220"> | <img src="docs/images/conversations.jpg" width="220"> |
| **Canvas** | **Einstellungen** | **Chat** |
| <img src="docs/images/canvas.jpg" width="220"> | <img src="docs/images/settings.jpg" width="220"> | <img src="docs/images/chat.jpg" width="220"> |

---

## Was es macht

### Sprachdialog

- **Vier Aufrufwege**: Wake-Wort, Kachel in den Schnelleinstellungen, System-Assistent-Geste (Navigationsleiste lange drücken / Power-Taste), statische App-Shortcuts; alternativ direkt den Punkt „Sprachdialog" in der App antippen.
- **Fortlaufender Dialog**: nach der Antwort wird automatisch weiter zugehört; umschaltbar auf Walkie-Talkie — einmal tippen zum Sprechen, erneut tippen beendet und sendet.
- **Beenden-Wörter**: Wörter wie „Beenden" oder „exit" beenden den Dialog direkt; die Standardwörter folgen der Oberflächensprache und sind in den Einstellungen änderbar.
- **Panel verdeckt keine Benachrichtigungen**: das Sprachpanel ist eine Karte am unteren Rand, die Statusleiste oben bleibt beim System, während des Dialogs lässt sich die Benachrichtigungsleiste weiter herunterziehen und Freigaben antippen.
- **Laufender Status**: während des Dialogs bleibt eine Benachrichtigung in der Statusleiste, ein Tipp führt zurück zum Dialogpanel; im Vordergrund zeigt das Panel „Sprachdialog läuft", im Hintergrund wird daraus „Zum Sprachdialog zurückkehren", nach dem Ende verschwindet die Benachrichtigung automatisch.

### Spracherkennung (STT)

Vier Backends nach Bedarf:

| Backend | Beschreibung |
| --- | --- |
| Systemerkennung | nutzt den Erkennungsdienst des Telefons, ohne Konfiguration |
| OpenAI-kompatibel | eigener `/v1/audio/transcriptions`-Dienst, Batch-Upload |
| FunASR-Streaming | lokales FunASR, Text erscheint beim Sprechen |
| Huawei HMS (ML Kit) | geräteseitige Erkennung, braucht HMS Core und einen AGC-API-Key; **nur Chinesisch und Englisch** |

In der Erkennungskette gibt es zwei Auffangnetze: ein Stille-Timeout, wenn ein ganzer Abschnitt keinen Text liefert, und ein clientseitiger Abschluss, wenn das Huawei-SDK beim final-Frame keinen Callback auslöst.

### Sprachsynthese (TTS)

| Anbieter | Beschreibung |
| --- | --- |
| Lokal (System-TTS) | Standard; ohne explizit gewählte Engine zuerst Google TTS, dann der Systemstandard |
| OpenAI-kompatibel | `POST {base}/v1/audio/speech`, Adresse, Modell und Stimme konfigurierbar |
| Huawei HMS (ML Kit) | **Cloud**-Synthese, braucht Netz und AGC-API-Key, die ersten 1 Mio. Zeichen pro Monat kostenlos; nur Chinesisch und Englisch |
| ElevenLabs | Stimmliste folgt der Sprache |
| VOICEVOX | nur im `full`-Build enthalten |

Fehlt im System jede TTS-Engine, erscheint ein Dialog mit zwei Wegen: Google TTS installieren oder die Open-Source-Engine sherpa-onnx installieren.

### Backends und Verbindungswege

Drei Backend-Typen; Sprache und Chat wählen jeweils unabhängig:

| Typ | Protokoll | Zweck |
| --- | --- | --- |
| Hermes Agent | HTTP (`/v1/runs` oder `/v1/chat/completions`) | Hauptweg; unterstützt System-Prompt, Streaming, Sessions |
| OpenClaw-Gateway | WebSocket-Knotenprotokoll | das Telefon tritt als Knoten bei, das Gateway kann Tool-Calls direkt ausliefern |
| OpenClaw HTTP | OpenAI-kompatibles HTTP | leichter Kanal ohne Dauerverbindung |

- **Verbindung der Sprachsession** — eine von drei: `OpenClaw-Gateway` / `OpenClaw HTTP` / `Hermes HTTP`, einmal oben im „Sprachdialog" auf der Startseite und einmal in den Einstellungen; ohne manuelle Wahl folgt sie dem Haupt-Backend.
- **Text-Chat** richtet sich nach dem Backend-Typ der Session, nicht nach dieser Einstellung.
- **Prompts**: Sprache und Text-Chat haben je einen System-Prompt, änderbar unter „Einstellungen → Chat", leer gelassen greift der eingebaute Standard.
- **Sprache schickt Kontext mit**: Zeit, Standort, Akkustand, Netz und Bildschirmstatus gehen mit jeder Sprach-Anfrage mit, der Assistent beantwortet direkt „wie spät ist es" oder „wie viel Akku hat das Telefon noch".
- **Selbstheilung des Dienstes**: reißt die Bindung zwischen Sprachdienst und System ab, gibt es drei Stufen — Standard-Assistent bestätigen, System-Bindung neu anfordern, zuletzt Neustart des Prozesses.

### Telefonsteuerung (Mobile Bridge)

Das Telefon startet selbst einen lokalen HTTP-Dienst (Standard 8787), über den Hermes die Funktionen des Telefons aufruft: Bildschirm lesen und UI bedienen (`screen.tap/swipe/long_press/drag/back/home`, `screen.find_nodes`, `screen.window.describe`), Screenshots (zweistufig, JPEG), Kamera, Standort, SMS, Kontakte, Kalender, Benachrichtigungen lesen und entfernen, Zwischenablage, Mediensteuerung (`media.play_pause/next/previous`, zusätzlich `media.status` für Titel, Interpret, Album und Position), Lautstärke und Helligkeit, App-Liste (`apps.list`, Apps mit Launcher-Eintrag, markiert mit `system` / `launchable`) und Starten (`apps.launch`, prüft, ob der Vordergrund tatsächlich gewechselt hat).

- Einmalige Kopplung: nach dem Einschalten der Bridge in der App erscheint ein 6-stelliger Kopplungscode, die Rechnerseite tauscht den Code gegen ein Token, danach entfällt die Kopplung.
- Berechtigung und Freigabe: Freigabe nach Fähigkeitsgruppen, risikoreiche Aktionen (SMS senden, Foto, Oberflächenbedienung) laufen über einen Bestätigungsdialog, dazu kommen Rate-Limits.
- Nur im LAN oder über Tailscale einschalten; der Bindungsmodus muss LAN/VPN sein, sonst kommt keine Verbindung zustande.
- **Es ist optional**: mit dem OpenClaw-Gateway liefert das Gateway die Telefonfähigkeiten selbst, die Bridge muss nicht laufen.

### Sonstiges

- **Terminal**: eingebautes Terminal verbindet sich mit dem Hermes-Dashboard auf dem Rechner, braucht `hermes dashboard --tui`.
- **Geplante Aufgaben**: Hermes-Cron-Jobs ansehen, neu anlegen, bearbeiten, Skripte sind möglich (relativ zu `HERMES_HOME/scripts/`).
- **Diagnose**: Startseite und Einstellungen haben Vorabprüfungen; fehlende Berechtigungen, ungebundener Sprachdienst, nicht konfiguriertes Backend und ausgeschaltete Bedienungshilfe werden einzeln aufgelistet, jeweils mit dem Weg dorthin.
- **Dunkelmodus**: alle Oberflächen folgen dem Hell/Dunkel des Systems, auch der Einrichtungsassistent.
- **10 Oberflächensprachen**: pro Sprache alle 1092 Texte vorhanden, kein englischer Rest.

---

## Installation

APK von [Releases](https://github.com/pengtb/wakehermesclaw/releases) herunterladen und direkt installieren (Paketname `com.openclaw.assistant`).

Nach der Installation drei Dinge in der App:

1. **Backend hinzufügen**: unter „Einstellungen → Verbindung" Adresse und Token von Hermes eintragen (oder Host / Port des OpenClaw-Gateways).
2. **Als digitalen Assistenten setzen**: die App zum Standard-Assistenten machen, sonst lässt sie sich per langem Druck auf die Navigationsleiste nicht aufrufen.
3. **Berechtigungen geben**: Mikrofon ist Pflicht; Standort, Kamera, SMS, Kontakte und Kalender nach den Fähigkeiten, die genutzt werden sollen; für Telefonsteuerung zusätzlich den Bedienungshilfe-Dienst einschalten. Per Sideload installierte Apps müssen zuerst unter „App-Info → ⋮ → Eingeschränkte Einstellungen zulassen“ freigegeben werden, sonst lässt das System die Bedienungshilfe nicht aktivieren.

Spracherkennung und -synthese nutzen standardmäßig die Systemfähigkeiten, auch ohne Konfiguration kommt Ton und Erkennung zustande.

---

## Kopplung

### Hermes-Backend

In `integrations/agentvoice-pair/` liegt ein kleines Rechner-Tool; PowerShell- und bash-Fassung sind gleichwertig und ohne Abhängigkeiten (QR-Code-Kodierung und PNG-Schreiben stecken im Skript). Es ermittelt die Hermes-API-Adresse des Rechners (bevorzugt Tailscale Serve, dann LAN), den API-Key sowie Dashboard-Terminal-Adresse und Session-Token und erzeugt daraus einen Kopplungs-QR-Code:

```bash
bash install.sh                # als agentvoice-pair installieren; unter Windows wird .ps1 installiert und ein .cmd-Wrapper erzeugt
agentvoice-pair --open --svg   # QR-Code erzeugen und öffnen
```

Der QR-Code enthält einen Deep-Link:

```
agentvoice://setup?hu=<API-Adresse>&hk=<key>&hm=<profile>&hr=1&hs=1&htu=<Dashboard-Adresse>&htt=<session token>
```

`hu` / `hk` sind Hermes-Adresse und API-Key, `hm` das Zielprofil, `hr=1` heißt standardmäßig Runs API, `hs=1` Streaming, `htu` / `htt` sind Dashboard-Adresse und Session-Token fürs Terminal. `hu` darf mehrfach angegeben werden, mehrere Adressen werden als Kandidaten parallel auf Geschwindigkeit geprüft. Auf dem Telefon führen drei Wege zum Ziel: QR-Code mit der Systemkamera scannen (Link antippen, zurück in die App), in der App scannen, oder den JSON-Block / Deep-Link im Einrichtungsassistenten direkt einfügen.

Der QR-Code enthält API-Key und Session-Token, also Passwort-Äquivalent — nicht weitergeben.

### Mobile Bridge

1. Auf dem Telefon „Einstellungen → Mobile Bridge" öffnen; nach dem Einschalten zeigt die Seite einen 6-stelligen Kopplungscode und einen Deep-Link `agentvoice://pair?u=<Bridge-Adresse>&c=<Kopplungscode>`.
2. Auf dem Rechner `agentvoice-pair --code <Kopplungscode> --write-env <Pfad>` ausführen oder direkt `POST {"code":"<Kopplungscode>"}` an `http://<Telefon>:8787/pair` schicken.
3. Das zurückgegebene Token in der `.env` unter `AGENT_VOICE_BRIDGE_URL` / `AGENT_VOICE_BRIDGE_TOKEN` eintragen; jeder weitere Aufruf schickt den Header `Authorization` mit `Bearer` gefolgt vom Token.

Der Kopplungscode ist einmalig und zeitlich begrenzt. Nach dem Token ist keine erneute Kopplung nötig, risikoreiche Fähigkeiten wie SMS senden, Foto oder Tippen lösen aber weiterhin jedes Mal eine Freigabe aus.

---

## Aus dem Quellcode bauen

Benötigt JDK 17 und Android SDK 35.

```bash
./gradlew assembleStandardDebug     # Debug-Build (Paketname mit zusätzlichem .debug, läuft neben dem Release)
./gradlew assembleFullDebug         # Debug-Build mit VOICEVOX
./gradlew assembleStandardRelease   # Release-Build
```

Die Artefakte liegen in `app/build/outputs/apk/<flavor>/<buildType>/`.

Einige Hinweise zum Build:

- `app/google-services.json` ist nicht im Repo. Ohne echte Firebase-Konfiguration mit `FIREBASE_ENABLED=false` bauen, sonst initialisiert Firebase mit einer Mock-Konfiguration.
- Release-Builds brauchen eine lokale Signaturkonfiguration (`storeFile / storePassword / keyAlias / keyPassword` in `local.properties`).
- Release-Builds müssen `uploadCrashlyticsMappingFileStandardRelease` ausschließen, sonst wird eine Firebase-Verbindung versucht.
- R8 muss die geräteseitigen Klassen des HMS SDK durchlassen (`com.huawei.libcore.*`, `org.chromium.net.*`), die Regeln stehen in `app/proguard-rules.pro`.
- Die Huawei-Keys werden aus `local.properties` (`hms.asr.key / hms.asr.appid / hms.asr.cert`) in die `BuildConfig` injiziert und nicht eingecheckt; Release-Builds enthalten keinen TTS-Key, der muss in der App selbst eingetragen werden.

---

## Codestruktur

```
app/src/main/java/com/openclaw/assistant/
├── speech/      Erkennung (System / OpenAI-kompatibel / FunASR / HMS), Synthese (lokal / ElevenLabs / OpenAI-kompatibel / HMS / VOICEVOX), Beenden-Wörter
├── backend/     Backend-Abstraktion und Dispatch: Hermes HTTP, OpenClaw-Gateway, OpenClaw HTTP
├── service/     Sprachdienst und Session-Panel, Wake-Wort, Kachel, Benachrichtigungen, Selbstheilung
├── bridge/      Mobile Bridge: HTTP-Dienst, Fähigkeitsimplementierung, Bedienungshilfe, Freigabe und Berechtigung
├── node/        Gateway-Knotenprotokoll und Runtime
├── ui/          Startseite, Chat, Einstellungen, Terminal, geplante Aufgaben, Diagnose
└── data/        Einstellungen und lokaler Speicher
```

- `AGENT.md` ist für Maintainer gedacht: Stolperfallen, warum etwas so geschrieben ist, welche Stellen bei einer Änderung still versagen.
- `docs/hermes-mobile-bridge.md` dokumentiert Bridge-Protokoll und Kopplungsablauf.
- `integrations/` enthält die Kopplungstools für die Rechnerseite.

---

## Was dieser Fork ändert

Upstream ist `yuga-hashimoto/openclaw-assistant`. Die Änderungen dieses Branches, nach Funktion gruppiert:

**Hermes-Backend**

- Hermes Agent als vollwertiges Backend angebunden, `/v1/runs` und `/v1/chat/completions` beide nutzbar, Sessions, Streaming und System-Prompt funktionieren. Die system-Nachricht der Runs API muss auf oberster Ebene in `instructions` stehen (in `input` liest der Server sie nicht), das ergänzt der Client.
- Drei Verbindungswege zur Wahl: OpenClaw-Gateway / OpenClaw HTTP / Hermes HTTP, je einmal auf der Startseite und in den Einstellungen, streng nach Auswahl, kein stiller Rückfall mehr.
- Fehlt `/v1/` in der Adresse, wird es automatisch ergänzt; behebt den 404 direkt nach dem Senden bei migrierten Altkonfigurationen.

**Sprache**

- Erkennung als steckbare Multi-Backend-Lösung: System / OpenAI-kompatibel im Batch-Verfahren / FunASR-Streaming / Huawei HMS; das frühere Baidu-Backend entfernt.
- Neue Cloud-Synthese über Huawei HMS; fehlt lokal jede TTS-Engine, gibt es eine Installationsanleitung.
- System-Prompts für Sprache und Text-Chat getrennt und änderbar, leer gelassen greift der eingebaute Standard.
- Sprach-Anfragen schicken Zeit, Standort, Akkustand, Netz und Bildschirmstatus automatisch mit.
- Beenden-Wörter: ein Treffer beendet die laufende Session, die Standardwörter folgen der Oberflächensprache.
- Drei zusätzliche Aufrufwege: Kachel in den Schnelleinstellungen, `VOICE_COMMAND`-Sprungbrett, statische Shortcuts.
- Reißt die Bindung zwischen Sprachdienst und System ab, heilt der Dienst in drei Stufen: Standard-Assistent bestätigen → Bindung neu anfordern → Prozess neu starten.
- Panel als Karte am unteren Rand, verdeckt die Benachrichtigungsleiste nicht mehr; die Benachrichtigung während des Dialogs wechselt mit Vorder- und Hintergrund den Text, ein Tipp führt zurück zum Panel, am Ende verschwindet sie.

**Telefonsteuerung**

- Screenshot und Foto zweistufig: erst Metadaten und eine kurzlebige id, dann base64 über die id; Bilder liegen nur im Speicher, sind 5 Minuten gültig und werden nicht auf die Platte geschrieben.
- Foto über einen Vordergrunddienst mit „Freigabe scharfschalten", um die ab Android 12 geltende Beschränkung für Kamerastarts aus dem Hintergrund zu umgehen.
- SMS-Versand wartet auf das `sentIntent`-Ergebnis, geliefert wird der echte Ergebniscode.
- Bedienungshilfen-Gesten werden im Hauptthread ausgeliefert und warten auf den Callback, behebt still fehlschlagende tap / swipe; `screen.window.describe` liest das aktive Fenster, der Paketname wird nicht mehr falsch gemeldet.
- Fähigkeitsgruppen um `location` / `screen` erweitert; die Bridge-Statuskarte hat zwei Zeilen „aktuell auf / geplant auf", nach einer Konfigurationsänderung direkt vergleichbar.
- Neu: `media.status` liest die laufende App, Titel, Interpret, Album, Position und Status aus – zusammen mit den drei vorhandenen Steuertasten.
- `apps.list` zählt jetzt nach Launcher-Einträgen und markiert `system` / `launchable` (MAIN/LAUNCHER in `<queries>` deklariert, kein `QUERY_ALL_PACKAGES`).
- `apps.launch` prüft nach dem Start, ob der Vordergrund wirklich gewechselt hat, statt pauschal Erfolg zu melden.
- Die accept-Schleife der Bridge beendet sich nicht mehr bei einem einzelnen `IOException`; damit entfällt der Zustand „Port lauscht, alle Verbindungen laufen in ein Timeout“.

**Alltag**

- Startseite, Einstellungen und Assistent haben Vorabprüfungen für Laufzeitberechtigungen und Bedienungshilfe-Dienst, jeder Eintrag mit „jetzt beheben".
- Alle 10 Oberflächensprachen mit je 1092 Texten vollständig übersetzt, in der Sprachliste wählbar.
- Dunkelmodus deckt auch Einrichtungsassistent und Backend-Verwaltung ab.
- Terminal um Dashboard-Adresse und Session-Token ergänzt; geplante Aufgaben um eine Skriptzeile, und der Fehler behoben, bei dem interval- und once-Jobs wegen des Pflichtfelds `expr` die Deserialisierung der ganzen Liste scheiterten.
- Der Fuß der Einstellungen zeigt den kurzen Build-Hash, verschiedene Artefakte derselben Version bleiben unterscheidbar.

Details lassen sich über `git log` nachvollziehen; Architekturentscheidungen und Stolperfallen stehen in `AGENT.md`.

---

## Bekannte Einschränkungen

- `apps.launch` stößt beim Start einer Activity aus dem Hintergrund an die Hintergrund-Start-Beschränkungen ab Android 10; ob es durchgeht, hängt vom System ab. Jetzt wird nach dem Start geprüft: mit aktivierter Bedienungshilfe kommen `verified` und das echte Vordergrund-Paket zurück, bei Blockade `BAL_BLOCKED`, ohne Bedienungshilfe `verified:false`. Auf dem Android-16-Gerät des Autors wird nicht blockiert — Einstellungen, NetEase Cloud Music und WeChat starten aus dem Hintergrund.
- `brightness.set` braucht `WRITE_SETTINGS`, ohne Berechtigung ist die Fähigkeit unsichtbar.
- Selbst gebaute Pakete (abweichender Paketname) erhalten das ML-Kit-Scan-Modul von Google Play nicht, der Scan in der App ist nicht nutzbar; als Umweg den Scanner der Systemkamera für Deep-Link-QR-Codes nutzen.
- Huawei HMS unterstützt bei Erkennung und Synthese nur Chinesisch und Englisch.
- Die Kanäle OpenClaw-Gateway und OpenClaw HTTP ließen sich in der Umgebung des Autors nicht gegen einen Server testen und wurden nie auf echter Hardware geprüft.

---

## Danksagung

Dieses Projekt steht auf zwei Open-Source-Projekten:

- [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant) (von yuga-hashimoto, MIT) — Ausgangspunkt dieses Forks: Oberfläche, Sprachinteraktion, Gateway- und Node-Funktionen stammen von dort. Das ursprüngliche README wurde durch dieses Dokument ersetzt.
- [gpt-assistant-android](https://github.com/Skythinker616/gpt-assistant-android) (von Skythinker616, GPL-3.0) — die Struktur „eine Schnittstelle, mehrere Backends" der Spracherkennung folgt dem `AsrClientBase`-Entwurf dieses Projekts; auch die Anbindung von Huawei HMS ML Kit orientiert sich daran. Die Umsetzung hier wurde eigenständig geschrieben, es wurde kein Code übernommen.

## Lizenz

MIT, siehe [LICENSE](LICENSE). Der ursprüngliche Copyright-Hinweis, Copyright (c) 2026 [yuga-hashimoto](https://github.com/yuga-hashimoto), bleibt im Repository erhalten; die in diesem Fork hinzugefügten Änderungen stehen ebenfalls unter MIT. Fremdkomponenten und mitgelieferte Assets sind in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) aufgeführt.
