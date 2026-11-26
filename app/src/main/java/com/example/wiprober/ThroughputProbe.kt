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
import java.net.HttpURLConnection
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
        scanResults: List<ScanResult>,
        onProgress: (ThroughputProgress) -> Unit = {}
    ): PointThroughput = withContext(Dispatchers.IO) {
        val network = wifiNetwork()
        val beacons = scanResults.associateBy { it.BSSID?.lowercase(Locale.ROOT) }
        val runner = ThroughputRunner(
            iperf = Iperf3Client(socketFactory = network?.socketFactory ?: javax.net.SocketFactory.getDefault()),
            http = HttpSpeedTest(open = { url ->
                (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            }),
            socketFactory = network?.socketFactory ?: javax.net.SocketFactory.getDefault(),
            connection = { if (network == null) null else connectionInfo(beacons) },
            device = DeviceInfo(
                model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                androidVersion = Build.VERSION.RELEASE
            ),
            clock = clock
        )
        runner.measure(settings) { progress -> mainThread.post { onProgress(progress) } }
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
