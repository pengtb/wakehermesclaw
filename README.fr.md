# WakeHermesClaw

Transformer le téléphone en un assistant IA invocable à tout moment : dites le mot de réveil, touchez la tuile des réglages rapides, maintenez appuyée la barre de navigation, ou appuyez sur la touche de votre casque — et le panneau vocal se déploie ; ce que vous dites part vers le backend que vous faites tourner sur votre ordinateur (Hermes Agent ou OpenClaw), et la réponse est lue à voix haute. Au-delà de la parole, l'assistant peut aussi piloter le téléphone en retour — lire l'écran, appuyer, prendre des photos, envoyer des SMS, localiser.

Il s'agit d'une branche personnelle dérivée de [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant), dont le travail principal a consisté à brancher Hermes Agent et à compléter la chaîne vocale, la sélection du backend et le contrôle du téléphone.

- Dernière version : v2.5.2 ([Releases](https://github.com/pengtb/wakehermesclaw/releases))
- Configuration requise : Android 8.0 ou supérieur (minSdk 26), targetSdk 34
- Langues de l'interface : 简体中文 / 繁體中文 / English / 日本語 / 한국어 / Deutsch / Español / Français / हिन्दी / Русский

[English](README.en.md) · [简体中文](README.md) · [繁體中文](README.zh-TW.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Deutsch](README.de.md) · [Español](README.es.md) · Français · [हिन्दी](README.hi.md) · [Русский](README.ru.md)

---

## Captures d'écran

| Accueil | Conversation vocale | Conversations |
| :---: | :---: | :---: |
| <img src="docs/images/home.jpg" width="220"> | <img src="docs/images/voice_overlay.jpg" width="220"> | <img src="docs/images/conversations.jpg" width="220"> |
| **Canvas** | **Réglages** | **Chat** |
| <img src="docs/images/canvas.jpg" width="220"> | <img src="docs/images/settings.jpg" width="220"> | <img src="docs/images/chat.jpg" width="220"> |

---

## Ce qu'il fait

### Conversation vocale

- **Quatre modes d'invocation** : mot de réveil, tuile des réglages rapides, geste de l'assistant système (appui long sur la barre de navigation / sur la touche marche-arrêt), raccourci statique de l'application ; on peut aussi ouvrir directement « Conversation vocale » dans l'app.
- **Conversation continue** : l'écoute reprend automatiquement après la réponse ; on peut aussi basculer en mode talkie-walkie — un appui pour commencer à parler, un autre pour terminer et envoyer.
- **Mots de sortie** : dire « 退出 », « exit » ou un mot équivalent met fin à la conversation ; le mot par défaut suit la langue de l'interface et se modifie dans les réglages.
- **Le panneau ne masque pas les notifications** : le panneau vocal est une carte collée en bas, la barre d'état reste au système ; pendant la conversation, on peut toujours dérouler les notifications et toucher les approbations.
- **État en cours** : une notification reste présente dans la barre d'état pendant la conversation, un appui dessus ramène au panneau ; au premier plan le panneau affiche « conversation vocale en cours », en arrière-plan il devient « touchez pour revenir à la conversation vocale », et la notification disparaît d'elle-même à la fin.

### Reconnaissance vocale (STT)

Quatre backends, au choix selon les besoins :

| Backend | Description |
| --- | --- |
| Reconnaissance système | Utilise le service de reconnaissance intégré au téléphone, zéro configuration |
| Compatible OpenAI | On lance son propre service `/v1/audio/transcriptions`, envoi par lots |
| FunASR en flux | FunASR local, les mots sortent au fil de la parole |
| Huawei HMS (ML Kit) | Reconnaissance côté appareil, nécessite HMS Core et une clé API AGC ; **ne prend en charge que le chinois et l'anglais** |

Deux garde-fous jalonnent la chaîne de reconnaissance : un délai d'expiration sur le silence lorsque le segment entier ne produit aucun mot, et un rattrapage côté client lorsque le SDK Huawei ne rappelle pas sur la trame finale.

### Synthèse vocale (TTS)

| Fournisseur | Description |
| --- | --- |
| Local (TTS système) | Par défaut ; sans moteur explicitement choisi, Google TTS est privilégié, puis le moteur par défaut du système |
| Compatible OpenAI | `POST {base}/v1/audio/speech`, adresse, modèle et voix configurables |
| Huawei HMS (ML Kit) | Synthèse **cloud**, nécessite une connexion réseau et une clé API AGC, le premier million de caractères de chaque mois est gratuit ; ne prend en charge que le chinois et l'anglais |
| ElevenLabs | La liste des voix suit la langue de l'interface |
| VOICEVOX | Uniquement présent dans la build `full` |

Si le système ne contient aucun moteur TTS, une boîte de dialogue propose deux voies : installer Google TTS, ou installer le moteur open source sherpa-onnx.

### Backends et modes de connexion

Trois types de backends sont pris en charge, la voix et le chat choisissant chacun indépendamment par quelle voie passer :

| Type | Protocole | Usage |
| --- | --- | --- |
| Hermes Agent | HTTP (`/v1/runs` ou `/v1/chat/completions`) | Principal ; prend en charge le prompt système, la sortie en flux, les sessions |
| Passerelle OpenClaw | Protocole de nœud WebSocket | Le téléphone s'y connecte en tant que nœud, la passerelle peut émettre directement des appels d'outils |
| OpenClaw HTTP | HTTP compatible OpenAI | Canal léger, sans connexion persistante |

- **Connexion de la session vocale** : un choix parmi trois — `OpenClaw 网关` / `OpenClaw HTTP` / `Hermes HTTP`, présent en haut de « Conversation vocale » sur la page d'accueil et dans les réglages ; sans choix manuel, il suit le backend principal.
- **Chat textuel** : dépend du type de backend choisi par la session, pas du réglage ci-dessus.
- **Prompts** : un prompt système pour la voix et un pour le chat textuel, modifiables dans « Réglages → Chat » ; laissés vides, ils reviennent aux valeurs par défaut intégrées.
- **La voix transporte le contexte de terrain** : heure, position, batterie, réseau et état de l'écran accompagnent chaque requête vocale, si bien que l'assistant peut répondre directement à « quelle heure est-il » ou « combien de batterie reste-t-il ».
- **Auto-rétablissement du service** : si la liaison entre le service vocal et le système tombe, trois niveaux de nouvelle tentative — confirmer l'assistant par défaut, redemander la liaison système, puis redémarrer le processus.

### Contrôle du téléphone (Mobile Bridge)

Le téléphone lance lui-même un service HTTP local (8787 par défaut), par lequel Hermes rappelle les capacités du téléphone : lecture de l'écran et pilotage de l'interface (`screen.tap/swipe/long_press/drag/back/home`, `screen.find_nodes`, `screen.window.describe`), captures (deux étapes, JPEG), appareil photo, localisation, SMS, contacts, calendrier, lecture et suppression des notifications, presse-papiers, contrôle multimédia (`media.play_pause/next/previous`, plus `media.status` pour le titre, l'artiste, l'album et la position), volume et luminosité, liste des applications (`apps.list`, celles ayant une entrée dans le lanceur, marquées `system` / `launchable`) et lancement (`apps.launch`, qui vérifie que le premier plan a réellement changé).

- Appairage unique : après activation du pont dans l'app, un code d'appairage à 6 chiffres s'affiche ; côté ordinateur, on échange le code contre un token, ensuite plus besoin d'appairer.
- Autorisations et approbations : autorisations groupées par capacité, les actions à risque (envoi de SMS, photo, manipulation de l'interface) passent par une boîte de confirmation, avec en plus des limites de débit.
- À n'activer que sur un réseau local ou via Tailscale ; le mode de liaison doit être LAN/VPN pour que la connexion aboutisse.
- **C'est un composant optionnel** : avec la passerelle OpenClaw, la passerelle elle-même peut émettre les capacités du téléphone, inutile d'activer le pont.

### Autres

- **Terminal** : un terminal intégré se connecte au dashboard Hermes de l'ordinateur, nécessite `hermes dashboard --tui`.
- **Tâches planifiées** : consulter, créer et modifier les tâches cron de Hermes, avec prise en charge d'un script (chemin relatif à `HERMES_HOME/scripts/`).
- **Diagnostic** : la page d'accueil et les réglages proposent des contrôles préalables ; permission manquante, service vocal non lié, backend non configuré, accessibilité désactivée sont listés un par un avec la marche à suivre.
- **Mode sombre** : toute l'interface suit le thème clair/sombre du système, assistant de configuration compris.
- **10 langues d'interface** : les 1092 chaînes sont complètes pour chaque langue, aucun mélange d'anglais.

---

## Installation

Téléchargez l'APK depuis [Releases](https://github.com/pengtb/wakehermesclaw/releases) et installez-le directement (nom de package `com.openclaw.assistant`).

Après l'installation, trois choses à faire dans l'app :

1. **Ajouter un backend** : dans « Réglages → Connexion », saisissez l'adresse et le token de Hermes (ou l'hôte / le port de la passerelle OpenClaw).
2. **Définir comme assistant numérique** : définissez cette application comme assistant par défaut, sinon l'appui long sur la barre de navigation ne la déclenchera pas.
3. **Accorder les permissions** : le microphone est indispensable ; localisation, appareil photo, SMS, contacts et calendrier selon les capacités que vous voulez utiliser ; pour le contrôle du téléphone, activez en plus le service d'accessibilité. Les applications installées par sideload doivent d'abord autoriser les paramètres restreints (Infos sur l'app → ⋮ → Autoriser les paramètres restreints), sinon le système refuse d'activer l'accessibilité.

La reconnaissance et la synthèse vocales utilisent par défaut les capacités du système : sans rien configurer, elles parlent et reconnaissent.

---

## Appairage

### Backend Hermes

`integrations/agentvoice-pair/` contient un petit outil de bureau, en deux versions équivalentes PowerShell et bash, sans aucune dépendance (l'encodage du QR code et l'écriture du PNG sont faits par le script lui-même). Il détecte l'adresse de l'API Hermes de la machine (Tailscale Serve en priorité, puis le réseau local), la clé API, ainsi que l'adresse du Terminal du dashboard et son session token, puis génère un QR code d'appairage :

```bash
bash install.sh                # installe agentvoice-pair ; sous Windows, installe le .ps1 et génère un wrapper .cmd
agentvoice-pair --open --svg   # génère le QR code et l'ouvre
```

Le QR code contient un deep link :

```
agentvoice://setup?hu=<adresse API>&hk=<clé>&hm=<profil>&hr=1&hs=1&htu=<adresse dashboard>&htt=<session token>
```

`hu` / `hk` sont l'adresse Hermes et la clé API, `hm` le profil visé, `hr=1` indique que l'on passe par défaut par la Runs API, `hs=1` que la sortie est en flux, `htu` / `htt` sont l'adresse du dashboard et le session token utilisés par le terminal. `hu` peut être répété : les adresses multiples sont alors testées en parallèle comme candidates. Côté téléphone, trois voies fonctionnent : scan avec l'appareil photo du système (toucher le lien ramène à l'app), scan dans l'app, ou collage direct de ce JSON / deep link dans l'assistant de configuration.

Le QR code contient la clé API et le session token : c'est l'équivalent d'un mot de passe, ne le diffusez pas.

### Mobile Bridge

1. Sur le téléphone, ouvrez « Réglages → Pont mobile » ; une fois activé, la page affiche le code d'appairage à 6 chiffres ainsi qu'un deep link `agentvoice://pair?u=<adresse du pont>&c=<code d'appairage>`.
2. Sur l'ordinateur, exécutez `agentvoice-pair --code <code d'appairage> --write-env <chemin>`, ou envoyez directement `POST {"code":"<code d'appairage>"}` à `http://<téléphone>:8787/pair`.
3. Le token obtenu se place dans le `.env` sous `AGENT_VOICE_BRIDGE_URL` / `AGENT_VOICE_BRIDGE_TOKEN` ; chaque appel ultérieur envoie l'en-tête `Authorization` avec `Bearer` suivi du token.

Le code d'appairage est à usage unique et a une durée de validité. Une fois le token obtenu, l'appairage n'est plus nécessaire, mais les capacités à risque comme l'envoi de SMS, la photo ou les appuis à l'écran demandent toujours une approbation à chaque appel.

---

## Compiler depuis les sources

Nécessite JDK 17 et Android SDK 35.

```bash
./gradlew assembleStandardDebug     # paquet de debug (le nom de package gagne un .debug, il peut coexister avec le paquet de production)
./gradlew assembleFullDebug         # paquet de debug incluant VOICEVOX
./gradlew assembleStandardRelease   # paquet de production
```

Les artefacts se trouvent dans `app/build/outputs/apk/<flavor>/<buildType>/`.

Quelques points d'attention côté build :

- `app/google-services.json` n'est pas versionné. Sans configuration Firebase réelle, il faut produire le paquet avec `FIREBASE_ENABLED=false`, sinon Firebase est initialisé avec une configuration mock.
- La build release nécessite une configuration de signature locale (`storeFile / storePassword / keyAlias / keyPassword` dans `local.properties`).
- La build release doit exclure `uploadCrashlyticsMappingFileStandardRelease`, sinon elle tentera de joindre Firebase.
- R8 doit laisser passer les classes côté appareil référencées par le SDK HMS (`com.huawei.libcore.*`, `org.chromium.net.*`) ; les règles sont déjà dans `app/proguard-rules.pro`.
- Les clés Huawei sont injectées depuis `hms.asr.key / hms.asr.appid / hms.asr.cert` de `local.properties` dans `BuildConfig`, et ne sont pas versionnées ; le paquet release ne contient pas la clé TTS, l'utilisateur doit la saisir lui-même dans l'app.

---

## Structure du code

```
app/src/main/java/com/openclaw/assistant/
├── speech/      reconnaissance (système / compatible OpenAI / FunASR / HMS), synthèse (locale / ElevenLabs / compatible OpenAI / HMS / VOICEVOX), mots de sortie
├── backend/     abstraction et dispatch des backends : Hermes HTTP, passerelle OpenClaw, OpenClaw HTTP
├── service/     service d'interaction vocale et panneau de session, mot de réveil, tuile, notifications, auto-rétablissement
├── bridge/      Mobile Bridge : service HTTP, implémentation des capacités, accessibilité, approbation et autorisation
├── node/        protocole de nœud de la passerelle et runtime
├── ui/          accueil, chat, réglages, terminal, tâches planifiées, diagnostic
└── data/        réglages et stockage local
```

- `AGENT.md` s'adresse aux mainteneurs : les pièges rencontrés, pourquoi le code est écrit ainsi, et les endroits où une modification fait échouer les choses silencieusement.
- `docs/hermes-mobile-bridge.md` documente le protocole du pont et le déroulé de l'appairage.
- `integrations/` contient l'outil d'appairage côté desktop.

---

## Ce que ce fork change

L'amont est `yuga-hashimoto/openclaw-assistant`. Les modifications apportées par-dessus, regroupées par fonction :

**Backend Hermes**

- Hermes Agent est intégré comme backend de première classe : `/v1/runs` et `/v1/chat/completions` fonctionnent tous deux, avec sessions, sortie en flux et prompt système. Les messages system de la Runs API doivent se trouver dans `instructions` au niveau racine (le serveur ne les lit pas s'ils sont dans `input`) ; c'est complété côté client.
- Trois modes de connexion au choix : passerelle OpenClaw / OpenClaw HTTP / Hermes HTTP, chacun présent sur la page d'accueil et dans les réglages, avec une correspondance stricte à la sélection et plus de repli silencieux.
- Si l'adresse est saisie sans `/v1/`, il est ajouté automatiquement, ce qui corrige le 404 au premier message après migration d'une ancienne configuration.

**Voix**

- La reconnaissance devient multi-backends interchangeable : système / compatible OpenAI par lots / FunASR en flux / Huawei HMS ; l'ancien backend Baidu est retiré.
- Ajout de la synthèse cloud Huawei HMS ; si la machine ne possède aucun moteur TTS, une aide à l'installation est proposée.
- Les prompts système de la voix et du chat textuel sont séparés et modifiables ; laissés vides, ils reviennent aux valeurs par défaut.
- Les requêtes vocales joignent automatiquement l'heure, la position, la batterie, le réseau et l'état de l'écran.
- Mots de sortie : le mot prononcé met fin à la session en cours, le mot par défaut suit la langue de l'interface.
- Trois modes d'invocation supplémentaires : tuile des réglages rapides, passerelle `VOICE_COMMAND`, raccourci statique.
- Si la liaison entre le service vocal et le système tombe, trois niveaux d'auto-rétablissement : confirmer l'assistant par défaut → redemander la liaison → redémarrer le processus.
- Le panneau devient une carte collée en bas, il ne masque plus la barre de notifications ; la notification de conversation change de texte selon le premier plan ou l'arrière-plan, un appui ramène au panneau et elle disparaît à la fin.

**Contrôle du téléphone**

- Capture d'écran et photo se font en deux temps : d'abord les métadonnées et un identifiant de courte durée, puis le base64 récupéré par identifiant ; l'image ne vit qu'en mémoire, expire au bout de 5 minutes et n'est jamais écrite sur disque.
- La photo utilise un service de premier plan pour l'appareil photo en mode « approuver, puis armer », ce qui contourne l'interdiction de démarrer le service caméra en arrière-plan depuis Android 12.
- L'envoi de SMS attend désormais le résultat de `sentIntent` avant de répondre, et renvoie le vrai code de résultat.
- Les gestes d'accessibilité sont émis sur le thread principal avec attente du rappel, ce qui corrige l'échec silencieux de tap / swipe ; `screen.window.describe` lit la fenêtre active, le nom de package n'est plus erroné.
- Nouveaux groupes de capacités `location` / `screen` ; la carte d'état du pont affiche deux lignes, « actuellement sur / prévu sur », pour comparer directement après modification de la configuration.
- Nouveau `media.status` : lit l'app en cours de lecture, le titre, l'artiste, l'album, la position et l'état, en plus des trois touches de contrôle existantes.
- `apps.list` énumère désormais par entrée du lanceur et marque `system` / `launchable` (MAIN/LAUNCHER déclaré dans `<queries>`, sans `QUERY_ALL_PACKAGES`).
- `apps.launch` vérifie que le premier plan a réellement changé au lieu de répondre systématiquement un succès.
- La boucle accept du pont ne s'arrête plus sur un seul `IOException`, qui laissait le port à l'écoute alors que toutes les connexions expiraient.

**Usage quotidien**

- Trois contrôles préalables, sur la page d'accueil, dans les réglages et dans l'assistant, couvrent les permissions d'exécution et le service d'accessibilité, chacun avec un bouton « aller régler ».
- Les 10 langues d'interface ont leurs 1092 chaînes traduites et sont sélectionnables dans la liste des langues.
- Le mode sombre couvre l'assistant de configuration et la page de gestion des backends.
- Le terminal gagne les champs adresse du dashboard et session token ; les tâches planifiées gagnent la ligne de script, et le bug où l'obligation de `expr` faisait échouer la désérialisation de toute la liste pour les tâches interval / once est corrigé.
- Le bas de la page des réglages affiche le hash court de build, ce qui distingue les différents artefacts d'un même numéro de version.

Le détail des modifications se lit dans `git log` ; les arbitrages d'architecture et les pièges rencontrés sont écrits dans `AGENT.md`.

---

## Limitations connues

- `apps.launch` : lancer une Activity depuis l'arrière-plan se heurte aux restrictions d'Android 10+ ; le résultat dépend du système. Il vérifie désormais après le lancement : avec l'accessibilité active il renvoie `verified` et le paquet réellement au premier plan, `BAL_BLOCKED` si le lancement est bloqué, `verified:false` sans accessibilité. Sur l'appareil Android 16 de l'auteur, rien n'est bloqué : Réglages, NetEase Cloud Music et WeChat démarrent depuis l'arrière-plan.
- `brightness.set` nécessite `WRITE_SETTINGS` ; sans autorisation, la capacité n'est pas visible.
- Un paquet auto-compilé (nom de package différent) n'obtient pas le module de scan ML Kit de Google Play, le scan dans l'app est donc inutilisable ; on peut contourner en scannant le QR code de deep link avec l'appareil photo du système.
- La reconnaissance et la synthèse de Huawei HMS ne prennent en charge que le chinois et l'anglais.
- Les deux canaux passerelle OpenClaw et OpenClaw HTTP n'ont pas de serveur testable dans l'environnement de l'auteur ; aucune validation sur appareil réel n'a été faite.

---

## Remerciements

Ce projet repose sur deux projets open source :

- [openclaw-assistant](https://github.com/yuga-hashimoto/openclaw-assistant) (par yuga-hashimoto, MIT) : point de départ de ce fork ; l'interface, l'interaction vocale, la passerelle et les capacités de nœud en sont issues. Le README d'origine a été remplacé par ce document.
- [gpt-assistant-android](https://github.com/Skythinker616/gpt-assistant-android) (par Skythinker616, GPL-3.0) : la structure « une interface, plusieurs backends » de la couche de reconnaissance vocale s'inspire de la conception `AsrClientBase` de ce projet, tout comme l'intégration de Huawei HMS ML Kit. L'implémentation ici a été écrite indépendamment, sans copie de code.

## Licence

MIT, voir [LICENSE](LICENSE). La mention de copyright d'origine, Copyright (c) 2026 [yuga-hashimoto](https://github.com/yuga-hashimoto), est conservée dans le dépôt ; les modifications apportées dans ce fork sont également publiées sous MIT. Les composants tiers et les ressources embarquées sont listés dans [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
