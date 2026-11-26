package com.example.wiprober

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import javax.net.SocketFactory
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android boundary of the per-point throughput tests. Every socket and HTTP connection is bound
 * to the Wi-Fi network, so a weak Wi-Fi never silently falls back to mobile data; without Wi-Fi
 * nothing is sent and each target reports [ThroughputRunner.NOT_ON_WIFI].
 */
class ThroughputProbe(context: Context, private val clock: MillisClock = SystemMillisClock) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val mainThread = Handler(Looper.getMainLooper())

    /**
     * [scanResults] is the scan just taken at this point: it supplies the connected AP's channel
     * width and BSS Load. [onProgress] is delivered on the main thread as each phase starts.
     */
    suspend fun measure(
        settings: ThroughputSettings,
        imported: WiproberConfigStore.Imported,
        scanResults: List<ScanResult>,
        onProgress: (ThroughputProgress) -> Unit = {}
    ): PointThroughput = withContext(Dispatchers.IO) {
        val network = wifiNetwork()
        val beacons = scanResults.associateBy { it.BSSID?.lowercase(Locale.ROOT) }
        val socketFactory = network?.socketFactory ?: SocketFactory.getDefault()
        val resolve: (String) -> Array<InetAddress> = { host -> network?.getAllByName(host) ?: InetAddress.getAllByName(host) }
        val connection = { if (network == null) null else connectionInfo(beacons) }
        val timedHttp = TimedHttpClient(socketFactory = socketFactory, resolve = resolve)
        val runner = ThroughputRunner(
            iperf = Iperf3Client(socketFactory = socketFactory),
            http = HttpSpeedTest(open = { url ->
                (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            }),
            socketFactory = socketFactory,
            connection = connection,
            device = DeviceInfo(
                model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                androidVersion = Build.VERSION.RELEASE
            ),
            networkTests = NetworkTestRunner(
                http = timedHttp,
                socketFactory = socketFactory,
                resolve = resolve,
                datagramSocket = { DatagramSocket().also { socket -> network?.bindSocket(socket) } },
                context = networkContext(network),
                connection = connection,
                systemPing = NetworkTestRunner::runSystemPing,
                clock = clock
            ),
            scriptRunner = ScriptRunner(timedHttp),
            clock = clock
        )
        runner.measure(settings, imported.enabledTests, imported.enabledScripts) { progress ->
            mainThread.post { onProgress(progress) }
        }
    }

    /** Default gateway, DNS servers and interface name the Wi-Fi network got from DHCP. */
    private fun networkContext(network: Network?): NetworkContext {
        val link = network?.let { connectivity?.getLinkProperties(it) } ?: return NetworkContext()
        return NetworkContext(
            gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway != null && !it.gateway!!.isAnyLocalAddress }
                ?.gateway?.hostAddress,
            dnsServers = link.dnsServers,
            interfaceName = link.interfaceName
        )
    }

    @Suppress("DEPRECATION") // allNetworks: the active network may be cellular while Wi-Fi is up
    private fun wifiNetwork(): Network? = connectivity?.allNetworks?.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    @Suppress("DEPRECATION") // connectionInfo still reports the associated AP with location access
    private fun connectionInfo(beacons: Map<String?, ScanResult>): WifiConnectionInfo? {
        val info = wifiManager?.connectionInfo ?: return null
        val bssid = info.bssid?.lowercase(Locale.ROOT)?.takeUnless { it == HIDDEN_BSSID }
        val ssid = info.ssid?.removeSurrounding("\"")?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }
        if (bssid == null && ssid == null) return null
        val beacon = bssid?.let(beacons::get)
        return WifiConnectionInfo(
            ssid = ssid,
            bssid = bssid,
            rssi = info.rssi.takeIf { it in -126..0 },
            linkSpeedMbps = info.linkSpeed.takeIf { it > 0 },
            rxLinkSpeedMbps = info.rxLinkSpeedMbps.takeIf { it > 0 },
            frequencyMhz = info.frequency.takeIf { it > 0 },
            channelWidthMhz = beacon?.let { WifiChannels.widthMhz(it.channelWidth) },
            wifiStandard = WifiChannels.standardName(info.wifiStandard),
            bssLoad = beacon?.let(::bssLoad)
        )
    }

    private fun bssLoad(beacon: ScanResult): BssLoad? = beacon.informationElements
        .firstOrNull { it.id == BssLoad.ELEMENT_ID }
        ?.bytes // a fresh read-only buffer on every call
        ?.let { buffer -> BssLoad.parse(ByteArray(buffer.remaining()).also(buffer::get)) }

    private companion object {
        const val HIDDEN_BSSID = "02:00:00:00:00:00"
    }
}
