package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * mDNS/NSD side channel used alongside UDP broadcast. The original advertises
 * and browses "_walkietalkie._tcp.".
 */
class NsdHelper(
    context: Context,
    private val onPeerDiscovered: (name: String, host: String, port: Int) -> Unit
) {
    private val tag = "NsdHelper"
    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun registerService(displayName: String) {
        unregisterService()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "WalkieTalkie-$displayName"
            serviceType = SERVICE_TYPE
            port = TCP_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(tag, "Service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "Service registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(tag, "Service unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "Service unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(tag, "Failed to register NSD service", e)
        }
    }

    fun unregisterService() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (_: Exception) {
        }
        registrationListener = null
    }

    fun startDiscovery() {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(tag, "Service discovery started: $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                Log.i(tag, "Service found: ${info.serviceName}")
                try {
                    nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(tag, "Resolve failed for ${info.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            Log.i(tag, "Resolve succeeded: $host:${info.port}")
                            onPeerDiscovered(info.serviceName, host, info.port)
                        }
                    })
                } catch (e: Exception) {
                    Log.w(tag, "Discovery failed", e)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Log.i(tag, "Service lost: ${info.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(tag, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "Failed to start service discovery: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "Stop discovery failed: $errorCode")
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start service discovery", e)
        }
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        }
        discoveryListener = null
    }

    fun shutdown() {
        stopDiscovery()
        unregisterService()
    }

    companion object {
        const val SERVICE_TYPE = "_walkietalkie._tcp."
    }
}
