package com.openclaw.assistant.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.R
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the Mobile Bridge HTTP server. The user must
 * explicitly opt-in from Settings; the bridge is off by default. While running,
 * a sticky notification advises the user that an external agent can call into
 * the device through the bridge.
 *
 * The service exposes ACTION_START / ACTION_STOP intents so the rest of the
 * app (Settings switches, onboarding) can drive it without coupling to
 * Service internals.
 */
class MobileBridgeService : Service() {

    private var server: MobileBridgeServer? = null
    private var watchdogJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // 端口/绑定模式只在 MobileBridgeServer.start() 时读取，改了配置必须重建 server 才生效。
                // 这里原地重启：先停（释放端口），稍等一拍再起，避免 TIME_WAIT/占用导致 bind 失败。
                stopServer()
                ensureChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startServer()
                }, RESTART_DELAY_MS)
                return START_STICKY
            }
            else -> {
                ensureChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                startServer()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        if (server != null) return
        val cfg = MobileBridgeConfig.getInstance(this)
        if (!cfg.enabled.value) return
        val srv = MobileBridgeServer(this, cfg).also { it.start() }
        server = srv

        // Auto-disable watchdog — if cfg.autoDisableIdleMs > 0, stops the
        // service when no request has touched the server for that long.
        val idleMs = cfg.autoDisableIdleMs.value
        if (idleMs > 0L) {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
            watchdogJob = scope.launch {
                while (scope.isActive) {
                    kotlinx.coroutines.delay(15_000L)
                    val since = System.currentTimeMillis() - srv.lastActivityMs
                    if (since >= idleMs) {
                        stopServer()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                }
            }
        }
    }

    private fun stopServer() {
        watchdogJob?.cancel(); watchdogJob = null
        server?.stop()
        server = null
    }

    /**
     * 通知的文案要走「设置 → 显示语言」，而 Service 的 Context 是应用级的、不跟这个设置。
     * 这里用 LocaleAwareActivity 的包装函数拿一个本地化过的 Context。
     */
    private fun localized(): Context = runCatching {
        com.openclaw.assistant.ui.LocaleAwareActivity.wrapWithSavedLocale(this)
    }.getOrDefault(this)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ctx = localized()
            // 每次启动都写一遍：频道名/描述不会自己跟着语言变，重写才能更新过来
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.bridge_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = ctx.getString(R.string.bridge_notification_channel_desc)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val ctx = localized()
        // 点通知回主页：之前没有 contentIntent，点了没反应
        val openApp = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            CONTENT_INTENT_REQUEST,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder
            .setContentTitle(ctx.getString(R.string.bridge_notification_title))
            .setContentText(
                ctx.getString(
                    R.string.bridge_notification_text,
                    MobileBridgeConfig.getInstance(this).port.value
                )
            )
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agent_voice_bridge"
        private const val NOTIFICATION_ID = 0xB47D6E
        private const val CONTENT_INTENT_REQUEST = 0x0B4D

        const val ACTION_START = "com.openclaw.assistant.bridge.START"
        const val ACTION_STOP = "com.openclaw.assistant.bridge.STOP"
        const val ACTION_RESTART = "com.openclaw.assistant.bridge.RESTART"

        /** 停一拍再起的时间：给 ServerSocket 释放端口留点余量 */
        private const val RESTART_DELAY_MS = 400L

        fun restart(context: Context) {
            val intent = Intent(context, MobileBridgeService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /**
         * 保存设置时调用：桥开着就用新配置重启一次（bindMode/port 只在 server start() 时读取，
         * 不重建 server 就不会生效）。返回是否真的触发了重启。
         */
        fun restartIfRunning(context: Context): Boolean {
            val cfg = MobileBridgeConfig.getInstance(context)
            if (!cfg.enabled.value) return false
            restart(context)
            return true
        }

        fun start(context: Context) {
            val intent = Intent(context, MobileBridgeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
        fun stop(context: Context) {
            context.startService(Intent(context, MobileBridgeService::class.java).setAction(ACTION_STOP))
        }
    }
}
