package com.openclaw.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.service.voice.VoiceInteractionSession
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import com.openclaw.assistant.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.openclaw.assistant.R
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.backend.VoiceBackendSelector
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.speech.asr.AsrManager
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.speech.TTSState
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

/**
 * Voice Interaction Session
 * Handles actual voice interaction
 */
class OpenClawSession(
    context: Context,
    initialSessionArgs: Bundle? = null
) : VoiceInteractionSession(context),
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {

    companion object {
        private const val TAG = "OpenClawSession"

        /** 主页对讲按钮 → 会话：收尾当前这轮识别 */
        const val ACTION_STOP_LISTENING = "com.openclaw.assistant.ACTION_STOP_LISTENING"
        const val EXTRA_CANCEL = "com.openclaw.assistant.EXTRA_CANCEL"
        private const val INITIAL_FILLER_DELAY_MS = 750L
        private const val INTERRUPT_LISTEN_DELAY_MS = 350L
    }

    private val settings = SettingsRepository.getInstance(context)
    private var sessionArgs: Bundle? = initialSessionArgs
    private val apiClient = OpenClawClient()
    private lateinit var speechManager: AsrManager
    private lateinit var ttsManager: TTSManager
    
    // Repository
    private val chatRepository = com.openclaw.assistant.data.repository.ChatRepository.getInstance(context)
    private var currentSessionId: String? = null
    
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var initialFillerPhraseJob: Job? = null
    private var auxiliarySpeechJob: Job? = null
    @Volatile private var ignoreNextTtsStop = false
    /** 主页"语音对话"按钮（对讲模式）用它来收尾：stop=说完发送，cancel=这轮不要了 */
    private val stopListeningReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STOP_LISTENING) return
            val cancel = intent.getBooleanExtra(EXTRA_CANCEL, false)
            Log.d(TAG, "收到 ACTION_STOP_LISTENING cancel=$cancel, state=" + currentState.value)
            if (cancel) {
                speechManager.cancelListening()
                finish()
            } else {
                speechManager.stopListening()
            }
        }
    }

    private val interruptReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.openclaw.assistant.ACTION_INTERRUPT_TTS") return
            if (currentState.value != AssistantState.SPEAKING &&
                currentState.value != AssistantState.PREPARING_SPEECH) return
            Log.d(TAG, "Barge-in interrupt received in OpenClawSession")
            interruptAndListen()
        }
    }

    // UI State
    private var currentState = mutableStateOf(AssistantState.IDLE)
    private var displayText = mutableStateOf("")
    private var userQuery = mutableStateOf("") // User's spoken text
    private var partialText = mutableStateOf("")
    private var errorMessage = mutableStateOf<String?>(null)
    private var audioLevel = mutableStateOf(0f) // Audio level for visualization

    private fun effectiveVoiceTarget(): String {
        return sessionArgs?.getString(OpenClawAssistantService.EXTRA_VOICE_TARGET)
            ?.takeIf { it == SettingsRepository.VOICE_TARGET_OPENCLAW || it == SettingsRepository.VOICE_TARGET_HERMES }
            // 只有"Hermes HTTP"才落到 Hermes；网关与 OpenClaw HTTP 都是 OpenClaw 产品
            ?: if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_HTTP) {
                SettingsRepository.VOICE_TARGET_HERMES
            } else {
                SettingsRepository.VOICE_TARGET_OPENCLAW
            }
    }

    private fun isOpenClawVoiceTarget(): Boolean {
        return effectiveVoiceTarget() == SettingsRepository.VOICE_TARGET_OPENCLAW
    }

    /**
     * O网关不可用时的提示。
     * 本机明明配了 Hermes 后端却还指向 OpenClaw 网关，多半是设置里"连接方式"选错了 ——
     * 这时候只说"网关未连接"没法定毛病，直接告诉用户改成"用 HTTP"。
     */
    private fun gatewayUnavailableMessage(): String {
        val hasHermes = runCatching {
            com.openclaw.assistant.backend.BackendRepository.getInstance(context).backends.value
                .any { it.enabled && it.type == com.openclaw.assistant.backend.BackendType.HERMES_API_SERVER }
        }.getOrDefault(false)
        return if (hasHermes) {
            context.getString(R.string.error_voice_use_http_hint)
        } else {
            context.getString(R.string.error_gateway_not_connected)
        }
    }

    /**
     * 给错误加上"是哪一环出的问题"。
     * 面板上只有一行字，只有错误码（比如 HTTP 502）时用户没法知道是识别、后端还是配置的问题。
     */
    private fun staged(stageRes: Int, detail: String?): String {
        val stage = context.getString(stageRes)
        val text = detail?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_network)
        return context.getString(R.string.error_with_stage, stage, text)
    }

    // WakeLock to keep CPU alive during voice conversation when screen is off
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        Log.e(TAG, "Session onCreate start")
        super.onCreate()
        
        // Initialize lifecycle and saved state here (once per session lifetime)
        try {
            savedStateRegistryController.performAttach()
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already attached?", e)
        }
        
        try {
            savedStateRegistryController.performRestore(null)
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already restored?", e)
        }

        try {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        } catch (e: Exception) {
             Log.w(TAG, "Lifecycle ON_CREATE failed", e)
        }

        speechManager = AsrManager(context)
        ttsManager = TTSManager(context)
        val initialized = ttsManager.initializeCurrentProvider()
        Log.e(TAG, "Session TTS: initialized=$initialized ready=${ttsManager.isReady()} error=${ttsManager.getErrorMessage()}")
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            interruptReceiver,
            android.content.IntentFilter("com.openclaw.assistant.ACTION_INTERRUPT_TTS"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            stopListeningReceiver,
            android.content.IntentFilter(ACTION_STOP_LISTENING),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
    
    // Audio Cue
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
    private val toneGeneratorReleased = AtomicBoolean(false)

    private fun playTone(tone: Int, durationMs: Int = -1) {
        if (toneGeneratorReleased.get()) return
        try {
            if (durationMs == -1) toneGenerator.startTone(tone)
            else toneGenerator.startTone(tone, durationMs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator already released", e)
        }
    }

    // AudioFocus management
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // Session foreground service keeps process alive during conversation (prevents MIUI/HyperOS freeze)

    override val lifecycle: androidx.lifecycle.Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreateContentView(): View {
        Log.e(TAG, "Session onCreateContentView")
        val composeView = ComposeView(context).apply {
            Log.e(TAG, "Initializing ComposeView with owners")
            // 窗口是 wrap_content：这个 View 也必须 wrap，否则子 View 撑满屏幕、窗口又变回全屏
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Set ViewTree owners using extensions
            try {
                setViewTreeLifecycleOwner(this@OpenClawSession)
                setViewTreeViewModelStoreOwner(this@OpenClawSession)
                setViewTreeSavedStateRegistryOwner(this@OpenClawSession)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ViewTree owners", e)
            }
            
            setContent {
                // 面板要跟系统深浅色走，这里必须套主题——之前没套，用的是 Compose 默认浅色，
                // 于是主页是深色、语音面板永远是白底。
                OpenClawAssistantTheme {
                    AssistantUI(
                        state = currentState.value,
                        displayText = displayText.value,
                        userQuery = userQuery.value,
                        partialText = partialText.value,
                        errorMessage = errorMessage.value,
                        audioLevel = audioLevel.value,
                        onClose = {
                            isUserDismissed = true
                            finish()
                        },
                        onRetry = { startListening() },
                        onInterrupt = { interruptAndListen() },
                        onStopListening = { speechManager.stopListening() }
                    )
                }
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionArgs = args

        // Recreate scope if it was cancelled by a previous onHide()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        // Ensure any existing SpeechRecognizerManager is cleaned up before creating a new one
        if (this::speechManager.isInitialized) {
            try {
                speechManager.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to destroy existing AsrManager before recreation", e)
            }
        }
        speechManager = AsrManager(context)

        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

        // Start foreground service to keep process alive during conversation (screen off)
        SessionForegroundService.start(context)

        Log.d(TAG, "Session shown with flags: $showFlags")

        // 面板原本是全屏窗口（TYPE_VOICE_INTERACTION），连状态栏一起盖住，
        // 结果通知栏拉不下来、批准类通知点不到。这里把窗口改成"底部卡片"：
        // 高度 = 屏幕 - 状态栏 - 一点余量，状态栏那一条留给系统，下拉通知栏照常可用。
        applyBottomCardWindowLayout()

        // PAUSE Hotword Service to prevent microphone conflict
        sendPauseBroadcast()
        
        // SESSION MANAGEMENT
        if (isOpenClawVoiceTarget()) {
            // Gateway mode: manage session on the gateway side, not in local DB
            val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
            if (!settings.resumeLatestSession) {
                // Start a fresh gateway session with a human-readable label
                val newKey = java.util.UUID.randomUUID().toString()
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val label = String.format(context.getString(R.string.default_session_title_format), timeStr)
                nodeRuntime.switchChatSession(newKey)
                scope.launch { nodeRuntime.patchChatSession(newKey, label) }
            }
            // resumeLatestSession ON → keep the current active gateway session as-is
        } else {
            scope.launch {
                try {
                    val latestSession = if (settings.resumeLatestSession) chatRepository.getLatestSession() else null
                    if (latestSession != null) {
                        currentSessionId = latestSession.id
                        Log.d(TAG, "Resuming latest session: $currentSessionId")
                    } else {
                        currentSessionId = chatRepository.createSession(title = String.format(context.getString(R.string.default_session_title_format), java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())))
                        Log.d(TAG, "Created new session: $currentSessionId")
                    }

                    // Store this ID in settings so ChatActivity and API calls use it
                    // 标签按这条链路实际用的产品写死：走到这里说明目标是 Hermes（网关目标会走上面那支）。
                    // 不写的话聊天列表会退回到"有 OpenClaw 配置就显示 OpenClaw"的启发式，标签就错了。
                    currentSessionId?.let { id ->
                        settings.sessionId = id
                        com.openclaw.assistant.data.SessionProductStore.save(
                            context, id, com.openclaw.assistant.ChatProduct.HERMES
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle session", e)
                    if (BuildConfig.FIREBASE_ENABLED) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
                }
            }
        }
        
        // Check settings
        if (!settings.isConfigured()) {
            currentState.value = AssistantState.ERROR
            errorMessage.value = staged(
                R.string.error_stage_config,
                context.getString(R.string.error_config_required)
            )
            displayText.value = context.getString(R.string.config_required)
            return
        }

        // For Gateway mode, fail fast if the gateway is not healthy
        if (isOpenClawVoiceTarget()) {
            val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
            if (!nodeRuntime.chatHealthOk.value) {
                currentState.value = AssistantState.ERROR
                errorMessage.value = staged(R.string.error_stage_backend, gatewayUnavailableMessage())
                displayText.value = context.getString(R.string.config_required)
                return
            }
        }

        // Start speech recognition
        startListening()
    }
    
    /**
     * 把会话窗口收成底部卡片，给状态栏留出可点的区域。
     *
     * 为什么需要：VoiceInteractionSession 的窗口默认铺满整屏，Compose 根布局又是一层
     * 70% 黑底，于是状态栏也被盖在下面 —— 通知栏拉不下来，审批通知点不到。
     * 窗口高度减掉状态栏（再加一点余量）后，顶部那一条仍归 SystemUI，下拉手势正常。
     */
    private fun applyBottomCardWindowLayout() {
        runCatching {
            // VoiceInteractionSession.window 是 Dialog，真正的窗口在 dialog.window 上
            val win = window?.window ?: return@runCatching
            val lp = win.attributes
            lp.gravity = android.view.Gravity.BOTTOM
            // 高度跟着内容走：面板多高窗口就多高，不留一大片空背景
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            // 不加背景遮罩，只让卡片浮在应用上方
            lp.dimAmount = 0f
            lp.flags = lp.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            win.setAttributes(lp)
            Log.d(TAG, "Session window adjusted: wrap_content at bottom")
        }.onFailure {
            Log.w(TAG, "Session window adjust failed", it)
        }
    }

    override fun onHide() {
        super.onHide()

        // ユーザーが明示的に Close ボタンを押した場合は、音声状態に関わらず必ずクリーンアップ
        if (isUserDismissed) {
            isUserDismissed = false
            cleanupSession()
            return
        }

        // If a voice session is active (listening, thinking, or speaking),
        // keep resources alive so conversation continues with screen off.
        val state = currentState.value
        val isVoiceActive = state == AssistantState.LISTENING ||
                state == AssistantState.THINKING ||
                state == AssistantState.SPEAKING ||
                state == AssistantState.PREPARING_SPEECH ||
                state == AssistantState.PROCESSING
        
        if (isVoiceActive) {
            Log.d(TAG, "onHide: voice session active ($state), keeping resources alive")
            // Keep scope, speechManager, ttsManager alive
            // SessionForegroundService is already running
            return
        }

        cleanupSession()
    }

    private fun cleanupSession() {
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)

        // Clean up audio resources
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        abandonAudioFocus()
        SessionForegroundService.stop(context)
        scope.cancel()
        speechManager.destroy()
        ttsManager.stop()
        releaseWakeLock()

        // Resume Hotword
        sendResumeBroadcast()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        
        // Resume Hotword (safety)
        sendResumeBroadcast()
        try {
            context.unregisterReceiver(interruptReceiver)
            runCatching { context.unregisterReceiver(stopListeningReceiver) }
        } catch (_: Exception) {
        }

        SessionForegroundService.stop(context)
        ttsManager.shutdown()
        toneGeneratorReleased.set(true)
        toneGenerator.release()
        releaseWakeLock()
    }

    private fun sendPauseBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
    
    private fun sendResumeBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    // Must implement ViewModelStoreOwner for Compose
    override val viewModelStore: androidx.lifecycle.ViewModelStore = androidx.lifecycle.ViewModelStore()

    private var listeningJob: Job? = null
    private var speakingJob: Job? = null
    private var isUserDismissed = false

    private fun startListening(initialDelayMs: Long = 50L) {
        Log.d(TAG, "startListening() called, currentState=${currentState.value}, listeningJob=${listeningJob}, speakingJob=${speakingJob}")
        listeningJob?.cancel()
        acquireWakeLock()
        sendPauseBroadcast()

        currentState.value = AssistantState.PROCESSING
        displayText.value = ""
        userQuery.value = ""
        partialText.value = ""
        errorMessage.value = null
        audioLevel.value = 0f

        // Fallback: if SpeechResult.Ready doesn't arrive within 2s, force LISTENING
        // so users don't see "Processing" indefinitely while the recognizer warms up
        scope.launch {
            delay(2000L)
            if (currentState.value == AssistantState.PROCESSING) {
                Log.w(TAG, "SpeechResult.Ready timeout — forcing LISTENING state")
                currentState.value = AssistantState.LISTENING
            }
        }

        listeningJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false
            
            // Wait for resources to be released before reopening the mic
            delay(initialDelayMs)

            while (isActive && !hasActuallySpoken) {
                // Request audio focus
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest = android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    ).build()
                    audioManager.requestAudioFocus(audioFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null,
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }

                // 硬超时只当最后兜底：录音类后端的"没听到人声/麦克风读不到"都由引擎自己报错收尾，
                // 这里主要防"卡死不回"。别设太小 —— 懒加载的转写服务冷启动要十几秒到一两分钟，
                // 结果晚回来一点点就会被这个超时连同结果一起丢掉（表现为界面空文本）。
                val listenResult = withTimeoutOrNull(150_000L) {
                    speechManager.startListening(settings.speechLanguage.ifEmpty { null }, settings.speechSilenceTimeout).collectLatest { result ->
                        when (result) {
                            is SpeechResult.Ready -> {
                                Log.d(TAG, "SpeechResult.Ready received, transitioning to LISTENING")
                                currentState.value = AssistantState.LISTENING
                                playTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                            }
                            is SpeechResult.Processing -> {
                                // 录完、正在上传/等服务端：切"处理中"，界面不至于看起来没反应
                                if (currentState.value == AssistantState.LISTENING) {
                                    currentState.value = AssistantState.PROCESSING
                                }
                            }
                            is SpeechResult.Listening -> {
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.RmsChanged -> {
                                audioLevel.value = result.rmsdB
                            }
                            is SpeechResult.PartialResult -> {
                                partialText.value = result.text
                                // Ensure state is listening if we get partial results
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.Result -> {
                                Log.d(TAG, "SpeechResult.Result received: text='${result.text}'")
                                hasActuallySpoken = true
                                userQuery.value = result.text
                                sendToOpenClaw(result.text)
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                              result.code == SpeechRecognizer.ERROR_NO_MATCH

                                if (isTimeout && settings.continuousMode && elapsed < 10000) {
                                    Log.d(TAG, "Speech timeout within 10s window ($elapsed ms), retrying...")
                                } else if (
                                    result.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                    result.code == SpeechRecognizer.ERROR_CLIENT
                                ) {
                                    speechManager.destroy()
                                    delay(500)
                                } else if (isTimeout) {
                                    // Timeout - close session without error screen
                                    // But only if AI is not currently thinking or speaking
                                    val state = currentState.value
                                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                                        Log.d(TAG, "Speech timeout but AI is $state, not closing session")
                                        hasActuallySpoken = true // break the loop but don't finish
                                    } else {
                                        playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                        finish() // Close the session
                                    }
                                } else {
                                    playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                    currentState.value = AssistantState.ERROR
                                    errorMessage.value = staged(R.string.error_stage_voice_input, result.message)
                                    hasActuallySpoken = true
                                }
                            }
                            else -> {}
                        }
                    }
                }

                if (listenResult == null && !hasActuallySpoken) {
                    Log.w(TAG, "Speech recognition timed out (150s). Device may be locked.")
                    // Manual timeout - only close session if not thinking/speaking
                    val state = currentState.value
                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                        Log.d(TAG, "Manual timeout but AI is $state, not closing session")
                        hasActuallySpoken = true // break the loop but don't finish
                    } else {
                        finish() // Close the session
                        hasActuallySpoken = true
                    }
                }
                
                if (!hasActuallySpoken) {
                    delay(300)
                }
            }
        }
    }

    private var thinkingSoundJob: Job? = null

    private fun startThinkingSound() {
        thinkingSoundJob?.cancel()
        if (!settings.thinkingSoundEnabled) return
        thinkingSoundJob = scope.launch {
            delay(2000)
            while (isActive) {
                playTone(android.media.ToneGenerator.TONE_SUP_RINGTONE, 100)
                delay(3000)
            }
        }
    }

    private fun stopThinkingSound() {
        thinkingSoundJob?.cancel()
        thinkingSoundJob = null
    }

    private fun sendToOpenClaw(message: String) {
        // 说中退出词（"退出"/"exit"…）就直接收尾，不送后端
        if (com.openclaw.assistant.speech.ExitPhrases.isExit(context, message)) {
            Log.d(TAG, "exit phrase matched: '$message', finishing session")
            playTone(android.media.ToneGenerator.TONE_PROP_ACK, 120)
            finish()
            return
        }
        Log.d(TAG, "sendToOpenClaw() called, transitioning to THINKING")
        currentState.value = AssistantState.THINKING
        playTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        startThinkingSound()
        displayText.value = ""

        // 相槌フレーズの再生（LLMへのリクエストと並行して実行）
        if (settings.fillerPhrasesEnabled) {
            scheduleInitialFillerPhrase()
        }

        scope.launch {
            val agentId = settings.defaultAgentId.takeIf { it.isNotBlank() && it != "main" }
            val voiceBackendId = resolveVoiceSessionBackendId()
            if (!isOpenClawVoiceTarget() && voiceBackendId == null) {
                cancelInitialFillerPhrase()
                cancelWaitPhraseTimer()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = staged(
                    R.string.error_stage_config,
                    context.getString(R.string.av_settings_no_hermes)
                )
                return@launch
            }
            val primaryReply = try {
                if (voiceBackendId != null) {
                    com.openclaw.assistant.backend.PrimaryBackendDispatcher.send(
                        context = context,
                        userText = message,
                        backendId = voiceBackendId,
                        sessionId = settings.sessionId,
                        agentId = agentId,
                        voiceMode = true,
                    )
                } else {
                    null
                }
            } catch (e: Throwable) {
                cancelInitialFillerPhrase()
                cancelWaitPhraseTimer()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = staged(R.string.error_stage_backend, e.message ?: context.getString(R.string.error_network))
                return@launch
            }
            if (primaryReply != null) {
                val text = primaryReply.text
                if (text.isNotBlank()) {
                    displayText.value = text
                    handleResponseReceived(text)
                } else {
                    cancelInitialFillerPhrase()
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = staged(R.string.error_stage_backend, context.getString(R.string.error_no_response))
                }
                return@launch
            }

            if (!isOpenClawVoiceTarget()) {
                cancelInitialFillerPhrase()
                cancelWaitPhraseTimer()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = staged(
                    R.string.error_stage_config,
                    context.getString(R.string.av_settings_no_hermes)
                )
                return@launch
            }

            // Save user message to local DB only for HTTP mode
            if (!isOpenClawVoiceTarget()) {
                currentSessionId?.let { sessionId ->
                    chatRepository.addMessage(sessionId, message, isUser = true)
                }
            }

            if (isOpenClawVoiceTarget()) {
                sendViaGateway(message)
            } else {
                sendViaHttp(message)
            }
        }
    }

    private suspend fun resolveVoiceSessionBackendId(): String? {
        val backends = com.openclaw.assistant.backend.BackendRepository.getInstance(context).backends.first()
        return VoiceBackendSelector.selectBackendId(
            voiceTarget = effectiveVoiceTarget(),
            backends = backends,
            gatewayHealthy = (context.applicationContext as OpenClawApplication).nodeRuntime.chatHealthOk.value,
            connectionMode = settings.wakewordConnectionType,
        )
    }

    private suspend fun resolveOpenClawGatewayModel(): String? {
        val backends = com.openclaw.assistant.backend.BackendRepository.getInstance(context).backends.first()
            .filter { it.enabled }
        return backends.firstOrNull {
            it.isPrimary && it.type == com.openclaw.assistant.backend.BackendType.OPENCLAW_GATEWAY
        }?.modelName?.takeIf { it.isNotBlank() }
            ?: backends.firstOrNull {
                it.type == com.openclaw.assistant.backend.BackendType.OPENCLAW_GATEWAY
            }?.modelName?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveLegacyOpenClawModel(): String? {
        val backends = com.openclaw.assistant.backend.BackendRepository.getInstance(context).backends.first()
            .filter { it.enabled }
        return backends.firstOrNull {
            it.isPrimary && it.type == com.openclaw.assistant.backend.BackendType.OPENCLAW_HTTP
        }?.modelName?.takeIf { it.isNotBlank() }
            ?: backends.firstOrNull {
                it.type == com.openclaw.assistant.backend.BackendType.OPENCLAW_HTTP
            }?.modelName?.takeIf { it.isNotBlank() }
    }

    private var waitPhraseJob: Job? = null

    private fun scheduleInitialFillerPhrase() {
        cancelInitialFillerPhrase()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        initialFillerPhraseJob = scope.launch {
            delay(INITIAL_FILLER_DELAY_MS)
            if (currentState.value == AssistantState.THINKING && isActive) {
                playFillerPhrase()
            }
        }
    }

    private fun cancelInitialFillerPhrase() {
        initialFillerPhraseJob?.cancel()
        initialFillerPhraseJob = null
    }

    private fun startWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        waitPhraseJob = scope.launch {
            delay(5000) // 5秒待機
            if (currentState.value == AssistantState.THINKING && isActive) {
                Log.d(TAG, "Wait phrase timer triggered (> 5s). Playing wait phrase.")
                playWaitPhrase()
            }
        }
    }

    private fun cancelWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        waitPhraseJob = null
    }

    private fun playFillerPhrase() {
        val phrase = context.getString(R.string.filler_ok)
        
        stopAuxiliarySpeech()
        var playbackJob: Job? = null
        playbackJob = scope.launch {
            try {
                // 相槌は progress 監視せずに即座に発話だけさせる
                ttsManager.speakWithProgress(phrase).collect {} 
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play filler phrase", e)
            } finally {
                if (auxiliarySpeechJob === playbackJob) {
                    auxiliarySpeechJob = null
                }
            }
        }
        auxiliarySpeechJob = playbackJob
    }

    private fun playWaitPhrase() {
        val waitPhrases = listOf(
            context.getString(R.string.wait_phrase_let_me_think),
            context.getString(R.string.wait_phrase_one_moment),
            context.getString(R.string.wait_phrase_checking)
        )
        val phrase = waitPhrases.random()
        
        stopAuxiliarySpeech()
        var playbackJob: Job? = null
        playbackJob = scope.launch {
            try {
                ttsManager.speakWithProgress(phrase).collect {}
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play wait phrase", e)
            } finally {
                if (auxiliarySpeechJob === playbackJob) {
                    auxiliarySpeechJob = null
                }
            }
        }
        auxiliarySpeechJob = playbackJob
    }

    private suspend fun sendViaGateway(message: String) {
        val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
        if (!nodeRuntime.chatHealthOk.value) {
            cancelInitialFillerPhrase()
            stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = gatewayUnavailableMessage()
            return
        }

        try {
            val assistantCountBefore = nodeRuntime.chatMessages.value.count { it.role == "assistant" }

            startWaitPhraseTimer() // 待ちフレーズのタイマー開始

            nodeRuntime.sendChat(
                message = message,
                thinking = "low",
                attachments = emptyList(),
                modelName = resolveOpenClawGatewayModel(),
            )

            // Wait for a new complete assistant response (timeout 60s).
            // chatMessages only adds a message when the full response is committed,
            // so watching it avoids both the pendingRunCount==0 early-fire issue
            // and streaming partial-text races.
            val responseText = withTimeoutOrNull(60_000L) {
                nodeRuntime.chatMessages
                    .first { messages -> messages.count { it.role == "assistant" } > assistantCountBefore }
                    .lastOrNull { it.role == "assistant" }
                    ?.content?.firstOrNull { it.type == "text" }?.text
            }

            cancelWaitPhraseTimer()

            if (responseText != null) {
                displayText.value = responseText
                handleResponseReceived(responseText)
            } else {
                cancelInitialFillerPhrase()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = staged(R.string.error_stage_backend, context.getString(R.string.error_no_response))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gateway error", e)
            cancelInitialFillerPhrase()
            cancelWaitPhraseTimer()
            stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = staged(R.string.error_stage_backend, e.message ?: context.getString(R.string.error_network))
        }
    }

    private suspend fun sendViaHttp(message: String) {
        val agentId = settings.defaultAgentId.takeIf { it.isNotBlank() && it != "main" }

        startWaitPhraseTimer()

        // Route through the configured Primary backend first. Older installs
        // without migrated backend records fall through to the legacy HTTP path.
        val primaryReply = try {
            com.openclaw.assistant.backend.PrimaryBackendDispatcher.sendPrimary(
                context = context,
                userText = message,
                sessionId = settings.sessionId,
                agentId = agentId,
                voiceMode = true,
            )
        } catch (e: Throwable) {
            cancelWaitPhraseTimer(); cancelInitialFillerPhrase(); stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = staged(R.string.error_stage_backend, e.message ?: context.getString(R.string.error_network))
            return
        }
        if (primaryReply != null) {
            cancelWaitPhraseTimer()
            val text = primaryReply.text
            if (text.isNotBlank()) {
                displayText.value = text
                handleResponseReceived(text)
            } else {
                cancelInitialFillerPhrase(); stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = staged(R.string.error_stage_backend, context.getString(R.string.error_no_response))
            }
            return
        }

        val result = apiClient.sendMessage(
            httpUrl = settings.getChatCompletionsUrl(),
            message = message,
            sessionId = settings.sessionId,
            authToken = settings.authToken.takeIf { it.isNotBlank() },
            agentId = agentId,
            modelName = resolveLegacyOpenClawModel(),
        )

        cancelWaitPhraseTimer()

        result.fold(
            onSuccess = { response ->
                val responseText = response.getResponseText()
                if (responseText != null) {
                    displayText.value = responseText
                    handleResponseReceived(responseText)
                } else if (response.error != null) {
                    cancelInitialFillerPhrase()
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = staged(R.string.error_stage_backend, response.error)
                } else {
                    cancelInitialFillerPhrase()
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = staged(R.string.error_stage_backend, context.getString(R.string.error_no_response))
                }
            },
            onFailure = { error ->
                Log.e(TAG, "API error", error)
                cancelInitialFillerPhrase()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = staged(R.string.error_stage_backend, error.message ?: context.getString(R.string.error_network))
            }
        )
    }

    private suspend fun handleResponseReceived(responseText: String) {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopAuxiliarySpeech()

        // Save AI response to local DB only for HTTP mode
        if (!isOpenClawVoiceTarget()) {
            currentSessionId?.let { sessionId ->
                chatRepository.addMessage(sessionId, responseText, isUser = false)
            }
        }

        if (settings.ttsEnabled) {
            // Thinking sound continues until actual audio playback starts
            speakResponse(responseText)
        } else if (settings.continuousMode) {
            stopThinkingSound()
            delay(500)
            startListening()
        } else {
            stopThinkingSound()
            // TTS disabled & continuous conversation OFF: Return to IDLE and close the panel
            currentState.value = AssistantState.IDLE
            SessionForegroundService.stop(context)
            // 同 speakResponse 里的那一支：非连续对话这一轮结束就收面板
            Log.d(TAG, "Response done, no TTS, continuous mode OFF. Finishing session.")
            finish()
        }
    }

    private fun interruptAndListen() {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        listeningJob?.cancel()
        sendPauseBroadcast()
        ignoreNextTtsStop = true
        ttsManager.stop()
        speakingJob?.cancel()
        speakingJob = null
        speechManager.destroy()
        abandonAudioFocus()
        currentState.value = AssistantState.PROCESSING
        partialText.value = ""
        errorMessage.value = null
        scope.launch {
            delay(INTERRUPT_LISTEN_DELAY_MS)
            startListening()
        }
    }

    private fun speakResponse(text: String) {
        Log.d(TAG, "speakResponse() called, text length=${text.length}")
        // Thinking sound continues until TTSState.Speaking is received
        currentState.value = AssistantState.PREPARING_SPEECH
        val cleanText = TTSUtils.stripMarkdownForSpeech(text)

        speakingJob = scope.launch {
            ignoreNextTtsStop = false
            try {
                val maxLen = minOf(TTSUtils.getMaxInputLength(null), 1000)
                val chunks = TTSUtils.splitTextForTTS(cleanText, maxLen)
                var success = chunks.isNotEmpty()
                for (chunk in chunks) {
                    var chunkSuccess = false
                    ttsManager.speakWithProgress(chunk).collect { state ->
                        when (state) {
                            is TTSState.Preparing -> {
                                Log.d(TAG, "TTS Preparing")
                                // Keep PREPARING_SPEECH state
                            }
                            is TTSState.Speaking -> {
                                Log.d(TAG, "TTS Speaking")
                                stopThinkingSound()
                                currentState.value = AssistantState.SPEAKING
                                // Barge-inが有効な場合、読み上げ開始時にHotwordServiceを再開する
                                if (settings.ttsBargeInEnabled) {
                                    sendResumeBroadcast()
                                }
                            }
                            is TTSState.Done -> {
                                Log.d(TAG, "TTS Done")
                                chunkSuccess = true
                            }
                            is TTSState.Error -> {
                                if (ignoreNextTtsStop) {
                                    Log.d(TAG, "Ignoring TTS stop during controlled interruption")
                                    return@collect
                                }
                                Log.e(TAG, "TTS Error: ${state.message}")
                                chunkSuccess = false
                            }
                        }
                    }
                    if (!chunkSuccess) {
                        success = false
                        break
                    }
                }

                // abandonAudioFocus() : 連続対話やBarge-in対応のため、ここでは即座にfocusを捨てない運用にするか、
                // 次のアクション(マイクの起動等)まで保持しておく選択もある。
                // 既存の挙動を尊重し一旦残す
                abandonAudioFocus()

                // 読み上げ終了時にBarge-inのために再開していたHotwordServiceを再度一時停止させる
                // (この後すぐにlisteningに入る場合はそちらでpauseされるが念のため)
                if (settings.ttsBargeInEnabled && !settings.continuousMode) {
                    sendPauseBroadcast()
                }

                if (ignoreNextTtsStop) {
                    return@launch
                }

                if (success) {
                    // After speech completion, if continuous conversation mode is enabled, start listening again
                    if (settings.continuousMode) {
                        Log.d(TAG, "TTS complete, continuous mode ON. Starting 2nd rally startListening() in 500ms")
                        delay(500)
                        startListening()
                    } else {
                        // If continuous conversation is OFF, end the session
                        currentState.value = AssistantState.IDLE
                        releaseWakeLock()
                        SessionForegroundService.stop(context)
                        // 非连续对话：这一轮播完就关面板。只置 IDLE 的话，语音面板（独立窗口）
                        // 会一直挂在主页上，用户得自己点右上角关掉
                        Log.d(TAG, "TTS complete, continuous mode OFF. Finishing session.")
                        finish()
                    }
                } else {
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_speech_general)
                }
            } catch (e: CancellationException) {
                if (!ignoreNextTtsStop) {
                    throw e
                }
            } catch (e: Exception) {
                if (ignoreNextTtsStop) {
                    return@launch
                }
                Log.e(TAG, "TTS speak error", e)
                abandonAudioFocus()
                releaseWakeLock()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_speech_general)
            }
        }
    }

    private fun stopAuxiliarySpeech() {
        val hadActiveAuxSpeech = auxiliarySpeechJob?.isActive == true
        auxiliarySpeechJob?.cancel()
        auxiliarySpeechJob = null
        if (hadActiveAuxSpeech) {
            ttsManager.stop()
        }
    }



    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClawAssistant::SessionWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 min max to prevent leak
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

/**
 * Assistant state
 */
enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    THINKING,
    PREPARING_SPEECH,
    SPEAKING,
    ERROR
}

/**
 * 语音面板里的文本区：内容超高时内部滚动，右侧画一条滑动条。
 *
 * Compose 没有内置滚动条，所以按 scrollState 自己算滑块位置和长度：
 * 内容不超高（maxValue == 0）时整条不显示，行为跟普通 Text 一样。
 * [followKey] 变化时，如果用户没往上翻，就自动跟到底部——流式输出不会一直停在开头。
 */
@Composable
private fun ScrollableConversationText(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    followKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var autoFollow by remember { mutableStateOf(true) }

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value to scrollState.maxValue }.collect { (value, max) ->
            autoFollow = max == 0 || value >= max - 24
        }
    }
    LaunchedEffect(followKey) {
        if (autoFollow && scrollState.maxValue > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )

        val viewportPx = constraints.maxHeight.toFloat()
        if (scrollState.maxValue > 0 && viewportPx > 0f) {
            val density = LocalDensity.current
            val contentPx = viewportPx + scrollState.maxValue
            val thumbPx = maxOf(
                viewportPx * viewportPx / contentPx,
                with(density) { 24.dp.toPx() }
            )
            val offsetPx = (viewportPx - thumbPx) * (scrollState.value.toFloat() / scrollState.maxValue)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { offsetPx.toDp() })
                    .width(4.dp)
                    .height(with(density) { thumbPx.toDp() })
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
            )
        }
    }
}

/**
 * Assistant UI (Compose)
 */
@Composable
fun AssistantUI(
    state: AssistantState,
    displayText: String,
    userQuery: String,
    partialText: String,
    errorMessage: String?,
    audioLevel: Float,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onInterrupt: () -> Unit = {},
    /** 正在听的时候点它 = 停止说话并发送（对讲模式收尾用；面板盖住了主页按钮，只能在面板里给） */
    onStopListening: () -> Unit = {}
) {
    // 窗口本身就是"底部卡片"（高度 wrap_content、贴底），这里不再铺满整屏、
    // 也不画那层 70% 黑底 —— 否则上半屏会留一块空荡荡的深色区域。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Microphone icon
            val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
            val baseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (state == AssistantState.LISTENING) 1.1f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "base_scale"
            )

            // Audio level animation
            // RMS dB usually ranges from roughly -2 (silence) to 10+ (loud speech).
            // We map this to a scale factor.
            // Shift -2 to 0: (level + 2)
            // Divide by expected max roughly 12: ((level + 2) / 12)
            // Clamp to 0..1 range just in case.
            val normalizedLevel = ((audioLevel + 2f) / 10f).coerceIn(0f, 1f)
            
            // Scale increases up to 1.5x for loud sounds
            val targetLevelScale = 1f + (normalizedLevel * 0.5f) 
            
            val animatedLevelScale by animateFloatAsState(
                targetValue = if (state == AssistantState.LISTENING) targetLevelScale else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow), // Slightly faster response
                label = "audio_level_scale"
            )

            // Combine breathing (baseScale) with voice reaction. 
            // When speaking loudly, the voice reaction should dominate.
            val finalScale = if (state == AssistantState.LISTENING) maxOf(baseScale, animatedLevelScale) else 1f

            // Morphing sphere — audio-reactive blob that
            // breathes / ripples / pulses by state. The mic icon floats above
            // the sphere so the existing tap-to-interrupt affordance still
            // works while the assistant is speaking.
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .then(
                        if (state == AssistantState.SPEAKING || state == AssistantState.PREPARING_SPEECH) {
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.interrupt_description),
                                role = Role.Button
                            ) { onInterrupt() }
                        } else if (state == AssistantState.LISTENING) {
                            // 在听的时候点球 = 说完了（和下面的按钮同义）
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.voice_stop_speaking_description),
                                role = Role.Button
                            ) { onStopListening() }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                com.openclaw.assistant.ui.voice.MorphingSphere(
                    state = state,
                    audioLevel = normalizedLevel,
                )
                Icon(
                    imageVector = if (state == AssistantState.ERROR) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status text
            Text(
                text = when (state) {
                    AssistantState.LISTENING -> stringResource(R.string.state_listening)
                    AssistantState.PROCESSING -> stringResource(R.string.state_processing)
                    AssistantState.THINKING -> stringResource(R.string.state_thinking)
                    AssistantState.PREPARING_SPEECH -> stringResource(R.string.preparing_speech)
                    AssistantState.SPEAKING -> stringResource(R.string.state_speaking)
                    AssistantState.ERROR -> stringResource(R.string.state_error)
                    else -> stringResource(R.string.state_ready)
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 文本区：回复长的时候内部滚动（封顶约半个屏，别把面板撑成全屏），右侧带一条滑动条
            val textScrollState = rememberScrollState()
            val maxTextHeight = with(LocalConfiguration.current) { (screenHeightDp * 0.42f).dp }
            ScrollableConversationText(
                scrollState = textScrollState,
                followKey = displayText + "\u0000" + partialText,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = maxTextHeight)
            ) {
                // Recognized text (partial results)
                if (partialText.isNotBlank() && state == AssistantState.LISTENING) {
                    Text(
                        text = partialText,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                // User Query (Final Result)
                if (userQuery.isNotBlank()) {
                    Text(
                        text = "$userQuery",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, // 和助手回复区分开
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Main text
                if (displayText.isNotBlank() && state != AssistantState.LISTENING) {
                    Text(
                        text = displayText,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // 「结束说话」：位置从面板出现的那一刻就占着（固定 40dp 高），只在"正在听"时才画按钮。
            // 之前是 if (LISTENING) 才加整块，ASR 准备要一两秒，按钮迟到的同时把面板撑高，
            // 看着就是"对话框先小后大变形"。
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                contentAlignment = Alignment.Center
            ) {
                if (state == AssistantState.LISTENING) {
                    Button(onClick = onStopListening) {
                        Text(stringResource(R.string.voice_stop_speaking))
                    }
                }
            }

            // Error message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    fontSize = 14.sp,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_try_again))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
