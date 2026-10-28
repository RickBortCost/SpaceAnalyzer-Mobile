package it.spaceanalyzer.mobile.link

import android.annotation.SuppressLint
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Formats the SHA-256 of a DER certificate as "AB:CD:..." (64 hex digits). */
fun fingerprintOf(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

/** Accepts only the single leaf certificate the user confirmed. No CA chain, no accept-all. */
@SuppressLint("CustomX509TrustManager")
class PinnedTrustManager(private val pinned: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Certificato del PC assente")
        leaf.checkValidity()
        if (!fingerprintOf(leaf).equals(pinned, ignoreCase = true))
            throw CertificateException("Impronta del certificato diversa da quella confermata")
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Autenticazione client non supportata")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

fun pinnedSocketFactory(pinned: String): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrustManager(pinned)), SecureRandom()) }.socketFactory

/** The identity is the pinned certificate, not the host name: the PC is reached by a literal IPv4. */
val PinnedHostnameVerifier = HostnameVerifier { _, _ -> true }

/**
 * Pairing step: performs only a TLS handshake to read the certificate presented by the PC,
 * sends no application data and closes. The result is shown to the user, who must compare it
 * with the fingerprint displayed on the PC before it is stored and trusted.
 */
fun fetchServerFingerprint(address: InetSocketAddress, timeoutMs: Int): String {
    var seen: X509Certificate? = null
    // Pairing only: reads the presented certificate, then the socket is closed without sending data.
    @SuppressLint("CustomX509TrustManager")
    val reader = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { seen = chain?.firstOrNull() }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val factory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(reader), SecureRandom()) }.socketFactory
    java.net.Socket().use { raw ->
        raw.connect(address, timeoutMs)
        raw.soTimeout = timeoutMs
        factory.createSocket(raw, address.hostString, address.port, true).use { tls ->
            (tls as javax.net.ssl.SSLSocket).startHandshake()
        }
    }
    return fingerprintOf(seen ?: throw CertificateException("Il PC non ha presentato un certificato"))
}
