package it.spaceanalyzer.mobile.model

import org.junit.Assert.*
import org.junit.Test

class ProfileTest {
    @Test fun defaultProfileIsValid() { assertNull(Profile().validationError()) }
    @Test fun rejectsInvalidAddresses() {
        listOf("256.1.1.1", "localhost", "1.2.3", "1.2.3.-1", "1.2.3.4; reboot", "").forEach { assertFalse(isIpv4(it)) }
    }
    @Test fun rejectsCredentialsAndWrongProtocol() {
        listOf("http://192.168.50.20:4840", "opc.tcp://user:password@192.168.50.20:4840", "opc.tcp://192.168.50.20:0", "opc.tcp://localhost:4840").forEach {
            assertNotNull(Profile(endpoint = it).validationError())
        }
    }
    @Test fun endpointMayDifferFromSshHost() {
        assertEquals("192.168.50.21" to 4841, Profile(endpoint = "opc.tcp://192.168.50.21:4841/path").opcAddress())
    }
    @Test fun boundsTimeoutAndPorts() {
        assertNotNull(Profile(timeoutMs = "0").validationError())
        assertNotNull(Profile(sshPort = "65536").validationError())
        assertNull(Profile(timeoutMs = "15000", sshPort = "65535").validationError())
    }
    @Test fun masks() {
        assertEquals("255.255.255.0", subnetMask(24))
        assertEquals("0.0.0.0", subnetMask(0))
        assertEquals("255.255.255.255", subnetMask(32))
    }
}
