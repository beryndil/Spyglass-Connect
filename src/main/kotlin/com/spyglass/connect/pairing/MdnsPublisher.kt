package com.spyglass.connect.pairing

import com.spyglass.connect.Log
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.InetAddress

/**
 * Publish mDNS service (_spyglass._tcp) on the local network
 * so Android clients can discover the desktop app for reconnection.
 */
class MdnsPublisher {

    companion object {
        const val SERVICE_TYPE = "_spyglass._tcp.local."
        const val SERVICE_NAME = "Spyglass Connect"
        private const val TAG = "mDNS"
    }

    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    /** Start publishing the mDNS service. */
    fun start(port: Int, ip: String = LanHelper.detectLanIp()) {
        try {
            val address = InetAddress.getByName(ip)
            Log.i(TAG, "Creating JmDNS on $ip")
            jmdns = JmDNS.create(address, "spyglass-connect")

            serviceInfo = ServiceInfo.create(
                SERVICE_TYPE,
                SERVICE_NAME,
                port,
                "Spyglass Connect Desktop Companion",
            )

            jmdns?.registerService(serviceInfo)
            Log.i(TAG, "Published $SERVICE_TYPE on $ip:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS on $ip:$port", e)
        }
    }

    /** Stop publishing. Fire-and-forget — JmDNS close is slow (3-6s) so we don't block on it. */
    fun stop() {
        val j = jmdns
        val s = serviceInfo
        jmdns = null
        serviceInfo = null
        if (j != null) {
            Thread({
                try {
                    s?.let { j.unregisterService(it) }
                    j.close()
                } catch (_: Exception) {}
            }, "mdns-shutdown").apply { isDaemon = true; start() }
            Log.i(TAG, "Stopped")
        }
    }
}
