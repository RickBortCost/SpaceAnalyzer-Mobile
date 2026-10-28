package it.spaceanalyzer.mobile.link

import java.security.cert.CertificateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedTlsTest {
    @Test fun fingerprintIsColonSeparatedUppercaseSha256() {
        assertTrue(TestCert.fingerprint.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }
    @Test fun acceptsOnlyThePinnedCertificate() {
        PinnedTrustManager(TestCert.fingerprint.lowercase()).checkServerTrusted(arrayOf(TestCert.certificate), "RSA")
    }
    @Test(expected = CertificateException::class) fun rejectsAnyOtherCertificate() {
        PinnedTrustManager("00:" + TestCert.fingerprint.drop(3)).checkServerTrusted(arrayOf(TestCert.certificate), "RSA")
    }
    @Test(expected = CertificateException::class) fun rejectsEmptyChain() {
        PinnedTrustManager(TestCert.fingerprint).checkServerTrusted(emptyArray(), "RSA")
    }
    @Test(expected = CertificateException::class) fun neverAcceptsClientCertificates() {
        PinnedTrustManager(TestCert.fingerprint).checkClientTrusted(arrayOf(TestCert.certificate), "RSA")
    }
    @Test fun fatalClassification() {
        assertTrue(isFatal(RuntimeException("x", CertificateException("pin"))))
        assertEquals(false, isFatal(java.io.IOException("cable")))
    }
}
