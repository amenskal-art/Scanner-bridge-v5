package com.scannerbridge.bridge.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Best-effort LAN IPv4 of this device. Prefers wlan/ap interfaces so the
     * URL we put in the QR is the one the PC can actually reach over Wi-Fi.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            // Prefer Wi-Fi style interfaces first.
            val ordered = interfaces.sortedBy { nif ->
                val n = nif.name.lowercase()
                when {
                    n.startsWith("wlan") -> 0
                    n.startsWith("ap") -> 1
                    n.startsWith("eth") -> 2
                    else -> 3
                }
            }
            for (nif in ordered) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("169.254")) continue // link-local
                        return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun buildStreamUrl(ip: String, port: Int): String = "http://$ip:$port/video"
}
