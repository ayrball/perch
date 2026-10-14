package com.perch.cam.util

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetwork {

    /** The phone's LAN IPv4 address (e.g. 192.168.1.50), or null when it is not on a network. */
    fun ipv4Address(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
