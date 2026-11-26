package com.example.wiprober

import android.content.Context
import android.net.wifi.ScanResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import javax.net.SocketFactory
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android boundary of the per-point throughput tests. One Wi-Fi [Network] is selected per run
 * ([AndroidWifiNetworks]) and every resolution, socket, HTTP connection and association read goes
 * through it, so a weak Wi-Fi never silently falls back to mobile data; without Wi-Fi nothing is
 * sent and each target reports [ThroughputRunner.NOT_ON_WIFI].
 */
class ThroughputProbe(context: Context, private val clock: MillisClock = SystemMillisClock) {
    private val appContext = context.applicationContext
    private val wifiNetworks = AndroidWifiNetworks(appContext)
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
        val network = wifiNetworks.select()
        try {
            val beacons = scanResults.mapNotNull { result ->
                result.BSSID?.lowercase(Locale.ROOT)?.let { it to BeaconInfo(WifiChannels.widthMhz(result.channelWidth), bssLoad(result)) }
            }.toMap()
            val timedHttp = TimedHttpClient(
                socketFactory = network?.socketFactory ?: NoNetworkSocketFactory,
                resolve = { host -> network?.resolve(host)?.toTypedArray() ?: throw MeasurementException(ErrorCodes.NOT_ON_WIFI) }
            )
            val runner = ThroughputRunner(
                iperf = Iperf3Client(socketFactory = network?.socketFactory ?: NoNetworkSocketFactory),
                http = HttpSpeedTest(open = { url ->
                    network?.openHttp(url) ?: throw MeasurementException(ErrorCodes.NOT_ON_WIFI)
                }),
                network = network,
                beacons = beacons,
                device = DeviceInfo(
                    model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    androidVersion = Build.VERSION.RELEASE
                ),
                networkTests = NetworkTestRunner(
                    http = timedHttp,
                    network = network,
                    systemPing = NetworkTestRunner::runSystemPing,
                    clock = clock
                ),
                scriptRunner = ScriptRunner(timedHttp),
                clock = clock
            )
            runner.measure(settings, imported.enabledTests, imported.enabledScripts) { progress ->
                mainThread.post { onProgress(progress) }
            }
        } finally {
            network?.close()
        }
    }

    /**
     * Continuous-mode roaming probe: every [RoamingSettings.intervalMillis] reads the associated AP
     * of the selected Wi-Fi network and tries a TCP connect to the target over it (a refused
     * connection still proves connectivity). [onSample] runs on the main thread with the
     * wall-clock time of the probe.
     */
    fun launchRoamingProbe(
        scope: CoroutineScope,
        settings: RoamingSettings,
        isScanning: () -> Boolean,
        onSample: (atMillis: Long, bssid: String?, rssi: Int?, ok: Boolean, rttMs: Double?, scanning: Boolean) -> Unit
    ): Job = scope.launch(Dispatchers.IO) {
        val config = settings.sanitized()
        var network: AndroidMeasurementNetwork? = null
        var address: InetAddress? = null
        try {
            while (isActive) {
                val wall = System.currentTimeMillis()
                val started = SystemClock.elapsedRealtime()
                if (network?.isAvailable() != true) {
                    network?.close()
                    network = wifiNetworks.select()
                    address = null
                }
                val net = network
                val info = net?.association()?.takeIf { it.known }
                var ok = false
                var rtt: Double? = null
                if (net != null) {
                    try {
                        val target = address ?: AddressPolicy.candidates(net.resolve(roamingHost(config.host, net))).first()
                            .also { address = it }
                        val start = System.nanoTime()
                        try {
                            net.socketFactory.createSocket().use {
                                it.connect(InetSocketAddress(target, config.port), ROAMING_CONNECT_TIMEOUT_MILLIS)
                            }
                            ok = true
                        } catch (e: ConnectException) {
                            ok = "refused" in e.message.orEmpty().lowercase()
                        }
                        if (ok) rtt = round1((System.nanoTime() - start) / 1_000_000.0)
                    } catch (e: Exception) {
                        if (e is UnknownHostException || e is MeasurementException) address = null
                    }
                }
                val scanning = isScanning()
                withContext(Dispatchers.Main) { onSample(wall, info?.bssid, info?.rssi, ok, rtt, scanning) }
                delay((config.intervalMillis - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0L))
            }
        } finally {
            network?.close()
        }
    }

    /** Placeholders are only evaluated when the configured host contains them. */
    private fun roamingHost(template: String, network: MeasurementNetwork): String {
        if ("\${" !in template) return template
        val link = network.link()
        var host = template
        if ("\${gateway}" in host) host = host.replace("\${gateway}", link.gateway ?: throw UnknownHostException("no default gateway"))
        if ("\${dns}" in host) host = host.replace("\${dns}", link.dnsServers.firstOrNull()?.hostAddress ?: throw UnknownHostException("no DNS server"))
        return host
    }

    private fun bssLoad(beacon: ScanResult): BssLoad? = beacon.informationElements
        .firstOrNull { it.id == BssLoad.ELEMENT_ID }
        ?.bytes // a fresh read-only buffer on every call
        ?.let { buffer -> BssLoad.parse(ByteArray(buffer.remaining()).also(buffer::get)) }

    private companion object {
        const val ROAMING_CONNECT_TIMEOUT_MILLIS = 1_000
    }
}

/** Socket factory for "no Wi-Fi": refuses to create sockets instead of using the default network. */
internal object NoNetworkSocketFactory : SocketFactory() {
    private fun refuse(): Nothing = throw MeasurementException(ErrorCodes.NOT_ON_WIFI)
    override fun createSocket(): java.net.Socket = refuse()
    override fun createSocket(host: String?, port: Int): java.net.Socket = refuse()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): java.net.Socket = refuse()
    override fun createSocket(host: InetAddress?, port: Int): java.net.Socket = refuse()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): java.net.Socket = refuse()
}
