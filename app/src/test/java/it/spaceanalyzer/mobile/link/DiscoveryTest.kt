package it.spaceanalyzer.mobile.link

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryTest {
    private fun parse(json: String, ip: String = "192.168.42.2"): DiscoveredPc? {
        val b = json.toByteArray(); return parseBeacon(b, b.size, InetAddress.getByName(ip))
    }
    @Test fun acceptsValidBeaconFromPrivateAddress() =
        assertEquals(DiscoveredPc("192.168.42.2", 47800, "Lab"), parse("""{"sfa":1,"name":"Lab","port":47800}"""))
    @Test fun rejectsPublicSourceWrongVersionAndBadPort() {
        assertNull(parse("""{"sfa":1,"port":47800}""", "8.8.8.8"))
        assertNull(parse("""{"sfa":2,"port":47800}"""))
        assertNull(parse("""{"sfa":1,"port":70000}"""))
        assertNull(parse("not json"))
    }
    @Test fun sanitizesName() =
        assertEquals("ab", parse("""{"sfa":1,"name":"a\u0000b","port":1}""")!!.name)
}
