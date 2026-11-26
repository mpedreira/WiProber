package com.example.wiprober

import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.Collections
import javax.net.SocketFactory

/**
 * Test double of the measured Wi-Fi network. Its resolver only knows [hosts] (plus IP literals),
 * so a name the system DNS cannot resolve proves a client used this network's resolver.
 */
class FakeMeasurementNetwork(
    var hosts: Map<String, List<InetAddress>> = emptyMap(),
    var link: NetworkContext = NetworkContext(),
    var associationProvider: () -> Association = { DEFAULT_AP },
    var available: () -> Boolean = { true },
    override val id: String = "wifi-1",
    override val selection: String = "only",
    override val socketFactory: SocketFactory = SocketFactory.getDefault()
) : MeasurementNetwork {
    val resolved: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val boundDatagrams = Collections.synchronizedList(mutableListOf<DatagramSocket>())

    override fun resolve(host: String): List<InetAddress> {
        resolved += host
        hosts[host]?.let { return it }
        if (host.matches(Regex("[0-9.]+|[0-9a-fA-F:]+"))) return listOf(InetAddress.getByName(host))
        throw UnknownHostException(host)
    }

    override fun openHttp(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection
    override fun bind(socket: DatagramSocket) { boundDatagrams += socket }
    override fun link(): NetworkContext = link
    override fun association(): Association = associationProvider()
    override fun isAvailable(): Boolean = available()

    companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        val DEFAULT_AP = Association(
            known = true, source = Association.SOURCE_NETWORK_CALLBACK,
            ssid = "CORP", bssid = "aa:bb:cc:dd:ee:01", rssi = -58, linkSpeedMbps = 573,
            rxLinkSpeedMbps = 649, frequencyMhz = 5_500, wifiStandard = "802.11ax (Wi-Fi 6/6E)"
        )
    }
}

/** Socket factory whose every [failEvery]-th connect fails, to simulate lost probes. */
class FlakySocketFactory(private val failEvery: Int) : SocketFactory() {
    private val count = java.util.concurrent.atomic.AtomicInteger()
    override fun createSocket(): java.net.Socket = object : java.net.Socket() {
        override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
            if (count.incrementAndGet() % failEvery == 0) throw java.net.SocketTimeoutException("lost probe")
            super.connect(endpoint, timeout)
        }
    }
    override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress?, port: Int) = throw UnsupportedOperationException()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
}
