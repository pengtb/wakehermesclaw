package com.openclaw.assistant.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.openclaw.assistant.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Voice Interaction Service
 * Launched as a system assistant with a long press of the home button
 */
class OpenClawAssistantService : VoiceInteractionService() {

    companion object {
        private const val TAG = "OpenClawAssistantSvc"
        private const val PENDING_SESSION_TIMEOUT_MS = 30_000L
        const val ACTION_SHOW_ASSISTANT = "com.openclaw.assistant.ACTION_SHOW_ASSISTANT"
        const val EXTRA_VOICE_TARGET = "com.openclaw.assistant.EXTRA_VOICE_TARGET"

        /**
         * 服务进程起来没有（created）／系统真的把服务绑定上了没有（ready）。
         * 主页按钮要靠这两个判断：系统可能停在 `mBound=true mService=null` 的半死状态
         * （进程被杀/更新安装后常见）——这时框架的 isActiveService() 仍返回 true，
         * 但 onReady 永远不会来，showSession() 只能排队到超时，界面上就是"点了没反应"。
         */
        private val _created = MutableStateFlow(false)
        val created: StateFlow<Boolean> = _created.asStateFlow()
        private val _ready = MutableStateFlow(false)
        val ready: StateFlow<Boolean> = _ready.asStateFlow()
    }

    private var isServiceReady = false
    private var pendingShowSession = false
    private var pendingSessionArgs: Bundle? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pendingSessionTimeoutRunnable = Runnable {
        if (pendingShowSession) {
            Log.w(TAG, "Pending showSession timed out. Clearing.")
            pendingShowSession = false
            pendingSessionArgs = null
        }
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.e(TAG, "Assistant trigger receiver triggered: ${intent?.action}")
            if (intent?.action == ACTION_SHOW_ASSISTANT) {
                triggerShowSession(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        _created.value = true
        Log.e(TAG, "VoiceInteractionService onCreate")
        val filter = IntentFilter(ACTION_SHOW_ASSISTANT)
        ContextCompat.registerReceiver(this, debugReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // 装包 / 被强停后热词服务会跟着一起没，之后再也没人拉它（只有开机和手动拨开关会拉），
        // 表现就是"唤醒词没反应"。系统 bind 助理的时候这里补一次。
        try {
            val hotwordOn = com.openclaw.assistant.data.SettingsRepository.getInstance(this).hotwordEnabled
            val canRecord = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hotwordOn && canRecord && !HotwordService.running.value) {
                HotwordService.start(this)
                Log.e(TAG, "onCreate: 唤醒词是开的但服务没跑，已重新拉起 HotwordService")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "重启唤醒词服务失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            Log.d(TAG, "onStartCommand received: null (system restart)")
            return START_STICKY
        }
        
        val action = intent.action
        Log.e(TAG, "onStartCommand received: $action")
        if (action == ACTION_SHOW_ASSISTANT) {
            triggerShowSession(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        Log.e(TAG, "onBind received: ${intent?.action}")
        return super.onBind(intent)
    }

    private fun triggerShowSession(sourceIntent: Intent? = null) {
        val compName = ComponentName(this, OpenClawAssistantService::class.java)
        val isActive = isActiveService(this, compName)
        Log.e(TAG, "triggerShowSession: isServiceReady=$isServiceReady, isActiveService=$isActive")
        val args = buildSessionArgs(sourceIntent)
        
        if (isServiceReady) {
            try {
                showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                Log.e(TAG, "showSession() called immediately")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call showSession immediately", e)
                if (BuildConfig.FIREBASE_ENABLED) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        } else {
            Log.e(TAG, "Service not ready (mBound 但没 onReady). 尝试让系统重新拉起助理。")
            // 进程被杀/装包后，系统这边 mBound 仍是 true、连接却已经死了：此时本进程自己
            // startService 只会走到这里排队，永远不会 onReady（实测 02:19 那次就是这样卡了 30 秒）。
            // 真正能让系统重新 bind 的是"让系统去启动助理"，所以这里发一次 ACTION_ASSIST 当敲门砖。
            val nudged = try {
                startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.e(TAG, "已发出 ACTION_ASSIST 请求系统重新绑定助理")
                true
            } catch (e: Exception) {
                Log.e(TAG, "ACTION_ASSIST 失败，继续排队: ${e.message}")
                false
            }
            if (nudged) {
                // 系统那边会重新 bind 并自己拉一次会话；onReady 到了仍会把排队的请求补发一次，
                // 保证即使 ACTION_ASSIST 没落地也还有兜底。
                Log.e(TAG, "nudged=$nudged，仍保留排队兜底")
            }
            pendingShowSession = true
            pendingSessionArgs = args
            handler.removeCallbacks(pendingSessionTimeoutRunnable)
            handler.postDelayed(pendingSessionTimeoutRunnable, PENDING_SESSION_TIMEOUT_MS)
        }
    }

    private fun buildSessionArgs(sourceIntent: Intent?): Bundle {
        return Bundle().apply {
            sourceIntent?.getStringExtra(EXTRA_VOICE_TARGET)?.takeIf { it.isNotBlank() }?.let { target ->
                putString(EXTRA_VOICE_TARGET, target)
            }
        }
    }

    override fun onReady() {
        super.onReady()
        Log.e(TAG, "VoiceInteractionService onReady")
        isServiceReady = true
        _ready.value = true
        if (pendingShowSession) {
            pendingShowSession = false
            handler.removeCallbacks(pendingSessionTimeoutRunnable)
            try {
                val args = pendingSessionArgs ?: Bundle()
                pendingSessionArgs = null
                showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                Log.e(TAG, "showSession() called from onReady (pending)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call pending showSession", e)
                if (BuildConfig.FIREBASE_ENABLED) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
        }
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.e(TAG, "VoiceInteractionService onShutdown")
        isServiceReady = false
        _ready.value = false
        pendingShowSession = false
        pendingSessionArgs = null
        handler.removeCallbacks(pendingSessionTimeoutRunnable)
        try {
            unregisterReceiver(debugReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "debugReceiver already unregistered", e)
        }
    }
}
