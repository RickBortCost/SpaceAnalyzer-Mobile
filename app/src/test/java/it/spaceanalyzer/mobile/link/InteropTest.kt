package it.spaceanalyzer.mobile.link

import java.net.InetSocketAddress
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Production client against the independent Python server (tools/sfa_dev_server.py).
 * Runs only when SFA_INTEROP_PORT is set, e.g. SFA_INTEROP_PORT=47801 SFA_INTEROP_CODE=123456.
 */
class InteropTest {
    private val port = System.getenv("SFA_INTEROP_PORT")?.toIntOrNull()
    private val code = System.getenv("SFA_INTEROP_CODE").orEmpty()

    @Test fun pairsAuthenticatesStreamsAndResumes() = runBlocking {
        assumeTrue("SFA_INTEROP_PORT non impostata", port != null)
        val fp = fetchServerFingerprint(InetSocketAddress("127.0.0.1", port!!), 3000)
        SfaLinkClient("127.0.0.1", port, fp, code).use { c ->
            withTimeout(15_000) {
                assertEquals(1, c.hello().protocolVersion)
                assertEquals(listOf(1, 2, 3, 4), c.listSignals().map { it.id })
                val live = c.subscribe(0).take(10).toList()
                assertEquals(10, live.size)
                assertEquals(4, live[0].samplesCount)
                assertEquals(live.map { it.seq }, live.map { it.seq }.sorted())
                // Resume from 5 batches back: the server must replay without a gap.
                val from = live.last().seq - 5
                val replay = c.subscribe(from).take(3).toList()
                assertEquals(from + 1, replay[0].seq)
                assertTrue(!replay[0].gap)
            }
        }
        SfaLinkClient("127.0.0.1", port, fp, "wrong").use { c ->
            val f = runCatching { withTimeout(10_000) { c.hello() } }.exceptionOrNull()
            assertTrue(f != null && isFatal(f))
        }
    }
}
