package com.snap.tiles.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

private const val TAG = "NsdDiscovery"
private const val SERVICE_TYPE = "_adb-snap._tcp"

sealed class NsdEvent {
    data class Found(val device: MacDevice) : NsdEvent()
    data class Lost(val name: String) : NsdEvent()
}

class NsdDiscoveryManager(context: Context) {

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _events = MutableSharedFlow<NsdEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<NsdEvent> = _events

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery(scope: CoroutineScope) {
        if (discoveryListener != null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.d(TAG, "Discovery started: $type")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                // Fresh listener per call — avoids FAILURE_ALREADY_ACTIVE (error 3)
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${info.serviceName}: error=$errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        Log.d(TAG, "Resolved: ${info.serviceName} @ $host:${info.port}")
                        scope.launch {
                            _events.emit(NsdEvent.Found(MacDevice(info.serviceName, host, info.port)))
                        }
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                scope.launch { _events.emit(NsdEvent.Lost(service.serviceName)) }
            }

            override fun onDiscoveryStopped(type: String) {
                Log.d(TAG, "Discovery stopped: $type")
            }

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $type error=$errorCode")
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: $type error=$errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices threw: ${e.message}")
            discoveryListener = null
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
            discoveryListener = null
        }
    }
}
