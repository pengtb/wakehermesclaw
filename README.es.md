# WakeHermesClaw

Convierte el teléfono en un asistente de IA que puedes invocar en cualquier momento: di la palabra de activación, toca el mosaico de ajustes rápidos, mantén pulsada la barra de navegación o pulsa una vez el botón de los auriculares, y el panel de voz se eleva; lo que dices va al backend que ejecutas en el ordenador (Hermes Agent u OpenClaw) y la respuesta se lee en voz alta. Además de hablar, el asistente puede operar el teléfono en sentido inverso: leer la pantalla, pulsar, hacer fotos, enviar SMS, ubicar.

Es una bifurcación de uso propio hecha sobre [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant); el trabajo principal fue conectar Hermes Agent y completar la cadena de voz, la selección de backend y el control del teléfono.

- Última versión: v2.5.2 ([Releases](https://github.com/pengtb/wakehermesclaw/releases))
- Requisitos del sistema: Android 8.0 o superior (minSdk 26), targetSdk 34
- Idiomas de la interfaz: 简体中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Español / Français / हिन्दी / Русский

[English](README.en.md) · [简体中文](README.md) · [繁體中文](README.zh-TW.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Deutsch](README.de.md) · Español · [Français](README.fr.md) · [हिन्दी](README.hi.md) · [Русский](README.ru.md)

---

## Capturas

| Inicio | Conversación por voz | Conversaciones |
| :---: | :---: | :---: |
| <img src="docs/images/home.jpg" width="220"> | <img src="docs/images/voice_overlay.jpg" width="220"> | <img src="docs/images/conversations.jpg" width="220"> |
| **Canvas** | **Ajustes** | **Chat** |
| <img src="docs/images/canvas.jpg" width="220"> | <img src="docs/images/settings.jpg" width="220"> | <img src="docs/images/chat.jpg" width="220"> |

---

## Qué hace

### Conversación de voz

- **Cuatro formas de invocarlo**: palabra de activación, mosaico de ajustes rápidos, gesto del asistente del sistema (mantener pulsada la barra de navegación / el botón de encendido) y accesos directos estáticos de la aplicación; también puedes abrir directamente «Conversación de voz» dentro de la app.
- **Conversación continua**: al terminar la respuesta sigue escuchando; también se puede cambiar al modo pulsar para hablar: un toque para empezar a hablar, otro para terminar y enviar.
- **Palabra de salida**: decir «salir», «exit» y palabras similares termina la conversación al momento; la palabra predeterminada cambia con el idioma de la interfaz y se puede modificar en los ajustes.
- **El panel no tapa las notificaciones**: el panel de voz es una tarjeta anclada en la parte inferior, la barra de estado superior queda para el sistema y durante la conversación se puede desplegar la barra de notificaciones y pulsar aprobaciones.
- **Estado en curso**: durante la conversación queda una notificación en la barra de estado; al pulsarla se vuelve al panel de conversación. Con el panel en primer plano se muestra «Conversación de voz en curso»; al pasar a segundo plano cambia a «Toca para volver a la conversación de voz», y al terminar la notificación desaparece sola.

### Reconocimiento de voz (STT)

Cuatro backends, elige según lo que necesites:

| Backend | Descripción |
| --- | --- |
| Reconocimiento del sistema | Usa el servicio de reconocimiento propio del teléfono, sin configuración |
| Compatible con OpenAI | Levantas tu propio servicio `/v1/audio/transcriptions`, carga por lotes |
| FunASR en streaming | FunASR local, el texto sale mientras hablas |
| Huawei HMS (ML Kit) | Reconocimiento en el dispositivo, requiere HMS Core y una API Key de AGC; **solo admite chino e inglés** |

En la cadena de reconocimiento hay dos redes de seguridad: el tiempo de espera por silencio cuando el segmento completo no produce texto, y un cierre adicional que aplica el cliente cuando el SDK de Huawei no devuelve la llamada en el frame final.

### Síntesis de voz (TTS)

| Proveedor | Descripción |
| --- | --- |
| Local (TTS del sistema) | Predeterminado; si no se elige un motor explícitamente se prefiere Google TTS y, en su defecto, el predeterminado del sistema |
| Compatible con OpenAI | `POST {base}/v1/audio/speech`, dirección, modelo y voz configurables |
| Huawei HMS (ML Kit) | Síntesis **en la nube**, requiere conexión y una API Key de AGC; el primer millón de caracteres de cada mes es gratuito; solo admite chino e inglés |
| ElevenLabs | La lista de voces se localiza según el idioma |
| VOICEVOX | Solo incluido en la compilación `full` |

Si no hay ningún motor TTS en el sistema, aparece un diálogo con dos caminos: instalar Google TTS o instalar el motor de código abierto sherpa-onnx.

### Backends y formas de conexión

Se admiten tres tipos de backend; la voz y el chat eligen cada uno por su cuenta por dónde ir:

| Tipo | Protocolo | Uso |
| --- | --- | --- |
| Hermes Agent | HTTP (`/v1/runs` o `/v1/chat/completions`) | Principal; admite instrucciones del sistema, salida en streaming y sesiones |
| Pasarela de OpenClaw | Protocolo de nodo por WebSocket | El teléfono se conecta como nodo y la pasarela puede enviar llamadas a herramientas directamente |
| OpenClaw HTTP | HTTP compatible con OpenAI | Canal ligero que no necesita conexión persistente |

- **Conexión de la sesión de voz**, una de tres opciones: `Pasarela de OpenClaw` / `OpenClaw HTTP` / `Hermes HTTP`; hay una copia en la parte superior de «Conversación de voz» de la página principal y otra en los ajustes; si nunca se eligió manualmente, sigue al backend principal.
- **El chat de texto** usa el tipo de backend elegido en la sesión, no este ajuste.
- **Instrucciones**: la voz y el chat de texto tienen cada uno su propio conjunto de instrucciones del sistema, editables en «Ajustes → Chat»; si se dejan vacías se usan las predeterminadas integradas.
- **La voz incluye información del contexto**: hora, ubicación, batería, red y estado de la pantalla se envían con cada petición de voz, así el asistente puede responder directamente a «qué hora es» o «cuánta batería le queda al teléfono».
- **Autorrecuperación del servicio**: si se pierde el enlace entre el servicio de voz y el sistema, hay tres reintentos: confirmar el asistente predeterminado, volver a solicitar el enlace del sistema y, por último, reiniciar el proceso.

### Control del teléfono (Mobile Bridge)

El teléfono levanta su propio servidor HTTP local (8787 por defecto) y Hermes llama a través de él a las capacidades del teléfono: leer la pantalla y manejar la interfaz (`screen.tap/swipe/long_press/drag/back/home`, `screen.find_nodes`, `screen.window.describe`), capturas (en dos pasos, JPEG), cámara, ubicación, SMS, contactos, calendario, lectura y descarte de notificaciones, portapapeles, control multimedia (`media.play_pause/next/previous`, más `media.status` para título, artista, álbum y posición), volumen y brillo, lista de aplicaciones (`apps.list`, solo con entrada en el lanzador, marcadas `system` / `launchable`) y apertura (`apps.launch`, que verifica si el primer plano cambió de verdad).

- Emparejamiento único: al activar el puente en la app aparece un código de emparejamiento de 6 dígitos; el ordenador canjea el código por un token y después ya no hace falta emparejar.
- Autorización y aprobaciones: los permisos se agrupan por capacidad, las acciones de alto riesgo (enviar SMS, hacer fotos, operar la interfaz) pasan por un diálogo de confirmación y además hay límite de frecuencia.
- Se recomienda activarlo solo en la red local o en Tailscale; el modo de enlace debe ser LAN/VPN para que se pueda conectar.
- **Es opcional**: al usar la pasarela de OpenClaw, la propia pasarela ya puede enviar capacidades al teléfono y no hace falta activar el puente.

### Otros

- **Terminal**: terminal integrada que se conecta al dashboard de Hermes en el ordenador; requiere `hermes dashboard --tui`.
- **Tareas programadas**: ver, crear y editar tareas cron de Hermes; permite indicar un script (relativo a `HERMES_HOME/scripts/`).
- **Diagnóstico**: tanto en la página principal como en los ajustes hay comprobaciones previas; si falta un permiso, el servicio de voz no está enlazado, el backend no está configurado o la accesibilidad no está activa, se enumeran una por una con el lugar donde resolverlas.
- **Modo oscuro**: toda la interfaz sigue el modo claro/oscuro del sistema, incluido el asistente de configuración.
- **10 idiomas de interfaz**: cada idioma tiene sus 1092 cadenas completas, sin mezclar inglés.

---

## Instalación

Descarga el APK desde [Releases](https://github.com/pengtb/wakehermesclaw/releases) e instálalo directamente (nombre de paquete `com.openclaw.assistant`).

Después de instalar, haz tres cosas en la app:

1. **Añadir el backend**: en «Ajustes → Conexión» introduce la dirección y el token de Hermes (o el host / puerto de la pasarela de OpenClaw).
2. **Establecerlo como asistente digital**: configura esta aplicación como asistente predeterminado; de lo contrario, mantener pulsada la barra de navegación no la invocará.
3. **Dar permisos**: el micrófono es imprescindible; ubicación, cámara, SMS, contactos y calendario según las capacidades que quieras usar; si quieres control del teléfono, activa además el servicio de accesibilidad. Las apps instaladas por sideload deben permitir primero los ajustes restringidos («Información de la app → ⋮ → Permitir ajustes restringidos»); si no, el sistema no deja activar la accesibilidad.

El reconocimiento y la síntesis de voz usan por defecto las capacidades del sistema: sin configurar nada ya puedes hablar y reconocer.

---

## Emparejamiento

### Backend de Hermes

En `integrations/agentvoice-pair/` hay una pequeña herramienta de escritorio, con una versión PowerShell y otra bash equivalentes y sin dependencias (la codificación del código QR y la escritura del PNG están en el propio script). Detecta la dirección de la API de Hermes de la máquina (primero Tailscale Serve, después la red local), la API key, y la dirección del Terminal del dashboard junto con su session token, y genera un código QR de emparejamiento:

```bash
bash install.sh                # se instala como agentvoice-pair; en Windows instala el .ps1 y genera un envoltorio .cmd
agentvoice-pair --open --svg   # genera el código QR y lo abre
```

El código QR contiene un enlace profundo:

```
agentvoice://setup?hu=<dirección de la API>&hk=<key>&hm=<profile>&hr=1&hs=1&htu=<dirección del dashboard>&htt=<session token>
```

`hu` / `hk` son la dirección y la API key de Hermes, `hm` es el profile de destino, `hr=1` indica que se usa la Runs API de forma predeterminada, `hs=1` indica streaming, y `htu` / `htt` son la dirección del dashboard y el session token que usa el terminal. `hu` se puede repetir, y las distintas direcciones se prueban en paralelo como candidatas. En el teléfono valen las tres vías: escanear con la cámara del sistema (al abrir el enlace se vuelve a la app), escanear dentro de la app, o pegar ese JSON / enlace profundo directamente en el asistente de configuración.

El código QR contiene la API key y el session token; equivalen a una contraseña, no lo compartas.

### Mobile Bridge

1. En el teléfono abre «Ajustes → Puente móvil»; al activarlo, la página muestra un código de emparejamiento de 6 dígitos y un enlace profundo `agentvoice://pair?u=<dirección del puente>&c=<código>`.
2. En el ordenador ejecuta `agentvoice-pair --code <código> --write-env <ruta>`, o haz directamente `POST {"code":"<código>"}` a `http://<teléfono>:8787/pair`.
3. El token que se devuelve se escribe en `AGENT_VOICE_BRIDGE_URL` / `AGENT_VOICE_BRIDGE_TOKEN` del `.env`; a partir de ahí, cada llamada envía la cabecera `Authorization` con `Bearer` seguido del token.

El código de emparejamiento es de un solo uso y tiene caducidad. Una vez obtenido el token ya no hace falta emparejar, pero las capacidades de alto riesgo, como enviar SMS, hacer fotos o pulsar, siguen pidiendo aprobación cada vez.

---

## Compilar desde el código fuente

Requiere JDK 17 y Android SDK 35.

```bash
./gradlew assembleStandardDebug     # paquete de depuración (el nombre lleva un .debug adicional, puede convivir con el oficial)
./gradlew assembleFullDebug         # paquete de depuración con VOICEVOX
./gradlew assembleStandardRelease   # paquete oficial
```

Los artefactos quedan en `app/build/outputs/apk/<flavor>/<buildType>/`.

Algunos puntos a tener en cuenta al compilar:

- `app/google-services.json` no está en el repositorio. Sin una configuración real de Firebase hay que generar el paquete con `FIREBASE_ENABLED=false`; de lo contrario Firebase se inicializa con una configuración mock.
- La compilación release necesita la configuración de firma local (`storeFile / storePassword / keyAlias / keyPassword` en `local.properties`).
- En la compilación release hay que excluir `uploadCrashlyticsMappingFileStandardRelease`; de lo contrario intentará conectar con Firebase.
- R8 necesita que se conserven las clases del lado del dispositivo a las que hace referencia el SDK de HMS (`com.huawei.libcore.*`, `org.chromium.net.*`); las reglas ya están en `app/proguard-rules.pro`.
- Las claves de Huawei se inyectan en `BuildConfig` desde `hms.asr.key / hms.asr.appid / hms.asr.cert` de `local.properties` y no están en el repositorio; el paquete release no incluye la clave de TTS, el usuario debe introducirla en la app.

---

## Estructura del código

```
app/src/main/java/com/openclaw/assistant/
├── speech/      Reconocimiento (sistema / compatible con OpenAI / FunASR / HMS), síntesis (local / ElevenLabs / compatible con OpenAI / HMS / VOICEVOX), palabra de salida
├── backend/     Abstracción y despacho de backends: Hermes HTTP, pasarela de OpenClaw, OpenClaw HTTP
├── service/     Servicio de interacción de voz y panel de conversación, palabra de activación, mosaico, notificaciones, autorrecuperación
├── bridge/      Mobile Bridge: servidor HTTP, implementación de capacidades, accesibilidad, aprobaciones y autorización
├── node/        Protocolo de nodo de la pasarela y runtime
├── ui/          Página principal, chat, ajustes, terminal, tareas programadas, diagnóstico
└── data/        Ajustes y almacenamiento local
```

- `AGENT.md` es para quien mantiene el proyecto: problemas encontrados, por qué está escrito así y qué partes dejan de funcionar en silencio con un solo cambio.
- `docs/hermes-mobile-bridge.md` documenta el protocolo del puente y el flujo de emparejamiento.
- `integrations/` contiene las herramientas de emparejamiento para el escritorio.

---

## Qué cambia este fork

El upstream es `yuga-hashimoto/openclaw-assistant`. Los cambios que esta bifurcación hace sobre él, agrupados por funcionalidad:

**Backend de Hermes**

- Hermes Agent se integra como backend de primera clase: funcionan tanto `/v1/runs` como `/v1/chat/completions`, con sesiones, streaming e instrucciones del sistema. En la Runs API el mensaje de sistema debe ir en `instructions` de nivel superior (el servidor no lo lee dentro de `input`); esto se completa en el cliente.
- La forma de conexión es una de tres: pasarela de OpenClaw / OpenClaw HTTP / Hermes HTTP, con una copia en la página principal y otra en los ajustes, con coincidencia estricta según lo elegido y sin retroceso silencioso.
- Si la dirección no incluye `/v1/`, se completa automáticamente; esto corrige el 404 al enviar el primer mensaje después de migrar una configuración antigua.

**Voz**

- El reconocimiento pasa a ser de varios backends intercambiables: sistema / compatible con OpenAI por lotes / FunASR en streaming / Huawei HMS, y se elimina el backend de Baidu anterior.
- Se añade la síntesis en la nube de Huawei HMS; si no hay ningún motor TTS en el dispositivo se ofrece una guía de instalación.
- Las instrucciones del sistema de la voz y del chat de texto se separan y se pueden editar; si se dejan vacías se usan las predeterminadas.
- Las peticiones de voz incluyen automáticamente hora, ubicación, batería, red y estado de la pantalla.
- Palabra de salida: al decirla se termina la sesión actual; la palabra predeterminada depende del idioma de la interfaz.
- Tres formas más de invocarlo: mosaico de ajustes rápidos, puente `VOICE_COMMAND` y accesos directos estáticos.
- Si se pierde el enlace entre el servicio de voz y el sistema, hay autorrecuperación en tres niveles: confirmar el asistente predeterminado → volver a solicitar el enlace → reiniciar el proceso.
- El panel pasa a ser una tarjeta anclada abajo y ya no tapa la barra de notificaciones; la notificación de la conversación cambia de texto según el primer o el segundo plano, al pulsarla se vuelve al panel y desaparece al terminar.

**Control del teléfono**

- Tanto la captura de pantalla como las fotos son en dos pasos: primero se devuelven los metadatos y un id de corta duración, y después se obtiene el base64 por ese id; la imagen solo se guarda en memoria, caduca a los 5 minutos y no se escribe en disco.
- Las fotos usan un servicio en primer plano de cámara «armado al aprobar», que sortea la restricción de Android 12 en adelante de no poder iniciar el servicio de cámara en segundo plano.
- El envío de SMS pasa a esperar el resultado de `sentIntent` antes de responder, y devuelve el código de resultado real.
- Los gestos de accesibilidad se despachan en el hilo principal y esperan la llamada de vuelta, lo que corrige el fallo silencioso de tap / swipe; `screen.window.describe` toma la ventana activa y el nombre de paquete ya no se informa mal.
- Se añaden los grupos de capacidades `location` / `screen`; la tarjeta de estado del puente muestra dos líneas, «actual / previsto», para poder comparar directamente después de cambiar la configuración.
- Nuevo `media.status`: lee la app en reproducción, título, artista, álbum, posición y estado, junto a las tres teclas de control existentes.
- `apps.list` ahora enumera por entrada del lanzador y marca `system` / `launchable` (MAIN/LAUNCHER declarado en `<queries>`, sin `QUERY_ALL_PACKAGES`).
- `apps.launch` comprueba si el primer plano cambió de verdad, en vez de informar siempre de éxito.
- El bucle accept del puente ya no termina con un solo `IOException`, que dejaba el puerto escuchando mientras todas las conexiones caducaban.

**Uso diario**

- La página principal, los ajustes y el asistente de configuración incluyen comprobaciones previas que cubren los permisos en tiempo de ejecución y el servicio de accesibilidad, cada una con su «ir a resolverlo».
- Los 10 idiomas de interfaz están traducidos completos con sus 1092 cadenas y se pueden elegir en la lista de idiomas.
- El modo oscuro cubre el asistente de configuración y la página de gestión de backends.
- El terminal añade los campos de dirección del dashboard y session token; las tareas programadas añaden la línea de script y se corrige el fallo por el que las tareas interval / once hacían fallar la deserialización de toda la lista al ser `expr` obligatorio.
- Al pie de la página de ajustes se muestra el hash corto de la compilación, para distinguir artefactos distintos con el mismo número de versión.

El detalle de los cambios se puede seguir con `git log`; las decisiones de arquitectura y los problemas encontrados están en `AGENT.md`.

---

## Limitaciones conocidas

- `brightness.set` necesita `WRITE_SETTINGS`; sin ese permiso la capacidad no es visible.
- Los paquetes compilados por uno mismo (con otro nombre de paquete) no obtienen el módulo de escaneo de ML Kit de Google Play y el escaneo dentro de la app no está disponible; se puede sortear escaneando el código QR del enlace profundo con la cámara del sistema.
- El reconocimiento y la síntesis de Huawei HMS solo admiten chino e inglés.
- Los dos canales, pasarela de OpenClaw y OpenClaw HTTP, no tenían servidor con el que probarse en el entorno del autor y no se han verificado en un dispositivo real.

---

## Agradecimientos

Este proyecto se apoya en dos proyectos de código abierto:

- [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant) (de yuga-hashimoto, MIT): punto de partida de esta bifurcación; la interfaz, la interacción por voz, la pasarela y las capacidades de nodo provienen de él. El README original se ha sustituido por este documento.
- [gpt-assistant-android](https://github.com/Skythinker616/gpt-assistant-android) (de Skythinker616, GPL-3.0): la estructura «una interfaz, varios backends» de la capa de reconocimiento de voz sigue el diseño `AsrClientBase` de ese proyecto, y la integración de Huawei HMS ML Kit también se inspiró en él. La implementación aquí es propia; no se copió código.

## Licencia

MIT, véase [LICENSE](LICENSE). El aviso de copyright original, Copyright (c) 2026 [yuga-hashimoto](https://github.com/yuga-hashimoto), se mantiene en el repositorio; los cambios añadidos en esta bifurcación también se publican bajo MIT. Los componentes de terceros y los recursos incluidos se detallan en [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
