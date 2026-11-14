package com.hermes.agent.data.tools

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Scanning tools only touch the user's own networks: private, link-local, CGNAT and loopback ranges. */
object NetGuard {
    fun isLocal(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        // Broadcast and multicast never leave the local network.
        if (address.isMulticastAddress || address.hostAddress == "255.255.255.255") return true
        return when (address) {
            is Inet4Address -> {
                val b = address.address.map { it.toInt() and 0xff }
                b[0] == 100 && b[1] in 64..127 // CGNAT, used by Tailscale and carrier networks
            }
            is Inet6Address -> (address.address[0].toInt() and 0xfe) == 0xfc // unique local fc00::/7
            else -> false
        }
    }

    fun requireLocal(host: String): InetAddress {
        val address = InetAddress.getByName(host)
        require(isLocal(address)) {
            "$host (${address.hostAddress}) is not on a local network. Port scanning is limited to LAN, VPN and loopback addresses."
        }
        return address
    }
}
