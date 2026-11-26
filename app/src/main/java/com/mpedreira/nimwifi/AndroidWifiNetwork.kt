package com.mpedreira.nimwifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.Closeable
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Chooses the Wi-Fi [Network] to measure and wraps it as a [MeasurementNetwork]. The choice is
 * explicit and deterministic: the only Wi-Fi network; else the Wi-Fi network that is the default
 * network; else a validated one; else the lowest network handle. Internet access is not required, so a local
 * Wi-Fi without Internet is measured even while mobile data is the default network.
 * Close the returned network when the run ends (it holds a network callback on Android 12+).
 */
class AndroidWifiNetworks(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)

    fun select(): AndroidMeasurementNetwork? {
        val cm = connectivity ?: return null
        val monitor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AssociationMonitor(cm) else null
        val candidates = wifiNetworks(cm)
        val chosen: Pair<Network, String>? = when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.single() to "only"
            else -> cm.activeNetwork?.takeIf { it in candidates }?.let { it to "default" }
                ?: candidates.filter { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }
                    .minByOrNull(Network::getNetworkHandle)?.let { it to "validated" }
                ?: candidates.minBy(Network::getNetworkHandle) to "lowest-handle"
        }
        if (chosen == null) {
            monitor?.close()
            return null
        }
        return AndroidMeasurementNetwork(chosen.first, chosen.second, cm, wifiManager, monitor)
    }

    companion object {
        @Suppress("DEPRECATION") // allNetworks: the default network may be cellular while Wi-Fi is up
        internal fun wifiNetworks(cm: ConnectivityManager): List<Network> = cm.allNetworks.filter { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@filter false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }
}

/** [MeasurementNetwork] over one Android [Network]. */
class AndroidMeasurementNetwork internal constructor(
    val network: Network,
    override val selection: String,
    private val connectivity: ConnectivityManager,
    private val wifiManager: WifiManager?,
    private val monitor: AssociationMonitor?
) : MeasurementNetwork, Closeable {
    override val id: String = network.networkHandle.toString()
    override val socketFactory: SocketFactory get() = network.socketFactory

    override fun resolve(host: String): List<InetAddress> = network.getAllByName(host).toList()

    override fun openHttp(url: URL): HttpURLConnection = network.openConnection(url) as HttpURLConnection

    override fun bind(socket: DatagramSocket) = network.bindSocket(socket)

    override fun link(): NetworkContext {
        val link = connectivity.getLinkProperties(network) ?: return NetworkContext()
        return NetworkContext(
            gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }
                ?.gateway?.hostAddress,
            dnsServers = link.dnsServers,
            interfaceName = link.interfaceName
        )
    }

    override fun isAvailable(): Boolean = network in AndroidWifiNetworks.wifiNetworks(connectivity)

    override fun association(): Association {
        if (monitor != null) {
            val info = monitor.wifiInfo(network) ?: return Association.UNKNOWN
            return info.toAssociation(Association.SOURCE_NETWORK_CALLBACK)
        }
        // Android 11: WifiManager describes "the" Wi-Fi; only trust it when this is the only one.
        if (AndroidWifiNetworks.wifiNetworks(connectivity) != listOf(network)) return Association.UNKNOWN
        @Suppress("DEPRECATION")
        val info = wifiManager?.connectionInfo ?: return Association.UNKNOWN
        return info.toAssociation(Association.SOURCE_WIFI_MANAGER)
    }

    override fun close() {
        monitor?.close()
    }

    private fun WifiInfo.toAssociation(source: String): Association {
        @Suppress("DEPRECATION")
        val ssidValue = ssid?.removeSurrounding("\"")?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }
        val bssidValue = bssid?.lowercase(Locale.ROOT)?.takeUnless { it == HIDDEN_BSSID }
        if (ssidValue == null && bssidValue == null) return Association.UNKNOWN
        return Association(
            known = true,
            source = source,
            ssid = ssidValue,
            bssid = bssidValue,
            rssi = rssi.takeIf { it in -126..0 },
            linkSpeedMbps = linkSpeed.takeIf { it > 0 },
            rxLinkSpeedMbps = rxLinkSpeedMbps.takeIf { it > 0 },
            frequencyMhz = frequency.takeIf { it > 0 },
            wifiStandard = WifiChannels.standardName(wifiStandard)
        )
    }

    private companion object {
        const val HIDDEN_BSSID = "02:00:00:00:00:00"
    }
}

/**
 * Android 12+: [ConnectivityManager.getNetworkCapabilities] redacts SSID/BSSID; a callback
 * registered with [ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO] receives the
 * per-network [WifiInfo] (location permission still required).
 */
internal class AssociationMonitor(private val connectivity: ConnectivityManager) : Closeable {
    private val infos = ConcurrentHashMap<Network, WifiInfo>()
    private val firstUpdate = CountDownLatch(1)
    private val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                (capabilities.transportInfo as? WifiInfo)?.let { infos[network] = it }
                firstUpdate.countDown()
            }

            override fun onLost(network: Network) {
                infos.remove(network)
            }
        }
    } else {
        null
    }

    init {
        callback?.let {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), it
            )
        }
    }

    /** Waits briefly for the first callback after registration. */
    fun wifiInfo(network: Network): WifiInfo? {
        firstUpdate.await(FIRST_UPDATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        return infos[network]
    }

    override fun close() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
    }

    private companion object {
        const val FIRST_UPDATE_TIMEOUT_MILLIS = 1_000L
    }
}
