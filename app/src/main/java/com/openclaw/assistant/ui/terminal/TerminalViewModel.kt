package com.openclaw.assistant.ui.terminal

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

data class TerminalOutput(val b64: String)

data class TerminalUiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val message: String = "Hermes Dashboard terminal is not connected.",
)

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var lastCols: Int = 80
    private var lastRows: Int = 24

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState

    private val _outputFlow = MutableSharedFlow<TerminalOutput>(replay = 64, extraBufferCapacity = 64)
    val outputFlow: SharedFlow<TerminalOutput> = _outputFlow

    fun onTerminalReady(cols: Int, rows: Int) {
        lastCols = cols.coerceAtLeast(1)
        lastRows = rows.coerceAtLeast(1)
        if (socket == null) connect()
    }

    fun connect() {
        val endpoint = resolveEndpoint()
        if (endpoint == null) {
            _uiState.value = TerminalUiState(
                connected = false,
                connecting = false,
                message = getApplication<Application>()
                    .getString(com.openclaw.assistant.R.string.terminal_dashboard_off, "no endpoint"),
            )
            return
        }
        socket?.cancel()
        _uiState.value = TerminalUiState(connecting = true, message = "Connecting to Hermes terminal...")
        val request = Request.Builder().url(endpoint).build()
        socket = client.newWebSocket(request, Listener())
    }

    fun disconnect() {
        socket?.close(1000, "closed by app")
        socket = null
        _uiState.value = TerminalUiState(message = "Disconnected.")
    }

    fun sendInput(data: String) {
        socket?.send(data)
    }

    fun resize(cols: Int, rows: Int) {
        lastCols = cols.coerceAtLeast(1)
        lastRows = rows.coerceAtLeast(1)
        socket?.send("\u001b[RESIZE:$lastCols;$lastRows]")
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private fun resolveEndpoint(): String? {
        return HermesTerminalClient.resolveEndpoint(getApplication<Application>().applicationContext)
    }

    private fun emitText(text: String) {
        _outputFlow.tryEmit(TerminalOutput(Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)))
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _uiState.value = TerminalUiState(connected = true, message = "Connected.")
            resize(lastCols, lastRows)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            _outputFlow.tryEmit(TerminalOutput(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            emitText(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            val code = response?.code
            val detail = when {
                code != null -> "HTTP $code"
                t.message != null -> t.message!!
                else -> t.javaClass.simpleName
            }
            // 502/503/504 是反向代理（Tailscale serve / 网关）找不到本地 dashboard 时的典型返回；
            // 连接被拒/超时/域名解析失败同理。这些情况给一句人话，而不是甩一个错误码。
            val dashboardLikelyDown = (code != null && code in 502..504) ||
                t is java.net.ConnectException ||
                t is java.net.SocketTimeoutException ||
                t is java.net.UnknownHostException
            val app = getApplication<Application>()
            _uiState.value = TerminalUiState(
                message = if (dashboardLikelyDown)
                    app.getString(com.openclaw.assistant.R.string.terminal_dashboard_off, detail)
                else detail
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            _uiState.value = TerminalUiState(message = "Disconnected: $code ${reason.ifBlank { "" }}".trim())
        }
    }
}
