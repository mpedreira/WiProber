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

    /** Chooses with [NetworkSelectionPolicy]; [expectedSsid] prefers the eligible network that reports it. */
    fun select(expectedSsid: String? = null): AndroidMeasurementNetwork? {
        val cm = connectivity ?: return null
        val monitor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AssociationMonitor(cm) else null
        val networks = wifiNetworks(cm)
        val candidates = networks.map { network -> candidate(cm, network, networks.size, monitor) }
        val chosen = NetworkSelectionPolicy.choose(candidates, expectedSsid)
        if (chosen == null) {
            monitor?.close()
            return null
        }
        val network = networks.first { it.networkHandle == chosen.first.id }
        return AndroidMeasurementNetwork(network, chosen.second, cm, wifiManager, monitor, candidates.map { it.ssid })
    }

    /** rc.3 · 2: whether any eligible Wi-Fi network exists, without opening or binding anything. */
    fun hasWifi(): Boolean = connectivity?.let { wifiNetworks(it).isNotEmpty() } ?: false

    /**
     * rc.2 · 3: the Wi-Fi network with handle [id] (the one approved in the preflight), or null
     * when it no longer exists. Never falls back to another network.
     */
    fun selectById(id: String): AndroidMeasurementNetwork? {
        val cm = connectivity ?: return null
        val networks = wifiNetworks(cm)
        val network = networks.firstOrNull { it.networkHandle.toString() == id } ?: return null
        val monitor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AssociationMonitor(cm) else null
        val candidates = networks.map { candidate(cm, it, networks.size, monitor) }
        return AndroidMeasurementNetwork(network, APPROVED, cm, wifiManager, monitor, candidates.map { it.ssid })
    }

    private fun candidate(cm: ConnectivityManager, network: Network, count: Int, monitor: AssociationMonitor?): NetworkCandidate {
        @Suppress("DEPRECATION")
        val info = monitor?.wifiInfo(network) ?: if (count == 1) wifiManager?.connectionInfo else null
        @Suppress("DEPRECATION")
        val ssid = info?.ssid?.removeSurrounding("\"")?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }
        return NetworkCandidate(
            id = network.networkHandle,
            isDefault = cm.activeNetwork == network,
            validated = cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            ssid = ssid
        )
    }

    companion object {
        /** Selection reason of a network bound by its approved handle. */
        const val APPROVED = "approved"

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
    private val monitor: AssociationMonitor?,
    /** SSIDs of every eligible Wi-Fi network when this one was chosen. */
    val eligibleSsids: List<String?> = emptyList()
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
            interfaceName = link.interfaceName,
            localAddresses = link.linkAddresses.map { it.address }.filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
        )
    }

    override fun isAvailable(): Boolean = network in AndroidWifiNetworks.wifiNetworks(connectivity)

    /** This network's own proxy settings (LinkProperties), the ones openHttp() applies. */
    override fun proxy(): ProxyConfig = runCatching {
        val proxy = connectivity.getLinkProperties(network)?.httpProxy ?: return@runCatching ProxyConfig.NONE
        when {
            proxy.pacFileUrl != null && proxy.pacFileUrl != android.net.Uri.EMPTY -> ProxyConfig(ProxyKind.PAC, pacUrl = proxy.pacFileUrl.toString())
            !proxy.host.isNullOrEmpty() -> ProxyConfig(ProxyKind.MANUAL, proxy.host, proxy.port, proxy.exclusionList.orEmpty().toList())
            else -> ProxyConfig.NONE
        }
    }.getOrDefault(ProxyConfig.UNKNOWN)

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
