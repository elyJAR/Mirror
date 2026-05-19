package com.antigravity.mirror.stream.transport.lan.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.antigravity.mirror.stream.transport.TransportId
import com.antigravity.mirror.stream.transport.TransportTarget
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import java.net.InetAddress

private const val TAG = "MirrorApp/LanDiscovery"
private const val SERVICE_TYPE = "_mirror._tcp."

/**
 * Manages mDNS (Bonjour) discovery of LAN receivers using Android's NsdManager.
 *
 * Requirements: design.md §2.1
 */
class LanDiscoveryManager(private val context: Context) {

    /**
     * Starts a discovery session and emits the list of found [TransportTarget]s.
     */
    fun discoverReceivers(): Flow<List<TransportTarget>> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val discoveredServices = mutableMapOf<String, TransportTarget>()
        val lastSeenTimes = mutableMapOf<String, Long>()
        val udpDiscoveredDevices = mutableSetOf<String>()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS service found: ${service.serviceName}")
                
                // We need to resolve the service to get the IP and port.
                // Note: On older Android versions, NsdManager only supports one resolve at a time.
                // For a production library, we'd queue these or use a 3rd party lib like jmDNS,
                // but for v1 we'll use the platform resolver.
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.i(TAG, "Service resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")
                        
                        val target = LanTransportTarget(
                            name = serviceInfo.serviceName,
                            host = serviceInfo.host.hostAddress ?: "",
                            port = serviceInfo.port
                        )
                        
                        synchronized(discoveredServices) {
                            discoveredServices[serviceInfo.serviceName] = target
                            trySend(discoveredServices.values.toList())
                        }
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS service lost: ${service.serviceName}")
                synchronized(discoveredServices) {
                    discoveredServices.remove(service.serviceName)
                    trySend(discoveredServices.values.toList())
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                close(RuntimeException("mDNS discovery failed to start: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        // UDP cleanup job: removes UDP-discovered devices if they haven't been heard from for 5 seconds
        val udpCleanupJob = launch(Dispatchers.Default) {
            while (isActive) {
                kotlinx.coroutines.delay(2000)
                val now = System.currentTimeMillis()
                var changed = false
                synchronized(discoveredServices) {
                    val toRemove = mutableListOf<String>()
                    for (name in udpDiscoveredDevices) {
                        val lastSeen = lastSeenTimes[name] ?: 0
                        if (now - lastSeen > 5000) { // 5 seconds timeout
                            toRemove.add(name)
                        }
                    }
                    if (toRemove.isNotEmpty()) {
                        for (name in toRemove) {
                            discoveredServices.remove(name)
                            udpDiscoveredDevices.remove(name)
                            lastSeenTimes.remove(name)
                        }
                        changed = true
                        Log.d(TAG, "Removed UDP devices due to timeout: $toRemove")
                    }
                }
                if (changed) {
                    trySend(synchronized(discoveredServices) { discoveredServices.values.toList() })
                }
            }
        }

        // UDP fallback discovery listener (for mobile hotspots / local network routing bypass)
        var udpSocket: java.net.DatagramSocket? = null
        val udpJob = launch(Dispatchers.IO) {
            try {
                udpSocket = java.net.DatagramSocket(8768).apply {
                    reuseAddress = true
                    broadcast = true
                }
                val buffer = ByteArray(1024)
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                
                while (isActive) {
                    udpSocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()
                    Log.d(TAG, "Received UDP broadcast: $message")
                    
                    val nameMatch = "\"name\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(message)
                    val ipMatch = "\"ip\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(message)
                    val portMatch = "\"port\"\\s*:\\s*(\\d+)".toRegex().find(message)
                    val quitMatch = "\"quit\"\\s*:\\s*(true|false)".toRegex().find(message)
                    
                    val name = nameMatch?.groupValues?.get(1)
                    val ip = ipMatch?.groupValues?.get(1)
                    val port = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: 8765
                    val quit = quitMatch?.groupValues?.get(1)?.toBoolean() ?: false
                    
                    if (name != null) {
                        synchronized(discoveredServices) {
                            if (quit) {
                                Log.i(TAG, "Device quit via UDP broadcast: $name")
                                if (discoveredServices.remove(name) != null) {
                                    udpDiscoveredDevices.remove(name)
                                    lastSeenTimes.remove(name)
                                    trySend(discoveredServices.values.toList())
                                }
                            } else if (ip != null) {
                                val target = LanTransportTarget(
                                    name = name,
                                    host = ip,
                                    port = port
                                )
                                lastSeenTimes[name] = System.currentTimeMillis()
                                udpDiscoveredDevices.add(name)
                                
                                if (discoveredServices[name] == null || discoveredServices[name]?.host != ip) {
                                    Log.i(TAG, "Device discovered via UDP Hotspot Fallback: $name at $ip:$port")
                                    discoveredServices[name] = target
                                    trySend(discoveredServices.values.toList())
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "UDP socket closed or failed: ${e.message}")
            } finally {
                udpSocket?.close()
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate discovery", e)
            close(e)
        }

        awaitClose {
            Log.d(TAG, "Closing discovery flow")
            udpJob.cancel()
            udpCleanupJob.cancel()
            runCatching { udpSocket?.close() }
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }
}

private data class LanTransportTarget(
    override val name: String,
    override val host: String,
    override val port: Int
) : TransportTarget {
    override val transportId: TransportId = TransportId.LAN
}
