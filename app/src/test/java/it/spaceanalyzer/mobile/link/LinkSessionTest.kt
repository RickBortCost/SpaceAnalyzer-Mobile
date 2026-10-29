package it.spaceanalyzer.mobile.link

import io.grpc.Status
import it.spaceanalyzer.link.v1.HelloReply
import it.spaceanalyzer.link.v1.Sample
import it.spaceanalyzer.link.v1.SampleBatch
import it.spaceanalyzer.mobile.model.LinkProfile
import it.spaceanalyzer.mobile.record.SfarReader
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun batch(seq: Long, gap: Boolean = false, v: Double = seq.toDouble()) = SampleBatch.newBuilder().setSeq(seq).setGap(gap)
    .addSamples(Sample.newBuilder().setSignalId(1).setTimestampUs(seq * 1000).setValue(v)).build()

/** attempts[i] runs on the i-th connection; the flow may end normally or throw. */
private class ScriptedClient(val resumes: MutableList<Long>, val attempt: (Int) -> Flow<SampleBatch>, val n: Int) : LinkClient {
    override suspend fun hello(): HelloReply = HelloReply.newBuilder().setProtocolVersion(1).setServerName("PC").setAcquisitionName("A").build()
    override suspend fun listSignals() = listOf(SignalInfo(1, "S1", "V"))
    override fun subscribe(resumeAfterSeq: Long): Flow<SampleBatch> { resumes += resumeAfterSeq; return attempt(n) }
    override fun close() = Unit
}

class LinkSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resumes = mutableListOf<Long>()
    @After fun tearDown() { scope.cancel() }

    private fun session(attempt: (Int) -> Flow<SampleBatch>): LinkSession {
        var n = 0
        return LinkSession(scope, { _, _ -> ScriptedClient(resumes, attempt, n++) }, backoffMs = { 1 })
    }
    private val profile = LinkProfile(host = "192.168.42.2", pinnedFingerprint = "AA")

    @Test fun reconnectsAndResumesFromLastSequence() = runBlocking {
        val s = session { n ->
            if (n == 0) flow { emit(batch(1)); emit(batch(2)); throw IOException("cavo scollegato") }
            else flow { emit(batch(3)); kotlinx.coroutines.awaitCancellation() }
        }
        s.start(profile, "123456")
        withTimeout(5000) { s.stats.first { it.lastSeq == 3L } }
        assertEquals(listOf(0L, 2L), resumes)
        assertEquals(0, s.stats.value.gaps)
        assertEquals(3L, s.stats.value.samples)
        s.stop()
        assertEquals(LinkState.Idle, s.state.value)
    }

    @Test fun reportsGapWhenServerCannotResume() = runBlocking {
        val s = session { n ->
            if (n == 0) flow { emit(batch(1)); throw IOException("x") }
            else flow { emit(batch(50, gap = true)); kotlinx.coroutines.awaitCancellation() }
        }
        s.start(profile, "1")
        withTimeout(5000) { s.stats.first { it.lastSeq == 50L } }
        assertEquals(1, s.stats.value.gaps)
        s.stop()
    }

    @Test fun authenticationFailureStopsWithoutRetry() = runBlocking {
        val s = session { flow { throw Status.UNAUTHENTICATED.asRuntimeException() } }
        s.start(profile, "bad")
        val failed = withTimeout(5000) { s.state.first { it is LinkState.Failed } } as LinkState.Failed
        assertTrue(failed.reason.contains("Codice"))
        assertEquals(1, resumes.size)
    }

    @Test fun recordingSurvivesReconnectionAndIsReadable() = runBlocking {
        val file = File.createTempFile("rec", ".sfar").apply { deleteOnExit() }
        val s = session { n ->
            if (n == 0) flow { emit(batch(1)); emit(batch(2)); kotlinx.coroutines.delay(150); throw IOException("x") }
            else flow { emit(batch(3)); kotlinx.coroutines.awaitCancellation() }
        }
        s.start(profile, "1")
        withTimeout(5000) { s.state.first { it is LinkState.Streaming } }
        s.startRecording(file, "Acq")
        withTimeout(5000) { s.stats.first { it.lastSeq == 3L } }
        s.stopRecording(); s.stop()
        val r = SfarReader(file.inputStream())
        assertEquals("Acq", r.header.acquisition)
        val values = generateSequence { r.next() }.map { it.value }.toList()
        assertTrue(values.contains(3.0))
        assertEquals(values.sorted(), values)
    }
}

class LowSpaceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    @Test fun warnsThenStopsRecordingKeepingSavedData() = runBlocking {
        val file = File.createTempFile("lowspace", ".sfar").apply { deleteOnExit() }
        var free = 1L shl 30
        val s = LinkSession(scope, { _, _ -> ScriptedClient(mutableListOf(), { _ ->
            flow { var i = 1L; while (true) { emit(batch(i++)); kotlinx.coroutines.delay(5) } }
        }, 0) }, backoffMs = { 1 }, freeSpace = { free }, spaceCheckMs = 0, warnBytes = 200, stopBytes = 50)
        s.start(LinkProfile(host = "192.168.42.2", pinnedFingerprint = "AA"), "1")
        withTimeout(5000) { s.state.first { it is LinkState.Streaming } }
        s.startRecording(file, "Acq")
        free = 100
        withTimeout(5000) { s.recording.first { it.lowSpace } }
        free = 10
        val stopped = withTimeout(5000) { s.recording.first { it.error != null } }
        assertTrue(stopped.error!!.contains("spazio"))
        assertEquals(null, stopped.file)
        assertTrue(SfarReader(file.inputStream()).next() != null)
        s.stop()
    }
}
