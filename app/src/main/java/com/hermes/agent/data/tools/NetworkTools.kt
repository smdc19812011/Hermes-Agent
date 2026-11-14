package com.hermes.agent.data.tools

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.google.gson.JsonObject
import com.hermes.agent.domain.tools.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private val COMMON_PORTS = mapOf(
    21 to "ftp", 22 to "ssh", 23 to "telnet", 53 to "dns", 80 to "http", 139 to "netbios", 443 to "https",
    445 to "smb", 548 to "afp", 554 to "rtsp", 631 to "ipp", 1883 to "mqtt", 3389 to "rdp", 5000 to "upnp/http", 5555 to "adb",
    5900 to "vnc", 8008 to "chromecast", 8080 to "http-alt", 8443 to "https-alt", 9100 to "printer", 32400 to "plex",
    62078 to "apple-device"
)

private val MDNS_TYPES = listOf(
    "_http._tcp", "_ipp._tcp", "_printer._tcp", "_googlecast._tcp", "_airplay._tcp", "_raop._tcp",
    "_spotify-connect._tcp", "_smb._tcp", "_ssh._tcp", "_hap._tcp", "_companion-link._tcp", "_workstation._tcp",
    // Android debugging: classic adb over TCP and Android 11+ wireless debugging (random port, so only mDNS finds it).
    "_adb._tcp", "_adb-tls-connect._tcp", "_adb-tls-pairing._tcp"
)

private fun alive(e: Exception) = e is ConnectException // refused = a host answered

/** Connects to [port]; true = open, false = closed but host up, null = no answer. */
private fun probe(address: InetAddress, port: Int, timeoutMs: Int): Boolean? = try {
    Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) }
    true
} catch (e: Exception) {
    if (alive(e)) false else null
}

private fun parsePorts(spec: String?, default: List<Int>): List<Int> {
    if (spec.isNullOrBlank()) return default
    val ports = spec.split(',').flatMap { part ->
        val p = part.trim()
        if ('-' in p) {
            val (a, b) = p.split('-').map { it.trim().toInt() }
            (a..b).toList()
        } else listOf(p.toInt())
    }.filter { it in 1..65535 }.distinct()
    require(ports.size <= 1024) { "At most 1024 ports per scan." }
    return ports
}

@Singleton
class NetworkInfoTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "network_info"
    override val capability = Capability.NETWORK
    override val description = "Describe this phone's network connections: active network type, IP addresses, subnet, gateway, DNS servers, " +
        "Wi-Fi details, and every network interface including USB tethering, Ethernet adapters and VPNs."
    override val parameters = schema()

    @SuppressLint("MissingPermission")
    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val cm = this@NetworkInfoTool.context.getSystemService(ConnectivityManager::class.java)
        val out = StringBuilder()
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val link = network?.let { cm.getLinkProperties(it) }
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("Bluetooth")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && caps?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true) add("USB")
        }
        out.appendLine("Active network: ${transports.joinToString().ifEmpty { "none" }}")
        link?.let { l ->
            out.appendLine("Interface: ${l.interfaceName}")
            l.linkAddresses.forEach { out.appendLine("Address: ${it.address.hostAddress}/${it.prefixLength}") }
            l.routes.filter { it.isDefaultRoute && it.gateway != null }.forEach { out.appendLine("Gateway: ${it.gateway?.hostAddress}") }
            if (l.dnsServers.isNotEmpty()) out.appendLine("DNS: ${l.dnsServers.joinToString { it.hostAddress.orEmpty() }}")
        }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            @Suppress("DEPRECATION")
            val info = this@NetworkInfoTool.context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
            val ssid = info.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } ?: "(hidden: grant location permission to see the Wi-Fi name)"
            out.appendLine("Wi-Fi: $ssid, signal ${info.rssi} dBm, ${info.frequency} MHz, ${info.linkSpeed} Mbps")
        }
        out.appendLine("All interfaces:")
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }.forEach { ni ->
            val addrs = ni.interfaceAddresses.joinToString { "${it.address.hostAddress}/${it.networkPrefixLength}" }
            out.appendLine("  ${ni.name}${if (ni.isLoopback) " (loopback)" else ""}: ${addrs.ifEmpty { "no address" }}")
        }
        out.toString()
    }
}

@Singleton
class LanScanTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "scan_lan"
    override val capability = Capability.NETWORK
    override val description = "Find devices on the local network this phone is connected to. Probes every address in the subnet on common ports " +
        "and discovers mDNS/Bonjour services (printers, Chromecasts, AirPlay, smart home, Android ADB/wireless debugging). Returns IP, hostname, open ports and service names. " +
        "Takes about 10-20 seconds."
    override val parameters = schema(
        "subnet" to prop("string", "Optional CIDR to scan, e.g. 192.168.1.0/24. Defaults to the current Wi-Fi or Ethernet subnet. Max /22.")
    )

    override fun label(args: JsonObject) = "Scanning ${args.string("subnet") ?: "the local network"}"

    override suspend fun run(args: JsonObject, context: ToolContext): String = coroutineScope {
        val (base, prefix) = args.string("subnet")?.let(::parseCidr) ?: currentSubnet()
            ?: throw IllegalStateException("Not connected to a Wi-Fi or Ethernet network.")
        require(prefix in 22..30) { "Subnet must be between /22 and /30 so the scan finishes in reasonable time." }
        val own = ownAddresses()
        val hosts = hostsIn(base, prefix)
        val mdns = async { discoverMdns(4_000) }

        // Bounded per socket, not per host: ~200 connects in flight keeps a /24 near 10 s without exhausting file descriptors.
        val sockets = Semaphore(200)
        val found = hosts.map { addr ->
            async(Dispatchers.IO) {
                val results = COMMON_PORTS.keys.map { port ->
                    async { sockets.withPermit { port to probe(addr, port, 350) } }
                }.awaitAll()
                val open = results.filter { it.second == true }.map { it.first }
                val up = results.any { it.second != null } ||
                    sockets.withPermit { runCatching { addr.isReachable(300) }.getOrDefault(false) }
                if (up) addr to open else null
            }
        }.awaitAll().filterNotNull()

        val services = mdns.await()
        val all = (found.map { it.first.hostAddress!! } + services.keys).distinct()
            .sortedBy { ip -> ip.split('.').map { it.toIntOrNull() ?: 0 }.fold(0L) { acc, n -> acc * 256 + n } }

        val names = all.map { ip -> async(Dispatchers.IO) { ip to withTimeoutOrNull(1_500) { reverseName(ip) } } }.awaitAll().toMap()
        val ports = found.associate { it.first.hostAddress!! to it.second }

        buildString {
            appendLine("Scanned ${hosts.size} addresses in ${intToIp(base)}/$prefix. Found ${all.size} devices:")
            all.forEach { ip ->
                val tags = buildList {
                    if (ip in own) add("this phone")
                    names[ip]?.let { add(it) }
                }
                append("- $ip")
                if (tags.isNotEmpty()) append(" (${tags.joinToString()})")
                ports[ip]?.takeIf { it.isNotEmpty() }?.let { p -> append(" open: ${p.joinToString { "$it/${COMMON_PORTS[it]}" }}") }
                services[ip]?.let { append(" services: ${it.joinToString()}") }
                appendLine()
            }
            appendLine("Devices that block every probed port and don't advertise mDNS (often phones on battery saver) can be missed.")
        }
    }

    private fun currentSubnet(): Pair<Int, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val link = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        val la = link.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        // Wide subnets (e.g. /16) are narrowed to the phone's own /24.
        val prefix = la.prefixLength.coerceAtLeast(24)
        return ipToInt(la.address as Inet4Address) to prefix
    }

    private fun ownAddresses(): Set<String> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }.toSet()

    private fun parseCidr(cidr: String): Pair<Int, Int> {
        val (ip, bits) = cidr.split('/').let { it[0] to (it.getOrNull(1)?.toInt() ?: 24) }
        val addr = InetAddress.getByName(ip)
        require(addr is Inet4Address && NetGuard.isLocal(addr)) { "$cidr is not a private IPv4 subnet." }
        return ipToInt(addr) to bits
    }

    private fun hostsIn(base: Int, prefix: Int): List<InetAddress> {
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = base and mask
        val count = 1 shl (32 - prefix)
        return (1 until count - 1).map { InetAddress.getByName(intToIp(network + it)) }
    }

    private fun reverseName(ip: String): String? =
        InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }

    /** Browses common mDNS service types and resolves each instance to an IP. */
    private suspend fun discoverMdns(windowMs: Long): Map<String, List<String>> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyMap()
        val found = ConcurrentHashMap<String, NsdServiceInfo>()
        val listeners = MDNS_TYPES.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) { found["${info.serviceName}|${info.serviceType}"] = info }
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }.also { runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) } }
        }
        delay(windowMs)
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }

        val byIp = mutableMapOf<String, MutableList<String>>()
        // Resolve one at a time: older Android rejects concurrent resolves.
        for (info in found.values.take(30)) {
            val resolved = withTimeoutOrNull(1_500) { resolve(nsd, info) } ?: continue
            @Suppress("DEPRECATION")
            val ip = resolved.host?.hostAddress ?: continue
            val kind = info.serviceType.trim('.').substringBefore("._tcp").trimStart('_')
            byIp.getOrPut(ip) { mutableListOf() } += "${resolved.serviceName} [$kind, port ${resolved.port}]"
        }
        return byIp
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (cont.isActive) cont.resume(serviceInfo) }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
}

@Singleton
class PortScanTool @Inject constructor() : AgentTool {
    override val name = "port_scan"
    override val capability = Capability.NETWORK
    override val description = "Check which TCP ports are open on one device on the local network (LAN, VPN or loopback addresses only)."
    override val parameters = schema(
        "host" to prop("string", "IP address or hostname on the local network."),
        "ports" to prop("string", "Ports to check, e.g. \"22,80,443\" or \"1-1024\". Defaults to common service ports. Max 1024."),
        required = listOf("host")
    )

    override fun label(args: JsonObject) = "Checking ports on ${args.string("host")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String = coroutineScope {
        val host = args.string("host") ?: throw IllegalArgumentException("host is required")
        val address = withContext(Dispatchers.IO) { NetGuard.requireLocal(host) }
        val ports = parsePorts(args.string("ports"), COMMON_PORTS.keys.toList())
        val gate = Semaphore(128)
        val results = ports.map { port -> async(Dispatchers.IO) { gate.withPermit { port to probe(address, port, 600) } } }.awaitAll()
        val open = results.filter { it.second == true }.map { it.first }
        val up = results.any { it.second != null }
        buildString {
            appendLine("${address.hostAddress}: checked ${ports.size} ports, host ${if (up) "is up" else "did not answer"}.")
            if (open.isEmpty()) appendLine("No open ports found.")
            else open.forEach { appendLine("- $it open${COMMON_PORTS[it]?.let { s -> " ($s)" }.orEmpty()}") }
        }
    }
}

@Singleton
class DnsLookupTool @Inject constructor() : AgentTool {
    override val name = "dns_lookup"
    override val capability = Capability.NETWORK
    override val description = "Resolve a hostname to its IP addresses, or an IP address to its hostname."
    override val parameters = schema("name" to prop("string", "Hostname or IP address."), required = listOf("name"))

    override fun label(args: JsonObject) = "Looking up ${args.string("name")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val name = args.string("name") ?: throw IllegalArgumentException("name is required")
        val addresses = InetAddress.getAllByName(name)
        addresses.joinToString("\n") { a ->
            val reverse = runCatching { a.canonicalHostName }.getOrNull()?.takeIf { it != a.hostAddress }
            "${a.hostAddress}${reverse?.let { " ($it)" }.orEmpty()}${if (NetGuard.isLocal(a)) " [local]" else ""}"
        }
    }
}

@Singleton
class TcpPingTool @Inject constructor() : AgentTool {
    override val name = "tcp_ping"
    override val capability = Capability.NETWORK
    override val description = "Measure whether a host is reachable on one TCP port and how long connecting takes (like ping, which Android apps can't send)."
    override val parameters = schema(
        "host" to prop("string", "Hostname or IP address."),
        "port" to prop("integer", "TCP port, default 443."),
        required = listOf("host")
    )

    override fun label(args: JsonObject) = "Pinging ${args.string("host")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val host = args.string("host") ?: throw IllegalArgumentException("host is required")
        val port = args.int("port") ?: 443
        val address = InetAddress.getByName(host)
        val times = (1..4).map {
            val start = System.nanoTime()
            val r = probe(address, port, 2_000)
            if (r == null) null else (System.nanoTime() - start) / 1_000_000
        }
        val ok = times.filterNotNull()
        "${address.hostAddress}:$port, ${ok.size}/4 answered" +
            if (ok.isNotEmpty()) ", min ${ok.min()} ms, avg ${ok.average().toInt()} ms, max ${ok.max()} ms" else ""
    }
}

internal fun ipToInt(a: Inet4Address) = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
internal fun intToIp(v: Int) = "${v ushr 24 and 0xff}.${v ushr 16 and 0xff}.${v ushr 8 and 0xff}.${v and 0xff}"
