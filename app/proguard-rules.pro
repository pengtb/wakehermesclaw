# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class com.openclaw.assistant.api.** { *; }
# UpdateChecker uses Gson to deserialize GitHub API response.
# Without this rule, R8 renames GithubRelease fields in release builds,
# causing Gson deserialization to fail silently and return null.
-keep class com.openclaw.assistant.utils.GithubRelease { *; }


# Google Error Prone Annotations
-dontwarn com.google.errorprone.annotations.**

# Tink (Security Crypto)
-dontwarn com.google.crypto.tink.**

# Vosk speech recognition
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# JNA (used by Vosk) — JNA uses reflection to access the 'peer' field
# in com.sun.jna.Pointer and native method registration in com.sun.jna.Native.
# Without these rules, R8 strips/renames fields causing UnsatisfiedLinkError.
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Markdown renderer (JetBrains markdown parser)
-keep class org.intellij.markdown.** { *; }
-dontwarn org.intellij.markdown.**

# dnsjava — uses Java SE / Sun APIs not available on Android
-dontwarn javax.naming.**
-dontwarn sun.net.spi.nameservice.**
-dontwarn lombok.**
-dontwarn org.slf4j.impl.**
-dontwarn org.xbill.DNS.spi.**
-keep class org.xbill.DNS.** { *; }

# BouncyCastle — required for BKS KeyStore provider registration.
# Without these rules, R8 strips the security provider registration code,
# causing java.security.KeyStoreException: BKS not found at runtime.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# VOICEVOX Core
-keep class jp.hiroshiba.voicevoxcore.** { *; }
-dontwarn jp.hiroshiba.voicevoxcore.**
-dontwarn jakarta.annotation.**

# TTS Providers (multi-provider architecture)
# These classes are instantiated directly (not via reflection), but keeping them
# ensures their inner classes and data classes survive R8 in release builds.
-keep interface com.openclaw.assistant.speech.TTSProvider { *; }
-keep class com.openclaw.assistant.speech.TTSState { *; }
-keep class com.openclaw.assistant.speech.TTSState$* { *; }
-keep class com.openclaw.assistant.speech.ElevenLabsProvider { *; }
-keep class com.openclaw.assistant.speech.ElevenLabsProvider$* { *; }
-keep class com.openclaw.assistant.speech.OpenAIProvider { *; }
-keep class com.openclaw.assistant.speech.OpenAIProvider$* { *; }
-keep class com.openclaw.assistant.speech.AndroidTTSProvider { *; }

# Huawei HMS (ML Kit 语音识别/合成 + Network Kit)
# SDK 里引用了一些只在华为 ROM 上存在的类（com.huawei.libcore.*、chromium cronet），
# 非华为设备上这些类不存在，R8 会把它们当致命缺失；这里显式放过。
-dontwarn com.huawei.**
-dontwarn org.chromium.net.**
-keep class com.huawei.hms.mlsdk.** { *; }
-keep class com.huawei.hms.network.** { *; }
-keep class com.huawei.hmf.tasks.** { *; }
-keep class com.huawei.agconnect.** { *; }
