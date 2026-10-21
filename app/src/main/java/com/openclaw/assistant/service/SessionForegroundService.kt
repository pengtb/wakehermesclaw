package com.openclaw.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.R

/**
 * Foreground service that runs during a voice interaction session.
 * Prevents the process from being frozen by aggressive battery optimization
 * (e.g. MIUI/HyperOS "greeze") while the user is in a conversation.
 */
class SessionForegroundService : Service() {

    companion object {
        private const val TAG = "SessionFgService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "session_channel"
        private const val ACTION_UPDATE_VISIBILITY = "com.openclaw.assistant.session.UPDATE_VISIBILITY"
        private const val EXTRA_PANEL_VISIBLE = "panel_visible"

        fun start(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Background execution limits prevented starting SessionForegroundService: ${e.message}", e)
                context.stopService(intent)
            } catch (e: SecurityException) {
                Log.e(TAG, "Security limits prevented starting SessionForegroundService: ${e.message}", e)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SessionForegroundService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }

        /** 服务是否已经 startForeground 过；没在跑时不能再 notify，否则会留下一条没人管的通知 */
        @Volatile
        private var foregroundStarted = false

        /**
         * 面板在前台还是被切到后台：决定通知正文写"进行中"还是"点按回到对话"。
         * 前台服务本身不能只在后台才挂通知，所以只能改文案。
         */
        fun updatePanelVisible(context: Context, visible: Boolean) {
            // 服务已经停了（对话结束）就别再 startService —— 那样会拉起一个只 notify 不留手的实例，
            // 通知就永远挂在状态栏上
            if (!foregroundStarted) return
            val intent = Intent(context, SessionForegroundService::class.java).apply {
                action = ACTION_UPDATE_VISIBILITY
                putExtra(EXTRA_PANEL_VISIBLE, visible)
            }
            runCatching { context.startService(intent) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var panelVisible = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_VISIBILITY) {
            if (!foregroundStarted) {
                stopSelf()
                return START_NOT_STICKY
            }
            panelVisible = intent.getBooleanExtra(EXTRA_PANEL_VISIBLE, panelVisible)
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(this)
                    .notify(NOTIFICATION_ID, createNotification())
            }
            return START_NOT_STICKY
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMicPermission) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            // This can happen on Android 14+ if the app is not in an eligible state
            // (e.g. started from background without a visible activity).
            // Attempting to fall back to an unrestricted type like dataSync for a microphone
            // service violates Play Store policy. Handle gracefully by stopping.
            Log.w(TAG, "startForeground(microphone) failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        foregroundStarted = true
        acquireWakeLock()
        Log.d(TAG, "Session foreground service started")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregroundStarted = false
        // stopService 不一定会把 notify() 更新过的那条一起收走，这里显式撤一次
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        }
        releaseWakeLock()
        Log.d(TAG, "Session foreground service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_session_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // 点通知要回到"语音对话"本身，不是回主页：发一条 app 内广播，
        // 由 OpenClawAssistantService 的接收器调 showSession()（会话已存在时就是重新显示面板）。
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(OpenClawAssistantService.ACTION_SHOW_ASSISTANT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_session_title))
            .setContentText(
                getString(
                    if (panelVisible) R.string.notification_session_content_active
                    else R.string.notification_session_content
                )
            )
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClawAssistant::SessionFgWakeLock"
        ).apply {
            acquire(5 * 60 * 1000L) // 5 min max
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}
