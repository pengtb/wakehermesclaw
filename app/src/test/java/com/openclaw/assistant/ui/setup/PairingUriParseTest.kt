package com.openclaw.assistant.ui.setup

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * 配对深链的解析测试：分别覆盖 query 形式、path 形式（agentvoice-pair -PathQr）以及被中转 App
 * 再编码一次的 URL。跑在 Robolectric 上，用的是真的 android.net.Uri 实现，不是猜测。
 */
@RunWith(RobolectricTestRunner::class)
// App 自己的 Application 在 onCreate 里会碰 AndroidKeyStore（Robolectric 下不可用），
// 这里换成裸 Application——本测试只关心 Uri/深链解析，不需要 App 初始化。
@Config(sdk = [34], application = android.app.Application::class)
class PairingUriParseTest {

    private val json = """{"type":"agent_voice_setup","version":1,"hermes":{""" +
        """"urls":["https://flow.taile4bd3b.ts.net:8443"],"model":"default","runs":true,"streaming":true,""" +
        """"key":"0123456789abcdef0123456789abcdef","name":"Hermes",""" +
        """"terminal":{"url":"https://flow.taile4bd3b.ts.net:10000","token":"tok-123"}}}"""

    private fun b64url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `query form parses`() {
        val uri = Uri.parse(
            "agentvoice://setup?hu=https%3A%2F%2Fexample.com%3A8443&hk=secret-key&hm=default&hr=1&hs=1" +
                "&hn=Hermes&htu=https%3A%2F%2Fexample.com%3A10000&htt=tok-abc"
        )
        val parsed = parsePairingUri(uri)
        assertNotNull("query 形式应能解析", parsed)
        assertEquals("https://example.com:8443", parsed!!.hermes?.baseUrl)
        assertEquals("secret-key", parsed.hermes?.apiKey)
        assertEquals("https://example.com:10000", parsed.hermes?.terminalUrl)
        assertEquals("tok-abc", parsed.hermes?.terminalSessionToken)
    }

    @Test
    fun `path form parses`() {
        val uri = Uri.parse("agentvoice://setup/" + b64url(json))
        val parsed = parsePairingUri(uri)
        assertNotNull("path 形式（-PathQr）应能解析", parsed)
        assertEquals("https://flow.taile4bd3b.ts.net:8443", parsed!!.hermes?.baseUrl)
        assertEquals(32, parsed.hermes?.apiKey?.length)
        assertEquals("https://flow.taile4bd3b.ts.net:10000", parsed.hermes?.terminalUrl)
        assertEquals("tok-123", parsed.hermes?.terminalSessionToken)
    }

    @Test
    fun `path form parses through parsePairingPayload too`() {
        val raw = "agentvoice://setup/" + b64url(json)
        val parsed = parsePairingPayload(raw)
        assertNotNull("扫码拿到的字符串走同一条解析链，也应认得 path 形式", parsed)
        assertEquals("https://flow.taile4bd3b.ts.net:8443", parsed!!.hermes?.baseUrl)
    }

    @Test
    fun `double encoded url is tolerated`() {
        // 中转 App 把整条 URL 再编码一次：hu 的值会是 https%3A%2F%2F…（解码一次仍是编码态）
        val uri = Uri.parse(
            "agentvoice://setup?hu=" + Uri.encode("https%3A%2F%2Fexample.com%3A8443") + "&hk=secret-key"
        )
        val parsed = parsePairingUri(uri)
        assertNotNull("再编码一次的地址应被逐级反转义后接受", parsed)
        assertEquals("https://example.com:8443", parsed!!.hermes?.baseUrl)
    }

    @Test
    fun `unrelated deep link is rejected`() {
        assertNull(parsePairingUri(Uri.parse("agentvoice://setup")))
        assertNull(parsePairingUri(Uri.parse("agentvoice://setup/not-base64!!")))
        assertNull(parsePairingUri(Uri.parse("other://setup?hu=https%3A%2F%2Fexample.com")))
    }
}
