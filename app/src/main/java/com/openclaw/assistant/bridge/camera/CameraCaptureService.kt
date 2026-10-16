package com.openclaw.assistant.bridge.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.openclaw.assistant.node.CameraCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Camera capture foreground service.
 *
 * Why this exists at all: since Android 11 the camera can only be opened from the background by a
 * foreground service that declares the `camera` type, and since Android 12 such a service cannot
 * even be *started* while the app is in the background — it has to be started while the app has a
 * visible activity. The bridge approval screen is exactly that moment, so the approval taps arm
 * this service; afterwards the HTTP request can be served while the phone shows some other app.
 *
 * What stays alive is the *service*, not the camera: the lifecycle owner is driven to RESUMED only
 * around a capture, so the camera is released (and its indicator light goes off) between shots.
 * `keepAliveMs = 0` means "one shot, then stop"; a timed approval arms it for the grant window
 * (the app has no visible activity later, so a fresh start would be refused by the platform).
 */
class CameraCaptureService : Service() {

    /** CameraX needs a LifecycleOwner; a service is not one, so we bring our own. */
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    class CapturedPhoto(val jpeg: ByteArray, val width: Int, val height: Int, val facing: String)

    private val owner = Owner()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: CameraCaptureManager? = null
    private var stopRunnable: Runnable? = null

    /** 0 = one shot (stop right after the capture); > 0 = the user's grant window. */
    private var keepAliveMs = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        owner.registry.currentState = Lifecycle.State.CREATED
        val mgr = CameraCaptureManager(applicationContext)
        mgr.attachLifecycleOwner(owner)
        manager = mgr
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val keepAliveMs = intent?.getLongExtra(EXTRA_KEEP_ALIVE_MS, 0L) ?: 0L
        this.keepAliveMs = keepAliveMs
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        if (keepAliveMs > 0L) {
            val capped = keepAliveMs.coerceAtMost(MAX_KEEP_ALIVE_MS)
            val runnable = Runnable {
                Log.i(TAG, "keep-alive window elapsed (${capped}ms) — stopping camera service")
                stopSelf()
            }
            stopRunnable = runnable
            mainHandler.postDelayed(runnable, capped)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        stopRunnable = null
        owner.registry.currentState = Lifecycle.State.DESTROYED
        manager = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Take one photo. Suspends until the JPEG is in hand (or throws), and releases the camera on
     * the way out. Must be called while the service is armed — see [arm].
     */
    suspend fun capture(facing: String, qualityPercent: Int, maxWidth: Int): CapturedPhoto {
        val mgr = manager ?: throw IllegalStateException("CAMERA_SERVICE_NOT_READY: service is not armed")
        val params = """{"facing":"$facing","quality":${(qualityPercent / 100.0).coerceIn(0.1, 1.0)},"maxWidth":$maxWidth}"""
        return withContext(Dispatchers.Main) {
            owner.registry.currentState = Lifecycle.State.RESUMED
            try {
                val payload = withContext(Dispatchers.IO) { mgr.snap(params) }
                parsePayload(payload.payloadJson, facing)
            } finally {
                owner.registry.currentState = Lifecycle.State.CREATED
                // "Approve once" (keepAlive = 0): the service has served its purpose, so drop the
                // notification. Timed grants keep it alive for the whole window instead.
                if (keepAliveMs <= 0L) mainHandler.postDelayed({ stopSelf() }, 1_000)
            }
        }
    }

    private fun parsePayload(json: String, facing: String): CapturedPhoto {
        val obj = Json.parseToJsonElement(json).jsonObject
        val b64 = obj["base64"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("CAMERA_CAPTURE_FAILED: payload carried no image")
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val width = obj["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val height = obj["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return CapturedPhoto(bytes, width, height, facing)
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "CameraCaptureService"
        private const val CHANNEL_ID = "agent_voice_camera"
        private const val NOTIFICATION_CHANNEL_NAME = "Camera capture"
        private const val NOTIFICATION_ID = 4712
        private const val NOTIFICATION_TITLE = "Camera ready"
        private const val NOTIFICATION_TEXT = "WakeHermesClaw can take photos while approved"
        const val EXTRA_KEEP_ALIVE_MS = "keepAliveMs"

        /** A Long.MAX_VALUE "until revoked" grant must not be turned into an endless notification. */
        const val MAX_KEEP_ALIVE_MS = 12L * 60 * 60 * 1000

        /** The capability this service exists for; the approval screen matches on it. */
        const val CAPABILITY_NAME = "camera.capture_photo"

        @Volatile private var instance: CameraCaptureService? = null

        fun get(): CameraCaptureService? = instance
        fun isRunning(): Boolean = instance != null

        /**
         * The approval screen arms us asynchronously, so a capability that runs right after the
         * approval tap can beat onCreate. Wait briefly for the instance instead of reporting
         * "not armed" for a service that is in fact on its way up.
         */
        suspend fun awaitArmed(timeoutMs: Long = 3_000L): CameraCaptureService? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                instance?.let { return it }
                kotlinx.coroutines.delay(50L)
            }
            return instance
        }

        /**
         * Arm the service. Must be called from a visible activity (the approval screen) or from an
         * exempt path, otherwise the platform throws ForegroundServiceStartNotAllowedException —
         * which is what the returned Boolean reports.
         */
        fun arm(context: Context, keepAliveMs: Long): Boolean {
            val intent = Intent(context, CameraCaptureService::class.java)
                .putExtra(EXTRA_KEEP_ALIVE_MS, keepAliveMs.coerceAtLeast(0L))
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "arm() refused by the platform: ${t::class.java.simpleName}: ${t.message}")
                false
            }
        }
    }
}
