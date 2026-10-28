package it.spaceanalyzer.mobile.link

import it.spaceanalyzer.mobile.model.DEFAULT_DISCOVERY_PORT
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject

/** UDP beacon announced by the PC: {"sfa":1,"name":"...","port":47800}. Only a hint: trust comes from pairing. */
data class DiscoveredPc(val host: String, val port: Int, val name: String)

fun parseBeacon(payload: ByteArray, length: Int, source: InetAddress): DiscoveredPc? = runCatching {
    if (length !in 1..512 || !(source.isSiteLocalAddress || source.isLinkLocalAddress)) return null
    val json = JSONObject(String(payload, 0, length, Charsets.UTF_8))
    val port = json.getInt("port")
    if (json.getInt("sfa") != PROTOCOL_VERSION || port !in 1..65535) return null
    val host = source.hostAddress ?: return null
    if (':' in host) return null
    DiscoveredPc(host, port, json.optString("name").filter { !it.isISOControl() }.take(80).ifBlank { host })
}.getOrNull()

fun discoveryFlow(port: Int = DEFAULT_DISCOVERY_PORT): Flow<DiscoveredPc> = flow {
    DatagramSocket(null).use { socket ->
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(port))
        socket.soTimeout = 1000
        val buffer = ByteArray(512)
        while (currentCoroutineContext().isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
            parseBeacon(packet.data, packet.length, packet.address)?.let { emit(it) }
        }
    }
}.flowOn(Dispatchers.IO)
