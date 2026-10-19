package com.openclaw.assistant.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for a Hermes API Server (https://github.com/hermes/agent). Speaks both the
 * OpenAI-compatible `/v1/chat/completions` API and the Hermes-native `/v1/runs` API.
 *
 * The client is intentionally stateless apart from `currentCall` which tracks the
 * in-flight HTTP call so [stopCurrentRun] can cancel it and (for Runs API) also issue
 * a server-side stop request.
 */
class HermesApiServerClient(
    override val config: AgentBackendConfig,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : AgentClient {

    @Volatile private var currentCall: Call? = null
    @Volatile private var currentRunId: String? = null

    /**
     * Resolves the effective base URL: prefers the racer-selected winner from
     * [HermesEndpointSelection] if one exists for this backend, otherwise the
     * config's canonical baseUrl. The first call into [testConnection] / send
     * triggers a race across [AgentBackendConfig.baseUrl] +
     * [AgentBackendConfig.secondaryUrls] and caches the result.
     */
    private val baseUrl: String
        get() {
            val cached = HermesEndpointSelection.forBackend(config.id)
            if (cached != null) return cached
            return config.baseUrl ?: error("Hermes backend has no baseUrl")
        }
    private val token: String?
        get() = config.apiKeyOrToken?.takeIf { it.isNotBlank() }
    private val modelName: String
        get() = config.modelName?.takeIf { it.isNotBlank() } ?: "default"

    private fun candidateEndpoints(): List<String> = buildList {
        config.baseUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
        addAll(config.secondaryUrls.filter { it.isNotBlank() })
    }

    private suspend fun resolveBaseUrl(): String {
        HermesEndpointSelection.forBackend(config.id)?.let { return it }
        val candidates = candidateEndpoints()
        if (candidates.size > 1) {
            val outcome = HermesEndpointRacer().race(candidates, token)
            if (outcome != null && outcome.ok) {
                HermesEndpointSelection.remember(config.id, outcome.url)
                return outcome.url
            }
        }
        return config.baseUrl ?: error("Hermes backend has no baseUrl")
    }

    override suspend fun testConnection(): ConnectionTestResult {
        // Race all configured endpoints. Caches the winner so subsequent
        // streaming requests don't re-probe.
        val candidates = candidateEndpoints()
        if (candidates.size > 1) {
            val outcome = HermesEndpointRacer().race(candidates, token)
            if (outcome != null && outcome.ok) {
                HermesEndpointSelection.remember(config.id, outcome.url)
                val tag = if (outcome.url == config.baseUrl) "OK" else "OK via ${outcome.url}"
                return ConnectionTestResult(true, tag, outcome.latencyMs)
            }
            if (outcome != null) return ConnectionTestResult(false, outcome.errorMessage ?: "HTTP ${outcome.httpStatus}")
            return ConnectionTestResult(false, "No endpoints reachable")
        }
        return try {
            val started = System.currentTimeMillis()
            val req = authed(Request.Builder().url(HermesUrl.modelsUrl(baseUrl)).get()).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    ConnectionTestResult(true, "OK", System.currentTimeMillis() - started)
                } else if (resp.code == 404) {
                    val healthReq = authed(Request.Builder().url(HermesUrl.healthUrl(baseUrl)).get()).build()
                    httpClient.newCall(healthReq).execute().use { h ->
                        if (h.isSuccessful) ConnectionTestResult(true, "OK (via /health)", System.currentTimeMillis() - started)
                        else {
                            val rootHealthReq = authed(Request.Builder().url(HermesUrl.rootHealthUrl(baseUrl)).get()).build()
                            httpClient.newCall(rootHealthReq).execute().use { root ->
                                if (root.isSuccessful) {
                                    ConnectionTestResult(true, "OK (via root /health)", System.currentTimeMillis() - started)
                                } else {
                                    ConnectionTestResult(false, "HTTP ${root.code}")
                                }
                            }
                        }
                    }
                } else ConnectionTestResult(false, "HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    override fun sendMessage(
        messages: List<AgentMessage>,
        options: AgentSendOptions,
    ): Flow<AgentEvent> = when {
        config.useRunsApi -> sendViaRunsApi(messages, options)
        else -> sendViaChatCompletions(messages, options)
    }.flowOn(Dispatchers.IO)

    override suspend fun stopCurrentRun(): Boolean {
        val cancelled = currentCall?.also { it.cancel() } != null
        val runId = currentRunId
        if (runId != null) {
            try {
                val req = authed(Request.Builder().url(HermesUrl.runStopUrl(resolveBaseUrl(), runId)).post(EMPTY_JSON_BODY)).build()
                httpClient.newCall(req).execute().close()
            } catch (_: Exception) { /* best-effort */ }
        }
        currentCall = null
        currentRunId = null
        return cancelled
    }

    internal fun buildChatRequestBody(messages: List<AgentMessage>, stream: Boolean): String =
        buildJsonObject {
            put("model", modelName)
            put("stream", stream)
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
        }.toString()

    internal fun buildRunsRequestBody(messages: List<AgentMessage>): String =
        buildJsonObject {
            put("model", modelName)
            put("input", runsInput(messages))
            // Runs API 不看 messages 里的 system 角色，它读的是 instructions（服务端拿它当
            // 本轮的 ephemeral system prompt）。语音那条"回答要短"的提示必须放这儿，
            // 否则会被整条丢掉——这就是之前"加了提示但回答没变短"的原因。
            val systemPrompt = messages
                .filter { it.role.equals("system", ignoreCase = true) }
                .joinToString("\n\n") { it.content }
                .trim()
            if (systemPrompt.isNotEmpty()) {
                put("instructions", systemPrompt)
            }
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
        }.toString()

    private fun runsInput(messages: List<AgentMessage>): String =
        messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content
            ?: messages.lastOrNull()?.content
            ?: ""

    private fun sendViaChatCompletions(
        messages: List<AgentMessage>,
        options: AgentSendOptions,
    ): Flow<AgentEvent> = flow {
        emit(AgentEvent.Started())
        val selectedBaseUrl = resolveBaseUrl()
        val stream = options.stream && config.useStreaming
        val body = buildChatRequestBody(messages, stream).toRequestBody(JSON_MEDIA)
        val req = authed(
            Request.Builder().url(HermesUrl.chatCompletionsUrl(selectedBaseUrl)).post(body),
        ).also { if (stream) it.header("Accept", "text/event-stream") }.build()

        val call = httpClient.newCall(req)
        currentCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorText = resp.body?.string()?.let(::extractErrorMessage).orEmpty()
                    emit(AgentEvent.Error(listOf("HTTP ${resp.code}", errorText.ifBlank { resp.message }).filter { it.isNotBlank() }.joinToString(": ")))
                    return@flow
                }
                val source = resp.body?.source() ?: run {
                    emit(AgentEvent.Error("Empty response body"))
                    return@flow
                }
                if (!stream) {
                    val text = source.readUtf8()
                    val finalText = extractNonStreamingContent(text)
                    emit(AgentEvent.Completed(finalText))
                    return@flow
                }
                val parser = SseParser()
                val collected = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val ev = parser.feed(line) ?: continue
                    when (val mapped = mapSseEvent(ev, collected)) {
                        is AgentEvent.Completed -> { emit(mapped); return@flow }
                        null -> Unit
                        else -> emit(mapped)
                    }
                }
                emit(AgentEvent.Completed(collected.toString()))
            }
        } catch (e: IOException) {
            if (call.isCanceled()) emit(AgentEvent.Error("Cancelled")) else emit(AgentEvent.Error(e.message ?: "I/O error", e))
        } finally {
            currentCall = null
        }
    }

    private fun sendViaRunsApi(
        messages: List<AgentMessage>,
        options: AgentSendOptions,
    ): Flow<AgentEvent> = flow {
        val selectedBaseUrl = resolveBaseUrl()
        val createBody = buildRunsRequestBody(messages).toRequestBody(JSON_MEDIA)

        val createReq = authed(Request.Builder().url(HermesUrl.runsUrl(selectedBaseUrl)).post(createBody)).build()
        val createCall = httpClient.newCall(createReq)
        currentCall = createCall
        val runId = try {
            createCall.execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(AgentEvent.Error("Run create failed: HTTP ${resp.code}"))
                    return@flow
                }
                val text = resp.body?.string().orEmpty()
                val obj = json.parseToJsonElement(text).jsonObject
                obj["id"]?.jsonPrimitive?.content
                    ?: obj["run_id"]?.jsonPrimitive?.content
                    ?: run { emit(AgentEvent.Error("Run id missing")); return@flow }
            }
        } finally { currentCall = null }

        currentRunId = runId
        emit(AgentEvent.Started(runId))

        val eventsReq = authed(Request.Builder().url(HermesUrl.runEventsUrl(selectedBaseUrl, runId)).get())
            .header("Accept", "text/event-stream").build()
        val eventsCall = httpClient.newCall(eventsReq)
        currentCall = eventsCall
        try {
            eventsCall.execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(AgentEvent.Error("Run events HTTP ${resp.code}"))
                    return@flow
                }
                val source = resp.body?.source() ?: return@flow
                val parser = SseParser()
                val collected = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val ev = parser.feed(line) ?: continue
                    val mapped = mapSseEvent(ev, collected, runIdHint = runId)
                    when (mapped) {
                        is AgentEvent.Completed -> { emit(mapped); return@flow }
                        null -> Unit
                        else -> emit(mapped)
                    }
                }
                emit(AgentEvent.Completed(collected.toString(), runId))
            }
        } catch (e: IOException) {
            if (eventsCall.isCanceled()) emit(AgentEvent.Error("Cancelled"))
            else emit(AgentEvent.Error(e.message ?: "I/O error", e))
        } finally {
            currentCall = null
            currentRunId = null
        }
    }

    internal fun mapSseEvent(
        ev: SseEvent,
        collected: StringBuilder,
        runIdHint: String? = null,
    ): AgentEvent? {
        val raw = ev.data
        if (raw == "[DONE]") return AgentEvent.Completed(collected.toString(), runIdHint)
        return when (ev.event) {
            "hermes.tool.progress" -> parseToolProgress(raw)
            else -> parseHermesEvent(raw, collected, runIdHint) ?: parseTokenDelta(raw, collected)
        }
    }

    private fun parseToolProgress(payload: String): AgentEvent.ToolProgress? = runCatching {
        val obj = json.parseToJsonElement(payload).jsonObject
        val tool = obj["tool"]?.jsonPrimitive?.content ?: "unknown"
        val stage = obj["stage"]?.jsonPrimitive?.content ?: "progress"
        val detail = obj["detail"]?.jsonPrimitive?.content
        AgentEvent.ToolProgress(tool, stage, detail)
    }.getOrNull()

    private fun parseHermesEvent(payload: String, collected: StringBuilder, runIdHint: String?): AgentEvent? = runCatching {
        val obj = json.parseToJsonElement(payload).jsonObject
        val eventType = obj["event"]?.jsonPrimitive?.contentOrNullSafe()
            ?: obj["type"]?.jsonPrimitive?.contentOrNullSafe()
            ?: return@runCatching null
        when (eventType) {
            "message.delta", "assistant.delta", "response.output_text.delta" -> {
                val token = obj["delta"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: obj["text"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: obj["payload"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNullSafe()
                    ?: obj["payload"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNullSafe()
                if (token.isNullOrEmpty()) null else {
                    collected.append(token)
                    AgentEvent.TokenDelta(token)
                }
            }
            "tool.progress", "tool.started", "tool.pending", "tool.completed", "tool.failed" -> {
                val tool = obj["tool"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: obj["tool_name"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: obj["name"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: "unknown"
                val detail = obj["delta"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: obj["message"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: obj["error"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: obj["result_preview"]?.jsonPrimitive?.contentOrNullSafe()
                AgentEvent.ToolProgress(tool, eventType.substringAfter('.'), detail)
            }
            "reasoning.available", "reasoning.delta", "thinking.delta" -> null
            "run.completed", "response.completed", "done" -> {
                val finalText = obj["output"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: obj["message"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: collected.toString()
                AgentEvent.Completed(finalText, runIdHint ?: obj["run_id"]?.jsonPrimitive?.contentOrNullSafe())
            }
            "run.failed", "error" -> {
                AgentEvent.Error(
                    obj["error"]?.jsonPrimitive?.contentOrNullSafe()
                        ?: obj["message"]?.jsonPrimitive?.contentOrNullSafe()
                        ?: "Hermes run failed",
                )
            }
            else -> null
        }
    }.getOrNull()

    private fun parseTokenDelta(payload: String, collected: StringBuilder): AgentEvent? = runCatching {
        val obj = json.parseToJsonElement(payload).jsonObject
        val choices = obj["choices"] as? JsonArray ?: return@runCatching null
        val first = choices.firstOrNull()?.jsonObject ?: return@runCatching null
        val delta = first["delta"] as? JsonObject
        val token = delta?.get("content")?.jsonPrimitive?.contentOrNullSafe()
            ?: first["text"]?.jsonPrimitive?.contentOrNullSafe()
        if (token.isNullOrEmpty()) {
            val finish = first["finish_reason"]?.jsonPrimitive?.contentOrNullSafe()
            return@runCatching if (finish != null) AgentEvent.Completed(collected.toString()) else null
        }
        collected.append(token)
        AgentEvent.TokenDelta(token)
    }.getOrNull()

    private fun extractNonStreamingContent(text: String): String = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        val choices = obj["choices"] as? JsonArray
        val first = choices?.firstOrNull()?.jsonObject
        first?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: first?.get("text")?.jsonPrimitive?.content
            ?: ""
    }.getOrDefault("")

    private fun extractErrorMessage(text: String): String = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        val err = obj["error"]?.jsonObject
        err?.get("message")?.jsonPrimitive?.contentOrNullSafe()
            ?: obj["message"]?.jsonPrimitive?.contentOrNullSafe()
            ?: text
    }.getOrDefault(text).take(500)

    private fun authed(builder: Request.Builder): Request.Builder {
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_JSON_BODY = "{}".toRequestBody("application/json".toMediaType())

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // streaming
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? = try { content } catch (_: Throwable) { null }
