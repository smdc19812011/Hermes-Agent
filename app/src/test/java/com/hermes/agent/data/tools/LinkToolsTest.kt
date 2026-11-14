package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.domain.tools.Capability
import com.hermes.agent.domain.tools.ToolContext
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LinkToolsTest {
    private val lan = ToolContext("c", setOf(Capability.NETWORK))

    private fun args(vararg kv: Pair<String, Any>) = JsonObject().apply {
        kv.forEach { (k, v) -> when (v) { is Number -> addProperty(k, v); is Boolean -> addProperty(k, v); else -> addProperty(k, v.toString()) } }
    }

    @Test
    fun payloadEncodings() {
        assertArrayEquals("AT\r\n".toByteArray(), Payload.decode("AT\\r\\n", "text"))
        assertArrayEquals(byteArrayOf(0x01, 0xa0.toByte(), 0xff.toByte()), Payload.decode("01 a0:FF", "hex"))
        assertArrayEquals(byteArrayOf(0x41, 0x00, 0x42), Payload.decode("A\\x00B", "text"))
        assertArrayEquals("hi".toByteArray(), Payload.decode("aGk=", "base64"))
        assertTrue(runCatching { Payload.decode("abc", "hex") }.isFailure)
        assertTrue(Payload.describe("OK\r\n".toByteArray()).contains("text: OK"))
        assertFalse(Payload.describe(byteArrayOf(0, 1, 2, 3, -1)).contains("text:"))
    }

    @Test
    fun tcpSendGetsTheReply() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread {
                server.accept().use { c ->
                    val line = c.getInputStream().bufferedReader().readLine()
                    c.getOutputStream().write("ECHO:${line.uppercase()}\n".toByteArray())
                }
            }
            val tool = TcpSendTool()
            val a = args("host" to "127.0.0.1", "port" to server.localPort, "data" to "hello\\n", "read_seconds" to 1)
            assertTrue(tool.requiresApproval(a))
            val out = runBlocking { tool.run(a, lan) }
            assertTrue(out, out.contains("Sent 6 bytes"))
            assertTrue(out, out.contains("text: ECHO:HELLO"))
        }
    }

    @Test
    fun readOnlyCallsSkipApprovalAndInternetNeedsItsSwitch() {
        assertFalse(TcpSendTool().requiresApproval(args("host" to "127.0.0.1", "port" to 23)))
        val e = runCatching { runBlocking { TcpSendTool().run(args("host" to "8.8.8.8", "port" to 53, "data" to "x"), lan) } }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("Turn on Internet access") == true)
    }

    @Test
    fun udpSendCollectsReplies() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            thread {
                val buf = ByteArray(512)
                val p = DatagramPacket(buf, buf.size)
                server.receive(p)
                val reply = "PONG ${String(p.data, 0, p.length)}".toByteArray()
                server.send(DatagramPacket(reply, reply.size, p.address, p.port))
            }
            val out = runBlocking {
                UdpSendTool().run(args("host" to "127.0.0.1", "port" to server.localPort, "data" to "ping", "listen_seconds" to 2), lan)
            }
            assertTrue(out, out.contains("1 replies"))
            assertTrue(out, out.contains("text: PONG ping"))
        }
    }

    @Test
    fun listenReceivesUdpAndTcp() = runBlocking {
        val udpPort = DatagramSocket(0).use { it.localPort }
        val udp = async { NetworkListenTool().run(args("protocol" to "udp", "port" to udpPort, "seconds" to 3), lan) }
        delay(500)
        DatagramSocket().use { it.send(DatagramPacket("sensor=21.5".toByteArray(), 11, InetAddress.getLoopbackAddress(), udpPort)) }
        val udpOut = udp.await()
        assertTrue(udpOut, udpOut.contains("text: sensor=21.5"))

        val tcpPort = ServerSocket(0).use { it.localPort }
        val tcp = async { NetworkListenTool().run(args("protocol" to "tcp", "port" to tcpPort, "seconds" to 3), lan) }
        delay(500)
        Socket(InetAddress.getLoopbackAddress(), tcpPort).use { it.getOutputStream().write("hello phone".toByteArray()) }
        val tcpOut = tcp.await()
        assertTrue(tcpOut, tcpOut.contains("connection from 127.0.0.1"))
        assertTrue(tcpOut, tcpOut.contains("text: hello phone"))
    }

    @Test
    fun magicPacketFormat() {
        val p = WakeOnLanTool.magicPacket("AA:BB:CC:DD:EE:FF")
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == 0xff.toByte() })
        assertArrayEquals(byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte()), p.copyOfRange(96, 102))
        assertTrue(runCatching { WakeOnLanTool.magicPacket("nope") }.isFailure)
    }

    @Test
    fun broadcastAndMulticastCountAsLocal() {
        assertTrue(NetGuard.isLocal(InetAddress.getByName("255.255.255.255")))
        assertTrue(NetGuard.isLocal(InetAddress.getByName("239.255.255.250")))
    }

    @Test
    fun parsesBleUuids() {
        assertEquals("00002a19-0000-1000-8000-00805f9b34fb", parseUuid("0x2A19").toString())
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", parseUuid("6E400002-B5A3-F393-E0A9-E50E24DCCA9E").toString())
    }
}
