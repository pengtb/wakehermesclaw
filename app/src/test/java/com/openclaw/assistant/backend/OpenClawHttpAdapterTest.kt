package com.openclaw.assistant.backend

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenClaw HTTP 模式（`BackendType.OPENCLAW_HTTP` → `OpenClawHttpAdapter`）的契约测试。
 *
 * 这条链路的行为和它和 Hermes 后端的差别，都被这几个断言钉住：
 * 1. 裸 `baseUrl`（`http://host:port`）会被补成 `/v1/chat/completions`；
 * 2. system 消息作为 messages[0] 发出去，不再被丢掉；
 * 3. 回复按 OpenAI 的 `choices[0].message.content` 解析。
 */
class OpenClawHttpAdapterTest {

    private val server = MockWebServer()

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun adapter(baseUrl: String, token: String? = "tok") = OpenClawHttpAdapter(
        AgentBackendConfig(
            displayName = "OpenClaw HTTP",
            type = BackendType.OPENCLAW_HTTP,
            baseUrl = baseUrl,
            apiKeyOrToken = token,
        ),
    )

    private fun startWithBody(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
        server.start()
    }

    @Test fun `posts to baseUrl as-is and parses openai style reply`() = runBlocking {
        startWithBody("""{"choices":[{"message":{"role":"assistant","content":"在的"}}]}""")
        val url = server.url("/v1/chat/completions").toString()

        val events = adapter(url).sendMessage(
            listOf(AgentMessage.user("你好")),
            AgentSendOptions(sessionId = "s1"),
        ).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().singleOrNull()
        assertEquals("在的", completed?.finalText)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"content\":\"你好\""))
    }

    @Test fun `bare baseUrl is completed with v1 chat completions`() = runBlocking {
        startWithBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        val root = server.url("/").toString()

        // 设置里填 http://host:port 这种裸地址（旧配置迁移过来的就是这样）也要能打通
        adapter(root).sendMessage(
            listOf(AgentMessage.user("hi")),
            AgentSendOptions(sessionId = "s1"),
        ).toList()

        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test fun `system message is sent as the first message`() = runBlocking {
        startWithBody("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        val url = server.url("/v1/chat/completions").toString()

        adapter(url).sendMessage(
            listOf(
                AgentMessage.system("回答不超过 60 字"),
                AgentMessage.user("介绍一下你自己"),
            ),
            AgentSendOptions(sessionId = "s1"),
        ).toList()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("介绍一下你自己"))
        assertTrue("system 提示词要发出去", body.contains("60 字"))
        assertTrue(body.indexOf("60 字") < body.indexOf("介绍一下你自己"))
    }

    @Test fun `connection test posts to the same endpoint`() = runBlocking {
        startWithBody("""{"choices":[{"message":{"content":"ok"}}]}""")
        val url = server.url("/v1/chat/completions").toString()

        val result = adapter(url).testConnection()

        assertTrue(result.ok)
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }
}
