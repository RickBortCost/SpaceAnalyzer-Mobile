package it.spaceanalyzer.mobile.link

import java.io.File
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assume

/** Self-signed certificate generated once per test run with the system openssl. */
object TestCert {
    val dir: File by lazy {
        val d = Files.createTempDirectory("sfa-cert").toFile().apply { deleteOnExit() }
        val p = ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
            "-keyout", "key.pem", "-out", "cert.pem", "-subj", "/CN=sfa-test")
            .directory(d).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        Assume.assumeTrue("openssl non disponibile: $out", p.waitFor() == 0)
        d
    }
    val certFile get() = File(dir, "cert.pem")
    val keyFile get() = File(dir, "key.pem")
    val certificate: X509Certificate by lazy {
        certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    }
    val fingerprint: String by lazy { fingerprintOf(certificate) }
}
