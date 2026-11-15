package com.example.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun getLocalIpAddress(context: Context? = null): String {
        if (context != null) {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ip = wifi?.connectionInfo?.ipAddress ?: 0
                if (ip != 0) {
                    val address = String.format(
                        "%d.%d.%d.%d",
                        ip and 0xff,
                        ip shr 8 and 0xff,
                        ip shr 16 and 0xff,
                        ip shr 24 and 0xff
                    )
                    if (address != "0.0.0.0") return address
                }
            } catch (_: Exception) {
                // Fall through to interface enumeration.
            }
        }
        return enumerateIpv4().firstOrNull()?.hostAddress ?: "127.0.0.1"
    }

    fun getWifiSsid(context: Context): String {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ssid = wifi?.connectionInfo?.ssid?.replace("\"", "")
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "Disconnected" else ssid
        } catch (_: Exception) {
            "Disconnected"
        }
    }

    /** 255.255.255.255, multicast, and a directed broadcast for every active subnet. */
    fun getBroadcastTargets(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        result.add(InetAddress.getByName("255.255.255.255"))
        result.add(InetAddress.getByName(MULTICAST_IP))
        for (address in enumerateIpv4()) {
            val raw = address.address
            val prefix = when {
                raw[0].toInt() and 0xff == 10 -> 8
                raw[0] == 172.toByte() && (raw[1].toInt() and 0xf0) == 0x10 -> 12
                raw[0] == 192.toByte() && raw[1] == 168.toByte() -> 16
                else -> 24
            }
            val mask = ((0xffffffffL shl (32 - prefix)) and 0xffffffffL).toInt()
            val ip = address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
            val broadcast = (ip or (mask.toLong() and 0xffffffffL).inv() and 0xffffffffL).toInt()
            val bytes = byteArrayOf(
                (broadcast shr 24).toByte(),
                (broadcast shr 16).toByte(),
                (broadcast shr 8).toByte(),
                broadcast.toByte()
            )
            result.add(InetAddress.getByAddress(bytes))
        }
        return result.toList()
    }

    fun getClassCSubnetTargets(localIp: String): List<String> {
        val prefix = localIp.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return emptyList()
        return (1..254).map { "$prefix.$it" }.filter { it != localIp }
    }

    private fun enumerateIpv4(): List<Inet4Address> {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                .distinctBy { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
