package com.timedirection.ordercapture

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NsdDiscovery(private val context: Context) {
    @Suppress("DEPRECATION")
    fun discover(timeoutMillis: Long = 900): String? {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("order-capture-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        lateinit var listener: NsdManager.DiscoveryListener
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = latch.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.contains("Order Capture", ignoreCase = true)) return
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val bracketed = if (host.contains(':')) "[$host]" else host
                        result.set("https://$bracketed:${info.port}")
                        latch.countDown()
                    }
                })
            }
        }
        return try {
            manager.discoverServices("_ordercapture._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            result.get()
        } catch (_: Exception) {
            null
        } finally {
            try { manager.stopServiceDiscovery(listener) } catch (_: Exception) { }
            if (lock.isHeld) lock.release()
        }
    }
}
