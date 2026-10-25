package it.spaceanalyzer.mobile.model

import java.net.URI

data class Profile(
    val name: String = "Beckhoff CX2062",
    val host: String = "192.168.50.20",
    val endpoint: String = "opc.tcp://192.168.50.20:4840",
    val sshPort: String = "22",
    val phoneIp: String = "192.168.50.10",
    val prefix: String = "24",
    val timeoutMs: String = "3000"
) {
    fun validationError(): String? = when {
        name.isBlank() || name.length > 80 -> "Nome richiesto (massimo 80 caratteri)"
        !isIpv4(host) || !isIpv4(phoneIp) -> "Inserire indirizzi IPv4 validi"
        prefix.toIntOrNull() !in 1..30 -> "Prefisso subnet tra 1 e 30"
        sshPort.toIntOrNull() !in 1..65535 -> "Porta SSH tra 1 e 65535"
        timeoutMs.toIntOrNull() !in 250..15000 -> "Timeout tra 250 e 15000 ms"
        opcAddress() == null -> "Endpoint richiesto: opc.tcp://IPv4:porta (senza credenziali)"
        else -> null
    }
    fun opcAddress(): Pair<String, Int>? = runCatching {
        val uri = URI(endpoint)
        require(uri.scheme == "opc.tcp" && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(isIpv4(uri.host.orEmpty()) && uri.port in 1..65535)
        uri.host to uri.port
    }.getOrNull()
}
fun isIpv4(value: String): Boolean = value.split('.').let { parts ->
    parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } && it.toInt() in 0..255 }
}
fun subnetMask(prefix: Int): String {
    require(prefix in 0..32)
    val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
    return (3 downTo 0).joinToString(".") { ((mask ushr (it * 8)) and 255).toString() }
}
