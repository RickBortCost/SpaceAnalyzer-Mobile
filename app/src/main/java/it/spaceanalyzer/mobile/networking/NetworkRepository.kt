package it.spaceanalyzer.mobile.networking

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import it.spaceanalyzer.mobile.model.subnetMask
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

data class NetworkInfo(val id: Long, val name: String, val transport: String,
    val addresses: List<String>, val gateways: List<String>, val dns: List<String>,
    val ethernet: Boolean)

class NetworkRepository(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private fun describe(network: Network): NetworkInfo? {
        val c = manager.getNetworkCapabilities(network) ?: return null
        val p = manager.getLinkProperties(network) ?: return null
        val ethernet = c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val kind = when {
            ethernet -> "Ethernet"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellulare"
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Altro"
        }
        return NetworkInfo(network.networkHandle, p.interfaceName ?: "Interfaccia sconosciuta", kind,
            p.linkAddresses.map { a ->
                "${a.address.hostAddress}/${a.prefixLength}" +
                    if (a.address is Inet4Address) " • ${subnetMask(a.prefixLength)}" else ""
            }, p.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress },
            p.dnsServers.mapNotNull { it.hostAddress }, ethernet)
    }
    val networks = callbackFlow {
        fun emitSnapshot() { trySend(manager.allNetworks.mapNotNull(::describe).sortedBy { it.id }) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emitSnapshot()
            override fun onLost(network: Network) = emitSnapshot()
            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) = emitSnapshot()
            override fun onLinkPropertiesChanged(network: Network, p: LinkProperties) = emitSnapshot()
        }
        // No INTERNET requirement: direct offline Ethernet is deliberately included.
        val request = NetworkRequest.Builder()
            // clearCapabilities() only exists from API 30. Remove the three
            // default requirements individually using Android 10-compatible APIs.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        manager.registerNetworkCallback(request, callback)
        emitSnapshot()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
    suspend fun probe(networkId: Long, host: String, port: Int, timeout: Int): String = withContext(Dispatchers.IO) {
        val network = manager.allNetworks.firstOrNull { it.networkHandle == networkId }
            ?: return@withContext "Ethernet scollegata"
        if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true)
            return@withContext "Rete selezionata non Ethernet"
        try {
            Socket().use { socket ->
                network.bindSocket(socket)
                // Validated literal IPv4: never route DNS via Wi-Fi or cellular.
                socket.connect(InetSocketAddress(InetAddress.getByName(host), port), timeout)
            }
            "TCP raggiungibile (nessuna sessione autenticata)"
        } catch (e: Exception) {
            "Non raggiungibile: ${e.javaClass.simpleName} — ${e.message.orEmpty().take(160)}"
        }
    }
}
