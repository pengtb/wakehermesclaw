package com.openclaw.assistant.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesApiServerClientTest {

    private fun client(model: String? = null, useRunsApi: Boolean = false) = HermesApiServerClient(
        AgentBackendConfig(
            displayName = "Hermes",
            type = BackendType.HERMES_API_SERVER,
            baseUrl = "http://host:8642",
            apiKeyOrToken = "key-123",
            modelName = model,
            useRunsApi = useRunsApi,
        ),
    )

    @Test fun `chat body uses default model when none provided`() {
        val body = client().buildChatRequestBody(listOf(AgentMessage.user("hi")), stream = true)
        assertTrue(body.contains("\"model\":\"default\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"content\":\"hi\""))
    }

    @Test fun `chat body honors custom model`() {
        val body = client(model = "hermes-large").buildChatRequestBody(listOf(AgentMessage.user("hi")), stream = false)
        assertTrue(body.contains("\"model\":\"hermes-large\""))
        assertTrue(body.contains("\"stream\":false"))
    }

    @Test fun `runs body includes Hermes input and message history`() {
        val body = client(useRunsApi = true).buildRunsRequestBody(
            listOf(
                AgentMessage.system("be concise"),
                AgentMessage.user("first"),
                AgentMessage.assistant("ok"),
                AgentMessage.user("latest"),
            ),
        )
        assertTrue(body.contains("\"model\":\"default\""))
        assertTrue(body.contains("\"input\":\"latest\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"content\":\"first\""))
    }

    @Test fun `runs body passes system prompt as instructions`() {
        val body = client(useRunsApi = true).buildRunsRequestBody(
            listOf(
                AgentMessage.system("be brief, this is spoken aloud"),
                AgentMessage.user("hello"),
            ),
        )
        // Runs API 只认 instructions，不读 messages 里的 system 角色；漏了这条语音提示就等于没加
        assertTrue(body.contains("\"instructions\":\"be brief, this is spoken aloud\""))
        assertTrue(body.contains("\"input\":\"hello\""))
    }

    @Test fun `runs body omits instructions when there is no system message`() {
        val body = client(useRunsApi = true).buildRunsRequestBody(listOf(AgentMessage.user("hello")))
        assertTrue(!body.contains("\"instructions\""))
    }

    @Test fun `tool progress event is parsed to ToolProgress`() {
        val ev = SseEvent(event = "hermes.tool.progress", data = """{"tool":"web.search","stage":"started","detail":"q=hi"}""")
        val collected = StringBuilder()
        val mapped = client().mapSseEvent(ev, collected)
        assertTrue(mapped is AgentEvent.ToolProgress)
        val tp = mapped as AgentEvent.ToolProgress
        assertEquals("web.search", tp.tool)
        assertEquals("started", tp.stage)
        assertEquals("q=hi", tp.detail)
    }

    @Test fun `token delta accumulates content`() {
        val collected = StringBuilder()
        val c = client()
        val a = c.mapSseEvent(SseEvent(null, """{"choices":[{"delta":{"content":"Hel"}}]}"""), collected)
        val b = c.mapSseEvent(SseEvent(null, """{"choices":[{"delta":{"content":"lo"}}]}"""), collected)
        assertTrue(a is AgentEvent.TokenDelta && (a as AgentEvent.TokenDelta).text == "Hel")
        assertTrue(b is AgentEvent.TokenDelta && (b as AgentEvent.TokenDelta).text == "lo")
        assertEquals("Hello", collected.toString())
    }

    @Test fun `DONE sentinel completes with collected text`() {
        val collected = StringBuilder("world")
        val mapped = client().mapSseEvent(SseEvent(null, "[DONE]"), collected)
        assertTrue(mapped is AgentEvent.Completed)
        assertEquals("world", (mapped as AgentEvent.Completed).finalText)
    }

    @Test fun `finish_reason completes stream`() {
        val collected = StringBuilder("x")
        val ev = SseEvent(null, """{"choices":[{"finish_reason":"stop","delta":{}}]}""")
        val mapped = client().mapSseEvent(ev, collected)
        assertTrue(mapped is AgentEvent.Completed)
    }

    @Test fun `runs message delta accumulates content`() {
        val collected = StringBuilder()
        val mapped = client().mapSseEvent(
            SseEvent(null, """{"event":"message.delta","run_id":"r1","delta":"Hi"}"""),
            collected,
            runIdHint = "r1",
        )
        assertTrue(mapped is AgentEvent.TokenDelta)
        assertEquals("Hi", collected.toString())
    }

    @Test fun `reasoning events are not shown as tool progress`() {
        val mapped = client().mapSseEvent(
            SseEvent(null, """{"event":"reasoning.available","text":"internal thoughts"}"""),
            StringBuilder(),
        )
        assertNull(mapped)
    }

    @Test fun `runs failed event maps to error`() {
        val mapped = client().mapSseEvent(
            SseEvent(null, """{"event":"run.failed","error":"bad provider"}"""),
            StringBuilder(),
        )
        assertTrue(mapped is AgentEvent.Error)
        assertEquals("bad provider", (mapped as AgentEvent.Error).message)
    }
}
