package com.openclaw.assistant.bridge

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Base64
import androidx.core.content.ContextCompat
import com.openclaw.assistant.CameraHudKind
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * High-risk capabilities. Every one of them:
 *  - declares `riskLevel = HIGH` so the default approval mode always prompts
 *    the user via [BridgeApprovalActivity]
 *  - reports `isAvailable = false` when the required permission is not held,
 *    so the manifest never advertises an action the device cannot perform
 *  - returns structured `{launched/sent/added: false, reason: ...}` payloads
 *    instead of throwing when the underlying call fails
 *
 * No high-risk action is ever auto-retried.
 */
private fun permissionOk(context: Context, vararg perms: String): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

object SmsSendCapability : BridgeCapability {
    override val name = "sms.send"
    override val description = "Send an SMS to a phone number"
    override val group = "sms"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.SEND_SMS)
    override fun isAvailable(context: Context): Boolean {
        // Google Play restricts SMS to default-SMS-app handlers. Sideload only.
        if (!com.openclaw.assistant.BuildConfig.IS_SIDELOAD) return false
        return permissionOk(context, Manifest.permission.SEND_SMS)
    }
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val number = arguments["number"]?.jsonPrimitive?.content
        val body = arguments["text"]?.jsonPrimitive?.content
        if (number.isNullOrBlank() || body.isNullOrBlank())
            return buildJsonObject { put("sent", false); put("reason", "number and text are required") }
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SERVICE) // ensure service is up
            android.telephony.SmsManager.getDefault().sendTextMessage(number, null, body, null, null)
            buildJsonObject { put("sent", true) }
        } catch (e: SecurityException) {
            buildJsonObject { put("sent", false); put("reason", "permission denied") }
        } catch (e: Exception) {
            buildJsonObject { put("sent", false); put("reason", e.message ?: "send failed") }
        }
    }
}

object ContactsCreateCapability : BridgeCapability {
    override val name = "contacts.create"
    override val description = "Create a new contact (display name + optional phone)"
    override val group = "contacts"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.WRITE_CONTACTS)
    override fun isAvailable(context: Context) = permissionOk(context, Manifest.permission.WRITE_CONTACTS)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val displayName = arguments["displayName"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("created", false); put("reason", "displayName required") }
        val phone = arguments["phone"]?.jsonPrimitive?.content
        return try {
            val cr = context.contentResolver
            val rawUri = cr.insert(
                ContactsContract.RawContacts.CONTENT_URI,
                ContentValues().apply {
                    putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
                },
            )
            val rawId = rawUri?.lastPathSegment?.toLongOrNull()
                ?: return buildJsonObject { put("created", false); put("reason", "could not insert raw contact") }
            cr.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
            })
            if (!phone.isNullOrBlank()) {
                cr.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                })
            }
            buildJsonObject { put("created", true); put("rawContactId", rawId.toString()) }
        } catch (e: SecurityException) {
            buildJsonObject { put("created", false); put("reason", "permission denied") }
        } catch (e: Exception) {
            buildJsonObject { put("created", false); put("reason", e.message ?: "create failed") }
        }
    }
}

object ContactsDeleteCapability : BridgeCapability {
    override val name = "contacts.delete"
    override val description = "Delete a contact by rawContactId"
    override val group = "contacts"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.WRITE_CONTACTS)
    override fun isAvailable(context: Context) = permissionOk(context, Manifest.permission.WRITE_CONTACTS)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val id = arguments["rawContactId"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return buildJsonObject { put("deleted", false); put("reason", "rawContactId required") }
        return try {
            val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
            val rows = context.contentResolver.delete(uri, "${ContactsContract.RawContacts._ID}=?", arrayOf(id.toString()))
            buildJsonObject { put("deleted", rows > 0); put("rows", rows) }
        } catch (e: Exception) {
            buildJsonObject { put("deleted", false); put("reason", e.message ?: "delete failed") }
        }
    }
}

object CalendarCreateCapability : BridgeCapability {
    override val name = "calendar.create"
    override val description = "Create a calendar event (title, startMs, endMs)"
    override val group = "calendar"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
    override fun isAvailable(context: Context) = permissionOk(context,
        Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val title = arguments["title"]?.jsonPrimitive?.content
        val startMs = arguments["startMs"]?.jsonPrimitive?.content?.toLongOrNull()
        val endMs = arguments["endMs"]?.jsonPrimitive?.content?.toLongOrNull()
        if (title.isNullOrBlank() || startMs == null || endMs == null)
            return buildJsonObject { put("created", false); put("reason", "title/startMs/endMs required") }
        return try {
            val calId = firstWritableCalendarId(context)
                ?: return buildJsonObject { put("created", false); put("reason", "no writable calendar") }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            })
            val eid = uri?.lastPathSegment
            if (eid == null) buildJsonObject { put("created", false); put("reason", "insert failed") }
            else buildJsonObject { put("created", true); put("eventId", eid) }
        } catch (e: Exception) {
            buildJsonObject { put("created", false); put("reason", e.message ?: "insert failed") }
        }
    }
    private fun firstWritableCalendarId(context: Context): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }
}

object CalendarDeleteCapability : BridgeCapability {
    override val name = "calendar.delete"
    override val description = "Delete a calendar event by eventId"
    override val group = "calendar"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.WRITE_CALENDAR)
    override fun isAvailable(context: Context) = permissionOk(context, Manifest.permission.WRITE_CALENDAR)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val id = arguments["eventId"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return buildJsonObject { put("deleted", false); put("reason", "eventId required") }
        return try {
            val rows = context.contentResolver.delete(CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID}=?", arrayOf(id.toString()))
            buildJsonObject { put("deleted", rows > 0); put("rows", rows) }
        } catch (e: Exception) {
            buildJsonObject { put("deleted", false); put("reason", e.message ?: "delete failed") }
        }
    }
}

private fun argInt(arguments: JsonObject, key: String, default: Int, min: Int, max: Int): Int =
    arguments[key]?.jsonPrimitive?.content?.trim()?.toIntOrNull()?.coerceIn(min, max) ?: default

/** On-screen hint while the agent drives the camera (same HUD the node camera path uses). */
private fun cameraHud(context: Context, message: String, kind: CameraHudKind, autoHideMs: Long? = null) {
    val app = context.applicationContext as? com.openclaw.assistant.OpenClawApplication ?: return
    app.peekRuntime()?.showCameraHud(message, kind, autoHideMs)
}

/**
 * Take one photo through the camera foreground service (see [com.openclaw.assistant.bridge.camera.CameraCaptureService]).
 *
 * Stage 1 of 2, like `screen.screenshot`: the JPEG goes into the in-memory
 * [com.openclaw.assistant.bridge.accessibility.ScreenCaptureStore] and only metadata plus an id
 * comes back over the bridge; `camera.capture_photo.fetch` drains the pixels.
 *
 * The service has to be armed first — the platform only lets such a service start while the app
 * has a visible activity, which is the approval screen. So a capture with no active grant always
 * carries an approval, and that approval tap is what arms the service.
 */
object CameraCapturePhotoCapability : BridgeCapability {
    private const val DEFAULT_LONG_EDGE = 1568
    private const val DEFAULT_QUALITY = 85
    private const val CAPTURE_TIMEOUT_MS = 25_000L

    override val name = "camera.capture_photo"
    override val description =
        "Take one photo with the device camera. Stage 1 of 2: returns metadata and an id " +
            "(no pixels); call camera.capture_photo.fetch with that id to get the JPEG bytes. " +
            "Needs an approved capture so the camera foreground service is armed."
    override val group = "camera"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.CAMERA)
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("facing", buildJsonObject {
                put("type", "string")
                put("description", "\"back\" (default) or \"front\"")
            })
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

    override fun isAvailable(context: Context): Boolean {
        if (!com.openclaw.assistant.BuildConfig.IS_SIDELOAD) return false
        // Typed startForeground() with FOREGROUND_SERVICE_TYPE_CAMERA is API 29+.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        return permissionOk(context, Manifest.permission.CAMERA)
    }

    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val facingArg = arguments["facing"]?.jsonPrimitive?.content?.trim()?.lowercase()
        val facing = if (facingArg == "front") "front" else "back"
        val longEdge = argInt(arguments, "long_edge", DEFAULT_LONG_EDGE, 320, 4096)
        val quality = argInt(arguments, "quality", DEFAULT_QUALITY, 10, 100)

        val service = com.openclaw.assistant.bridge.camera.CameraCaptureService.awaitArmed()
            ?: return buildJsonObject {
                put("ok", false)
                put("code", "CAMERA_SERVICE_NOT_ARMED")
                put(
                    "reason",
                    "the camera foreground service is not armed: the platform only lets it start " +
                        "while the app is visible, so approve a camera.capture_photo request in the app first",
                )
            }

        cameraHud(context, "Taking photo…", CameraHudKind.Photo)
        val photo = try {
            withTimeout(CAPTURE_TIMEOUT_MS) { service.capture(facing, quality, longEdge) }
        } catch (timeout: TimeoutCancellationException) {
            cameraHud(context, "Camera timed out", CameraHudKind.Error, 2200)
            return buildJsonObject {
                put("ok", false)
                put("code", "CAMERA_TIMEOUT")
                put("reason", "camera did not return a frame within ${CAPTURE_TIMEOUT_MS / 1000}s")
            }
        } catch (t: Throwable) {
            cameraHud(context, t.message ?: "Camera failed", CameraHudKind.Error, 2200)
            return buildJsonObject {
                put("ok", false)
                put("code", "CAMERA_CAPTURE_FAILED")
                put("reason", t.message ?: t::class.java.simpleName)
            }
        }

        val capture = com.openclaw.assistant.bridge.accessibility.ScreenCaptureStore.put(
            jpeg = photo.jpeg,
            width = photo.width,
            height = photo.height,
            sourceWidth = photo.width,
            sourceHeight = photo.height,
            longEdge = longEdge,
            quality = quality,
        )
        cameraHud(context, "Photo captured", CameraHudKind.Success, 1600)
        return buildJsonObject {
            put("ok", true)
            put("id", capture.id)
            put("mime", capture.mime)
            put("width", capture.width)
            put("height", capture.height)
            put("bytes", capture.jpeg.size)
            put("facing", photo.facing)
            put("longEdge", longEdge)
            put("quality", quality)
            put("ttlMs", com.openclaw.assistant.bridge.accessibility.ScreenCaptureStore.TTL_MS)
            put("expiresAt", capture.expiresAt)
            put("fetchWith", "camera.capture_photo.fetch")
        }
    }
}

object CameraCapturePhotoFetchCapability : BridgeCapability {
    override val name = "camera.capture_photo.fetch"
    override val description =
        "Return the JPEG (base64) for an id produced by camera.capture_photo; ids expire after a few minutes"
    override val group = "camera"
    override val riskLevel = RiskLevel.MEDIUM
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("id", buildJsonObject {
                put("type", "string")
                put("description", "id returned by camera.capture_photo")
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
        val encoded = Base64.encodeToString(capture.jpeg, Base64.NO_WRAP)
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

/** All capabilities including high-risk. Used by [BridgeRegistry]. */
object FullCapabilities {
    val all: List<BridgeCapability> = AllCapabilities.all + listOf(
        SmsSendCapability,
        ContactsCreateCapability,
        ContactsDeleteCapability,
        CalendarCreateCapability,
        CalendarDeleteCapability,
        CameraCapturePhotoCapability,
        CameraCapturePhotoFetchCapability,
        NotificationsActiveListCapability,
    ) + A11yCapabilities.all
}
