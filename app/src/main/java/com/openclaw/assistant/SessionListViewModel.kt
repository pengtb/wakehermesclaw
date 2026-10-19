package com.openclaw.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.data.local.entity.SessionEntity
import com.openclaw.assistant.data.repository.ChatRepository
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.data.SessionProductStore
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.gateway.AgentInfo
import com.openclaw.assistant.gateway.AgentListResult
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionUiModel(
    val id: String,
    val title: String,
    val createdAt: Long,
    val isGateway: Boolean,
    val product: ChatProduct,
)

enum class ChatProduct { OPENCLAW, HERMES }

class SessionListViewModel(application: Application) : AndroidViewModel(application) {

    /** AndroidViewModel 的 application 是私有的，这里留一份给 SessionProductStore 用 */
    private val appContext: android.content.Context = application.applicationContext

    private val chatRepository = ChatRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val backendRepository = BackendRepository.getInstance(application)
    private val nodeRuntime = (application as OpenClawApplication).nodeRuntime

    val isGatewayConfigured: Boolean
        get() = nodeRuntime.manualEnabled.value && nodeRuntime.manualHost.value.isNotBlank()
                
    val isHttpConfigured: Boolean
        get() = settingsRepository.isConfigured()

    val agentList: StateFlow<AgentListResult?> = nodeRuntime.agentList

    val allSessions: StateFlow<List<SessionUiModel>> = combine(
        nodeRuntime.chatSessions,
        chatRepository.allSessionsWithLatestTime,
        backendRepository.backends,
    ) { nodeEntries, localSessions, backends ->
        val gatewayModels = nodeEntries.map { entry ->
            SessionUiModel(
                id = entry.key,
                title = entry.displayName ?: "New Session",
                createdAt = entry.updatedAtMs ?: System.currentTimeMillis(),
                isGateway = true,
                product = ChatProduct.OPENCLAW,
            )
        }
        val hasHermes = backends.any { it.enabled && it.type == BackendType.HERMES_API_SERVER }
        val hasOpenClawHttp = backends.any { it.enabled && it.type == BackendType.OPENCLAW_HTTP } || isHttpConfigured
        val httpModels = localSessions.map { session ->
            SessionUiModel(
                id = session.id,
                title = session.title,
                createdAt = session.latestMessageTime ?: session.createdAt,
                isGateway = false,
                product = SessionProductStore.load(appContext, session.id)
                    ?: if (hasHermes && !hasOpenClawHttp) ChatProduct.HERMES else ChatProduct.OPENCLAW,
            )
        }
        
        (gatewayModels + httpModels).sortedByDescending { it.createdAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshSessions()
    }

    fun refreshSessions() {
        if (settingsRepository.useNodeChat) {
            nodeRuntime.refreshChatSessions(limit = 100)
        }
    }

    fun createSession(name: String, isGateway: Boolean, agentId: String? = null, targetBackendId: String? = null, onCreated: (String, Boolean) -> Unit) {
        if (isGateway) {
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val id = if (!agentId.isNullOrBlank()) "agent:$agentId:chat-$ts" else "chat-$ts"
            viewModelScope.launch {
                nodeRuntime.patchChatSession(id, name.trim())
                onCreated(id, true)
            }
        } else {
            viewModelScope.launch {
                val id = chatRepository.createSession(name.trim())
                saveProduct(id, productForBackend(targetBackendId))
                onCreated(id, false)
            }
        }
    }

    fun setUseNodeChat(useNodeChat: Boolean) {
        settingsRepository.useNodeChat = useNodeChat
    }

    fun renameSession(sessionId: String, newName: String, isGateway: Boolean) {
        if (isGateway) {
            viewModelScope.launch {
                nodeRuntime.patchChatSession(sessionId, newName.trim())
                nodeRuntime.refreshChatSessions()
            }
        } else {
            viewModelScope.launch {
                chatRepository.renameSession(sessionId, newName.trim())
            }
        }
    }

    fun deleteSession(sessionId: String, isGateway: Boolean) {
        if (isGateway) {
            viewModelScope.launch {
                nodeRuntime.deleteChatSession(sessionId)
                nodeRuntime.refreshChatSessions()
            }
        } else {
            viewModelScope.launch {
                chatRepository.deleteSession(sessionId)
                SessionProductStore.remove(appContext, sessionId)
            }
        }
    }

    private fun productForBackend(backendId: String?): ChatProduct {
        val backend = backendId?.let { id -> backendRepository.backends.value.firstOrNull { it.id == id } }
            ?: backendRepository.backends.value.firstOrNull { it.enabled && it.isPrimary }
        return when (backend?.type) {
            BackendType.HERMES_API_SERVER -> ChatProduct.HERMES
            else -> ChatProduct.OPENCLAW
        }
    }

    private fun saveProduct(sessionId: String, product: ChatProduct) {
        SessionProductStore.save(appContext, sessionId, product)
    }

    private fun loadProduct(sessionId: String): ChatProduct? =
        SessionProductStore.load(appContext, sessionId)
}
