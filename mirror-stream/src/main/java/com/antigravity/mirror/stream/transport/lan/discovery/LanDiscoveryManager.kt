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

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate discovery", e)
            close(e)
        }

        awaitClose {
            Log.d(TAG, "Closing discovery flow")
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
