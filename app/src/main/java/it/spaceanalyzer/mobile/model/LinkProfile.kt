package it.spaceanalyzer.mobile.model

import java.net.InetAddress

const val DEFAULT_LINK_PORT = 47800
const val DEFAULT_DISCOVERY_PORT = 47810

data class LinkProfile(
    val name: String = "PC Space",
    val host: String = "",
    val port: String = DEFAULT_LINK_PORT.toString(),
    /** SHA-256 of the PC certificate, confirmed by the user; empty until paired. */
    val pinnedFingerprint: String = ""
) {
    fun validationError(): String? = when {
        name.isBlank() || name.length > 80 -> "Nome richiesto (massimo 80 caratteri)"
        !isIpv4(host) -> "Inserire l’indirizzo IPv4 del PC"
        port.toIntOrNull() !in 1..65535 -> "Porta tra 1 e 65535"
        else -> null
    }
    fun portNumber(): Int = port.toInt()
    fun resolved(): InetAddress = InetAddress.getByName(host)
}

fun isIpv4(value: String): Boolean = value.split('.').let { parts ->
    parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } && it.toInt() in 0..255 }
}

fun subnetMask(prefix: Int): String {
    require(prefix in 0..32)
    val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
    return (3 downTo 0).joinToString(".") { ((mask ushr (it * 8)) and 255).toString() }
}
