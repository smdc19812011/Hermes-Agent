package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.domain.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.*

/** LAN targets are always allowed; internet targets also need Internet access switched on. */
internal fun resolveTarget(host: String, context: ToolContext): InetAddress {
    val address = InetAddress.getByName(host)
    if (!NetGuard.isLocal(address) && Capability.INTERNET !in context.enabled) {
        throw SecurityException("$host is on the internet, not the local network. Turn on Internet access in Settings to send there.")
    }
    return address
}

/** Reads until [seconds] of silence, end of stream, or [maxBytes]. */
private fun Socket.readFor(seconds: Int, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    soTimeout = (seconds * 1000).coerceAtLeast(100)
    val buf = ByteArray(4096)
    val input = getInputStream()
    try {
        while (out.size() < maxBytes) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
    } catch (_: SocketTimeoutException) {
        // Quiet for the window: done.
    }
    return out.toByteArray()
}

class TcpSendTool @javax.inject.Inject constructor() : AgentTool {
    override val name = "tcp_send"
    override val capability = Capability.NETWORK
    override val description = "Open a TCP connection (like netcat), optionally send data, and return what the other side sends back. " +
        "Use for raw protocols: device consoles, telnet-style services, Redis, SMTP banners, custom IoT devices."
    override val parameters = schema(
        "host" to prop("string", "IP address or hostname."),
        "port" to prop("integer", "TCP port."),
        "data" to Payload.dataProp,
        "encoding" to Payload.encodingProp,
        "read_seconds" to prop("integer", "Stop reading after this many seconds of silence (0-30, default 3)."),
        required = listOf("host", "port")
    )

    override fun requiresApproval(args: JsonObject) = Payload.from(args) != null
    override fun label(args: JsonObject) = (Payload.from(args)?.let { "Send ${it.size} bytes to" } ?: "Connect to") +
        " ${args.string("host")}:${args.int("port")} (TCP)"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val host = args.string("host") ?: throw IllegalArgumentException("host is required")
        val port = args.int("port")?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("port must be 1-65535")
        val address = resolveTarget(host, context)
        val payload = Payload.from(args)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), 5_000)
            payload?.let { socket.getOutputStream().apply { write(it); flush() } }
            val received = socket.readFor(args.int("read_seconds")?.coerceIn(0, 30) ?: 3, 256 * 1024)
            buildString {
                append("Connected to ${address.hostAddress}:$port.")
                payload?.let { append(" Sent ${it.size} bytes.") }
                append("\nReceived ").append(Payload.describe(received))
            }
        }
    }
}

class UdpSendTool @javax.inject.Inject constructor() : AgentTool {
    override val name = "udp_send"
    override val capability = Capability.NETWORK
    override val description = "Send a UDP datagram (unicast, subnet broadcast or 255.255.255.255) and collect replies for a few seconds. " +
        "Use for discovery protocols (SSDP to 239.255.255.250:1900, device beacons), DNS, NTP, or UDP-controlled devices."
    override val parameters = schema(
        "host" to prop("string", "Destination IP, hostname, or broadcast address."),
        "port" to prop("integer", "UDP port."),
        "data" to Payload.dataProp,
        "encoding" to Payload.encodingProp,
        "listen_seconds" to prop("integer", "How long to collect replies (0-30, default 3)."),
        required = listOf("host", "port", "data")
    )

    override val needsApproval = true
    override fun label(args: JsonObject) = "Send ${Payload.from(args)?.size ?: 0} bytes to ${args.string("host")}:${args.int("port")} (UDP)"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val host = args.string("host") ?: throw IllegalArgumentException("host is required")
        val port = args.int("port")?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("port must be 1-65535")
        val address = resolveTarget(host, context)
        val payload = Payload.from(args) ?: throw IllegalArgumentException("data is required for UDP")
        val listen = args.int("listen_seconds")?.coerceIn(0, 30) ?: 3
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(payload, payload.size, address, port))
            val replies = mutableListOf<String>()
            val deadline = System.currentTimeMillis() + listen * 1000L
            val buf = ByteArray(65_507)
            while (System.currentTimeMillis() < deadline && replies.size < 50) {
                socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                replies += "from ${packet.address.hostAddress}:${packet.port}: " + Payload.describe(packet.data.copyOf(packet.length), 2048)
            }
            "Sent ${payload.size} bytes to ${address.hostAddress}:$port. ${replies.size} replies in $listen s." +
                replies.joinToString("") { "\n- $it" }
        }
    }
}

class WakeOnLanTool @javax.inject.Inject constructor() : AgentTool {
    override val name = "wake_on_lan"
    override val capability = Capability.NETWORK
    override val needsApproval = true
    override val description = "Wake a computer on the local network by sending a Wake-on-LAN magic packet to its MAC address."
    override val parameters = schema(
        "mac" to prop("string", "MAC address, e.g. AA:BB:CC:DD:EE:FF."),
        "broadcast" to prop("string", "Broadcast address, default 255.255.255.255."),
        required = listOf("mac")
    )

    override fun label(args: JsonObject) = "Wake ${args.string("mac")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val packet = magicPacket(args.string("mac") ?: throw IllegalArgumentException("mac is required"))
        val target = InetAddress.getByName(args.string("broadcast") ?: "255.255.255.255")
        require(NetGuard.isLocal(target)) { "Wake-on-LAN only goes to local broadcast addresses." }
        DatagramSocket().use { s ->
            s.broadcast = true
            listOf(9, 7).forEach { port -> s.send(DatagramPacket(packet, packet.size, target, port)) }
        }
        "Sent a magic packet for ${args.string("mac")} to ${target.hostAddress} on ports 9 and 7."
    }

    companion object {
        fun magicPacket(mac: String): ByteArray {
            val bytes = mac.split(':', '-', '.').joinToString("").let { hex ->
                require(hex.length == 12 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "$mac is not a MAC address." }
                ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
            return ByteArray(6) { 0xff.toByte() } + ByteArray(16 * 6) { bytes[it % 6] }
        }
    }
}

class NetworkListenTool @javax.inject.Inject constructor() : AgentTool {
    override val name = "network_listen"
    override val capability = Capability.NETWORK
    override val description = "Listen on a TCP or UDP port of this phone for a while and return everything received, with each sender's address. " +
        "Use to receive data a device or computer sends to the phone."
    override val parameters = schema(
        "protocol" to prop("string", "tcp or udp.", listOf("tcp", "udp")),
        "port" to prop("integer", "Port to listen on (1024-65535)."),
        "seconds" to prop("integer", "How long to listen (1-60, default 15)."),
        required = listOf("protocol", "port")
    )

    override fun label(args: JsonObject) = "Listening on ${args.string("protocol")?.uppercase()} port ${args.int("port")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val port = args.int("port")?.takeIf { it in 1024..65535 } ?: throw IllegalArgumentException("port must be 1024-65535")
        val seconds = args.int("seconds")?.coerceIn(1, 60) ?: 15
        val deadline = System.currentTimeMillis() + seconds * 1000L
        val ips = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }.filter { it is Inet4Address && !it.isLoopbackAddress }.mapNotNull { it.hostAddress }
        val got = mutableListOf<String>()
        if (args.string("protocol")?.lowercase() == "udp") {
            DatagramSocket(port).use { s ->
                val buf = ByteArray(65_507)
                while (System.currentTimeMillis() < deadline && got.size < 100) {
                    s.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { break }
                    got += "from ${p.address.hostAddress}:${p.port}: " + Payload.describe(p.data.copyOf(p.length), 2048)
                }
            }
        } else {
            ServerSocket(port).use { server ->
                while (System.currentTimeMillis() < deadline && got.size < 20) {
                    server.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                    val client = try { server.accept() } catch (_: SocketTimeoutException) { break }
                    client.use { c ->
                        val left = ((deadline - System.currentTimeMillis()) / 1000).toInt().coerceIn(1, 5)
                        got += "connection from ${c.inetAddress.hostAddress}:${c.port}: " + Payload.describe(c.readFor(left, 256 * 1024), 4096)
                    }
                }
            }
        }
        "Listened on ${args.string("protocol")?.uppercase()} port $port for up to $seconds s (this phone: ${ips.joinToString()}). " +
            "Received ${got.size} ${if (got.size == 1) "message" else "messages"}." + got.joinToString("") { "\n- $it" }
    }
}
