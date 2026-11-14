package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.domain.tools.Capability
import com.hermes.agent.domain.tools.ToolContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class DeviceToolsTest {

    @Test
    fun netGuardAllowsOnlyLocalRanges() {
        listOf("192.168.1.10", "10.0.0.1", "172.16.5.4", "127.0.0.1", "169.254.1.1", "100.100.1.1", "fd12::1").forEach {
            assertTrue(it, NetGuard.isLocal(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "1.1.1.1", "100.128.0.1", "172.32.0.1", "2001:4860::8888").forEach {
            assertFalse(it, NetGuard.isLocal(InetAddress.getByName(it)))
        }
    }

    @Test
    fun portScanRefusesInternetHosts() {
        val args = JsonObject().apply { addProperty("host", "8.8.8.8") }
        val e = runCatching { runBlocking { PortScanTool().run(args, ToolContext("c", setOf(Capability.NETWORK))) } }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("not on a local network") == true)
    }

    @Test
    fun portScanFindsOpenLoopbackPort() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val args = JsonObject().apply {
                addProperty("host", "127.0.0.1")
                addProperty("ports", "${server.localPort}")
            }
            val out = runBlocking { PortScanTool().run(args, ToolContext("c", setOf(Capability.NETWORK))) }
            assertTrue(out, out.contains("${server.localPort} open"))
            assertTrue(out, out.contains("host is up"))
        }
    }

    @Test
    fun httpToLanNeedsNetworkCapability() {
        val args = JsonObject().apply { addProperty("url", "http://192.168.1.1/") }
        val e = runCatching { runBlocking { HttpRequestTool(OkHttpClient()).run(args, ToolContext("c", setOf(Capability.INTERNET))) } }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("Local network access") == true)
    }

    @Test
    fun htmlBecomesReadableText() {
        val text = htmlToText("<html><head><style>p{}</style><script>x()</script></head><body><h1>Router</h1><p>Clients:&nbsp;3 &amp; more</p></body></html>")
        assertEquals("Router\nClients: 3 & more", text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
    }

    @Test
    fun parsesRealDuckDuckGoResults() {
        val html = javaClass.classLoader!!.getResource("ddg-results.html")!!.readText()
        val results = WebSearchTool.parseDuckDuckGo(html)
        assertEquals(10, results.size)
        assertTrue(results.all { (_, link, _) -> link.startsWith("http") && "duckduckgo.com/l/" !in link })
        assertTrue(results.all { (title, _, snippet) -> title.isNotBlank() && snippet.isNotBlank() })
    }

    @Test
    fun formatsBleValues() {
        assertEquals("\"Hermes\" (48 65 72 6d 65 73)", formatValue("Hermes".toByteArray()))
        assertEquals("87 (57)", formatValue(byteArrayOf(87)))
        assertEquals("ff 00 10", formatValue(byteArrayOf(-1, 0, 16)))
    }
}
