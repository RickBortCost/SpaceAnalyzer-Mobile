package it.spaceanalyzer.mobile.link

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoLinkClientTest {
    @Test fun isLabelledAsSimulatedAndStreamsAllSignals() = runBlocking {
        val c = DemoLinkClient()
        assertEquals(DEMO_NAME, c.hello().serverName)
        assertTrue(c.hello().acquisitionName.contains("DEMO"))
        assertEquals(listOf(1, 2, 3, 4), c.listSignals().map { it.id })
        val batches = withTimeout(5000) { c.subscribe(0).take(3).toList() }
        assertEquals(listOf(1L, 2L, 3L), batches.map { it.seq })
        assertEquals(4, batches[0].samplesCount)
    }
}
