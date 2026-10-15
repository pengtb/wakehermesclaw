package com.openclaw.assistant.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Media / system capabilities that round out the WakeHermesClaw tool surface
 * count to ~18. Each one is risk-tiered and reports `isAvailable` honestly
 * based on the runtime permissions it actually holds.
 */
private fun permOk(context: Context, vararg perms: String): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

object ClipboardWriteCapability : BridgeCapability {
    override val name = "clipboard.write"
    override val description = "Set the system clipboard text"
    override val group = "clipboard.write"
    override val riskLevel = RiskLevel.MEDIUM
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("ok", false); put("reason", "text required") }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("agentvoice", text))
        return buildJsonObject { put("ok", true); put("length", text.length) }
    }
}

object AudioVolumeGetCapability : BridgeCapability {
    override val name = "audio.volume.get"
    override val description = "Read current media-stream volume (0..max)"
    override val group = "device"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return buildJsonObject { put("volume", cur); put("max", max) }
    }
}

object AudioVolumeSetCapability : BridgeCapability {
    override val name = "audio.volume.set"
    override val description = "Set media-stream volume (0..max)"
    override val group = "device"
    override val riskLevel = RiskLevel.MEDIUM
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val level = arguments["volume"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return buildJsonObject { put("ok", false); put("reason", "volume required") }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = level.coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        return buildJsonObject { put("ok", true); put("volume", clamped); put("max", max) }
    }
}

private fun sendMediaKey(context: Context, keycode: Int) {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keycode))
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keycode))
}

object MediaPlayPauseCapability : BridgeCapability {
    override val name = "media.play_pause"
    override val description = "Toggle play/pause on the active media session"
    override val group = "media"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        return buildJsonObject { put("ok", true) }
    }
}

object MediaNextCapability : BridgeCapability {
    override val name = "media.next"
    override val description = "Skip to next track"
    override val group = "media"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        return buildJsonObject { put("ok", true) }
    }
}

object MediaPreviousCapability : BridgeCapability {
    override val name = "media.previous"
    override val description = "Skip to previous track"
    override val group = "media"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return buildJsonObject { put("ok", true) }
    }
}

object BrightnessGetCapability : BridgeCapability {
    override val name = "brightness.get"
    override val description = "Read system screen brightness (0..255)"
    override val group = "device"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val v = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Settings.SettingNotFoundException) { -1 }
        return buildJsonObject { put("brightness", v); put("max", 255) }
    }
}

object BrightnessSetCapability : BridgeCapability {
    override val name = "brightness.set"
    override val description = "Set system screen brightness (0..255). Requires WRITE_SETTINGS."
    override val group = "device"
    override val riskLevel = RiskLevel.MEDIUM
    override fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val v = arguments["brightness"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return buildJsonObject { put("ok", false); put("reason", "brightness required") }
        val clamped = v.coerceIn(0, 255)
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
            buildJsonObject { put("ok", true); put("brightness", clamped) }
        } catch (e: SecurityException) {
            buildJsonObject { put("ok", false); put("reason", "WRITE_SETTINGS not granted") }
        }
    }
}

object NotificationDismissCapability : BridgeCapability {
    override val name = "notification.dismiss"
    override val description = "Dismiss an active notification by key"
    override val group = "notifications"
    override val riskLevel = RiskLevel.MEDIUM
    override fun isAvailable(context: Context): Boolean {
        val enabled = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabled.contains(context.packageName)
    }
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val key = arguments["key"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("ok", false); put("reason", "key required") }
        val svc = com.openclaw.assistant.service.OpenClawNotificationListenerService.instance
            ?: return buildJsonObject { put("ok", false); put("reason", "listener not connected") }
        return try {
            svc.cancelNotification(key)
            buildJsonObject { put("ok", true) }
        } catch (e: Exception) {
            buildJsonObject { put("ok", false); put("reason", e.message ?: "cancel failed") }
        }
    }
}

private fun argInt(arguments: JsonObject, key: String, default: Int, min: Int, max: Int): Int =
    arguments[key]?.jsonPrimitive?.content?.trim()?.toIntOrNull()?.coerceIn(min, max) ?: default

private fun jpegBytes(bitmap: Bitmap, quality: Int): ByteArray =
    ByteArrayOutputStream(bitmap.byteCount / 4 + 64 * 1024).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }

object ScreenScreenshotCapability : BridgeCapability {
    private const val DEFAULT_LONG_EDGE = 1568
    private const val DEFAULT_QUALITY = 80

    override val name = "screen.screenshot"
    override val description =
        "Capture the screen into a short-lived in-memory buffer. Stage 1 of 2: returns metadata " +
            "and an id (no pixels); call screen.screenshot.fetch with that id to get the JPEG bytes."
    override val group = "accessibility"
    override val riskLevel = RiskLevel.HIGH
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("long_edge", buildJsonObject {
                put("type", "integer")
                put("description", "resize so the longer edge is this many pixels (default $DEFAULT_LONG_EDGE)")
            })
            put("quality", buildJsonObject {
                put("type", "integer")
                put("description", "JPEG quality 1-100 (default $DEFAULT_QUALITY)")
            })
        })
    }

    override fun isAvailable(context: Context) =
        com.openclaw.assistant.BuildConfig.IS_SIDELOAD &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService.isRunning()

    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val longEdge = argInt(arguments, "long_edge", DEFAULT_LONG_EDGE, 320, 4096)
        val quality = argInt(arguments, "quality", DEFAULT_QUALITY, 1, 100)
        val service = com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService.get()
            ?: return buildJsonObject {
                put("ok", false)
                put("reason", "accessibility service not running")
            }

        val grab = service.captureScreenBitmap()
        val bitmap = grab.bitmap
            ?: return buildJsonObject {
                put("ok", false)
                put("reason", "takeScreenshot failed")
                put("errorCode", grab.errorCode)
            }

        // Downscale only when needed; `scaled` may be the same instance, in which case the
        // finally block must not recycle it twice.
        val scaled = if (maxOf(bitmap.width, bitmap.height) <= longEdge) {
            bitmap
        } else {
            val ratio = longEdge.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        }
        return try {
            val jpeg = withContext(Dispatchers.IO) { jpegBytes(scaled, quality) }
            val capture = com.openclaw.assistant.bridge.accessibility.ScreenCaptureStore.put(
                jpeg = jpeg,
                width = scaled.width,
                height = scaled.height,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                longEdge = longEdge,
                quality = quality,
            )
            buildJsonObject {
                put("ok", true)
                put("id", capture.id)
                put("mime", capture.mime)
                put("width", capture.width)
                put("height", capture.height)
                put("sourceWidth", bitmap.width)
                put("sourceHeight", bitmap.height)
                put("bytes", capture.jpeg.size)
                put("longEdge", longEdge)
                put("quality", quality)
                put("ttlMs", com.openclaw.assistant.bridge.accessibility.ScreenCaptureStore.TTL_MS)
                put("expiresAt", capture.expiresAt)
                put("fetchWith", "screen.screenshot.fetch")
            }
        } finally {
            if (scaled !== bitmap) runCatching { scaled.recycle() }
            runCatching { bitmap.recycle() }
        }
    }
}

object ScreenScreenshotFetchCapability : BridgeCapability {
    override val name = "screen.screenshot.fetch"
    override val description =
        "Return the JPEG (base64) for an id produced by screen.screenshot; ids expire after a few minutes"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.MEDIUM
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("id", buildJsonObject {
                put("type", "string")
                put("description", "id returned by screen.screenshot")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("id")) })
    }

    override fun isAvailable(context: Context) = com.openclaw.assistant.BuildConfig.IS_SIDELOAD

    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val id = arguments["id"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (id.isEmpty()) return buildJsonObject { put("ok", false); put("reason", "id required") }
        val capture = com.openclaw.assistant.bridge.accessibility.ScreenCaptureStore.get(id)
            ?: return buildJsonObject {
                put("ok", false)
                put("reason", "unknown or expired id")
                put("id", id)
            }
        val encoded = withContext(Dispatchers.IO) {
            Base64.encodeToString(capture.jpeg, Base64.NO_WRAP)
        }
        return buildJsonObject {
            put("ok", true)
            put("id", capture.id)
            put("mime", capture.mime)
            put("width", capture.width)
            put("height", capture.height)
            put("bytes", capture.jpeg.size)
            put("base64", encoded)
        }
    }
}

object FullPlusCapabilities {
    val all: List<BridgeCapability> = FullCapabilities.all + listOf(
        ClipboardWriteCapability,
        AudioVolumeGetCapability, AudioVolumeSetCapability,
        MediaPlayPauseCapability, MediaNextCapability, MediaPreviousCapability,
        BrightnessGetCapability, BrightnessSetCapability,
        NotificationDismissCapability,
        ScreenScreenshotCapability,
        ScreenScreenshotFetchCapability,
    )
}
