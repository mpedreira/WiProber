package com.mpedreira.nimwifi

import android.content.Context
import android.net.wifi.ScanResult
import android.os.Build
import android.security.NetworkSecurityPolicy
import java.net.ConnectException
import java.net.InetSocketAddress
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

    /**
     * Runs the configured tests of one point on a dedicated thread. [scanResults] is the scan
     * just taken at this point (channel width and BSS Load of the connected AP; empty for
     * "active tests only"). [control] bounds the run with a monotonic deadline and closes every
     * socket/process on cancel; cancelling the calling coroutine cancels it at once.
     * [listener] is called on the measuring thread.
     */
    suspend fun measure(
        settings: ThroughputSettings,
        imported: WiproberConfigStore.Imported,
        credentials: Map<String, String>,
        scanResults: List<ScanResult>,
        control: MeasurementControl,
        listener: RunListener
    ): PointThroughput = runCancellable(control, "active-tests") {
        val selected = wifiNetworks.select()
        // Name lookups never hold a phase longer than DNS_TIMEOUT_MILLIS, nor pile up (mejora 7).
        val network: MeasurementNetwork? = selected?.let { BoundedResolveNetwork(it, BoundedCalls(DNS_TIMEOUT_MILLIS, name = "dns")) }
        try {
            control.startRunDeadline(ThroughputRunner.runDeadlineMillis(settings, imported.enabledTests, imported.enabledScripts))
            val beacons = scanResults.mapNotNull { result ->
                result.BSSID?.lowercase(Locale.ROOT)?.let { it to BeaconInfo(WifiChannels.widthMhz(result.channelWidth), bssLoad(result)) }
            }.toMap()
            val socketFactory = network?.socketFactory ?: NoNetworkSocketFactory
            val timedHttp = TimedHttpClient(
                socketFactory = socketFactory,
                resolve = { host -> network?.resolve(host)?.toTypedArray() ?: throw MeasurementException(ErrorCodes.NOT_ON_WIFI) },
                control = control,
                cleartextPermitted = NetworkSecurityPolicy.getInstance()::isCleartextTrafficPermitted
            )
            val runner = ThroughputRunner(
                iperf = Iperf3Client(socketFactory = socketFactory, control = control),
                http = HttpSpeedTest(
                    open = { url -> network?.openHttp(url) ?: throw MeasurementException(ErrorCodes.NOT_ON_WIFI) },
                    control = control
                ),
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
                    clock = clock,
                    control = control
                ),
                scriptRunner = ScriptRunner(timedHttp, credentials),
                clock = clock,
                control = control
            )
            runner.measure(settings, imported.enabledTests, imported.enabledScripts, listener)
        } finally {
            control.clearRunDeadline()
            selected?.close()
        }
    }

    /**
     * Continuous-mode roaming probe. Every [RoamingSettings.intervalMillis] (best effort: an
     * attempt can take up to the connect timeout) it reads the association of the selected Wi-Fi
     * network and tries a TCP connect to the target over it, recording monotonic start/end and a
     * typed result. The target is resolved again when the network, or the gateway/DNS a
     * placeholder uses, changes. [onAttempt] runs on the main thread.
     */
    fun launchRoamingProbe(
        scope: CoroutineScope,
        settings: RoamingSettings,
        onAttempt: (startMono: Long, endMono: Long, result: String, association: Association?) -> Unit
    ): Job = scope.launch(Dispatchers.IO) {
        val config = settings.sanitized()
        var network: AndroidMeasurementNetwork? = null
        var resolvedKey: String? = null
        var address: InetAddress? = null
        val resolver = NonBlockingResolver(waitMillis = config.intervalMillis.toLong().coerceIn(50L, 500L))
        try {
            while (isActive) {
                val start = MonotonicMillisClock.now()
                if (network?.isAvailable() != true) {
                    network?.close()
                    network = wifiNetworks.select()
                }
                val net = network
                var association: Association? = null
                val result = if (net == null) {
                    ProbeResult.NO_WIFI
                } else {
                    association = net.association()
                    val host = expandRoamingTarget(config.host, net)
                    if (host == null) {
                        ProbeResult.INVALID_TARGET
                    } else {
                        // Resolve again when the network or the expanded host (gateway/DNS) changed.
                        // A slow or failing DNS never holds the cadence: the attempt is recorded as
                        // resolution_failed at once while the lookup goes on in the background.
                        val key = "${net.id}|$host"
                        if (key != resolvedKey) {
                            address = resolver.lookup(key, MonotonicMillisClock.now()) {
                                AddressPolicy.candidates(net.resolve(host)).firstOrNull()
                            }
                            resolvedKey = key.takeIf { address != null }
                        }
                        val target = address
                        if (target == null) ProbeResult.RESOLUTION_FAILED else connectOnce(net, target, config.port)
                    }
                }
                val end = MonotonicMillisClock.now()
                withContext(Dispatchers.Main) { onAttempt(start, end, result, association) }
                delay((config.intervalMillis - (MonotonicMillisClock.now() - start)).coerceAtLeast(0L))
            }
        } finally {
            resolver.close()
            network?.close()
        }
    }

    private fun connectOnce(network: MeasurementNetwork, target: InetAddress, port: Int): String = try {
        network.socketFactory.createSocket().use { it.connect(InetSocketAddress(target, port), ROAMING_CONNECT_TIMEOUT_MILLIS) }
        ProbeResult.CONNECTED
    } catch (e: java.net.SocketTimeoutException) {
        ProbeResult.TIMEOUT
    } catch (e: ConnectException) {
        if ("refused" in e.message.orEmpty().lowercase()) ProbeResult.REFUSED else ProbeResult.UNREACHABLE
    } catch (e: Exception) {
        ProbeResult.UNREACHABLE
    }

    private fun bssLoad(beacon: ScanResult): BssLoad? = beacon.informationElements
        .firstOrNull { it.id == BssLoad.ELEMENT_ID }
        ?.bytes // a fresh read-only buffer on every call
        ?.let { buffer -> BssLoad.parse(ByteArray(buffer.remaining()).also(buffer::get)) }

    private companion object {
        const val ROAMING_CONNECT_TIMEOUT_MILLIS = 1_000
        const val DNS_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Name lookups for the roaming probe that never block longer than [waitMillis]. A lookup that has
 * not finished keeps running in the background and its answer is used by a later attempt; after a
 * failure the same key is retried at most every [retryMillis].
 */
internal class NonBlockingResolver(
    private val waitMillis: Long,
    private val retryMillis: Long = 5_000L
) : java.io.Closeable {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "roaming-dns").apply { isDaemon = true }
    }
    private var pendingKey: String? = null
    private var pending: java.util.concurrent.Future<InetAddress?>? = null
    private val failedAt = mutableMapOf<String, Long>()

    /** The address, or null (failed, still resolving, or waiting to retry). */
    fun lookup(key: String, now: Long, resolve: () -> InetAddress?): InetAddress? {
        if (pendingKey != key) {
            pending?.cancel(true)
            pending = null
            pendingKey = null
            val failed = failedAt[key]
            if (failed != null && now - failed < retryMillis) return null
            pending = executor.submit<InetAddress?> { runCatching(resolve).getOrNull() }
            pendingKey = key
        }
        val future = pending ?: return null
        val result = try {
            future.get(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            return null // still resolving: this attempt counts as resolution_failed
        } catch (e: Exception) {
            null
        }
        pending = null
        pendingKey = null
        if (result == null) failedAt[key] = now else failedAt.remove(key)
        return result
    }

    override fun close() {
        pending?.cancel(true)
        executor.shutdownNow()
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

/**
 * Expands `${gateway}`/`${dns}` only when the configured host contains them, from the measured
 * network's current link; null when a needed value is missing (invalid target, not a
 * connectivity failure). A literal host is returned unchanged.
 */
internal fun expandRoamingTarget(template: String, network: MeasurementNetwork): String? {
    if ("\${" !in template) return template.ifBlank { null }
    val link = network.link()
    var host = template
    if ("\${gateway}" in host) host = host.replace("\${gateway}", link.gateway ?: return null)
    if ("\${dns}" in host) host = host.replace("\${dns}", link.dnsServers.firstOrNull()?.hostAddress ?: return null)
    return host.takeUnless { "\${" in it }
}
