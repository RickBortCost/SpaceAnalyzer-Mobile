package it.spaceanalyzer.mobile.link

import it.spaceanalyzer.link.v1.SampleBatch
import it.spaceanalyzer.mobile.model.LinkProfile
import it.spaceanalyzer.mobile.record.SfarWriter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RecordingState(val file: File? = null, val samples: Long = 0, val error: String? = null)

/**
 * Owns the connection to the PC, reconnects with exponential backoff and resumes the stream
 * from the last sequence number. Lives at application scope so it survives screen changes.
 */
class LinkSession(
    private val scope: CoroutineScope,
    private val clientFactory: (LinkProfile, String) -> LinkClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoffMs: (Int) -> Long = { attempt -> (1000L shl (attempt - 1).coerceAtMost(3)) },
    private val trendCapacity: Int = 5000
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _signals = MutableStateFlow<List<SignalInfo>>(emptyList())
    val signals: StateFlow<List<SignalInfo>> = _signals.asStateFlow()
    private val _latest = MutableStateFlow<Map<Int, LatestValue>>(emptyMap())
    val latest: StateFlow<Map<Int, LatestValue>> = _latest.asStateFlow()
    private val _stats = MutableStateFlow(BatchStats(0, 0, 0))
    val stats: StateFlow<BatchStats> = _stats.asStateFlow()
    private val _recording = MutableStateFlow(RecordingState())
    val recording: StateFlow<RecordingState> = _recording.asStateFlow()

    private val rings = HashMap<Int, Ring>()
    private var job: Job? = null
    private var writer: SfarWriter? = null

    fun trend(signalId: Int): Series? = synchronized(rings) { rings[signalId] }?.snapshot()

    val isActive: Boolean get() = job?.isActive == true

    fun start(profile: LinkProfile, accessCode: String) {
        if (isActive) return
        _latest.value = emptyMap(); _stats.value = BatchStats(0, 0, 0)
        synchronized(rings) { rings.clear() }
        job = scope.launch(Dispatchers.IO) { run(profile, accessCode) }
    }

    fun stop() {
        job?.cancel(); job = null
        stopRecording()
        _state.value = LinkState.Idle
    }

    /** Recording starts on the next received batch; the signal table is taken from the current session. */
    fun startRecording(file: File, acquisition: String) {
        synchronized(this) {
            if (writer != null) return
            try {
                writer = SfarWriter(file, acquisition, _signals.value, clock())
                _recording.value = RecordingState(file)
            } catch (e: Exception) {
                _recording.value = RecordingState(error = "Registrazione non avviata: ${e.message}")
            }
        }
    }

    fun stopRecording() {
        synchronized(this) {
            val w = writer ?: return
            writer = null
            runCatching { w.close() }
            _recording.value = RecordingState(file = null, samples = _recording.value.samples)
        }
    }

    private suspend fun run(profile: LinkProfile, accessCode: String) {
        var attempt = 0
        var lastSeq = 0L
        var reason = ""
        while (currentCoroutineContext().isActive) {
            _state.value = if (attempt == 0) LinkState.Connecting else LinkState.Reconnecting(attempt, reason)
            val client = clientFactory(profile, accessCode)
            try {
                val hello = client.hello()
                check(hello.protocolVersion == PROTOCOL_VERSION) { "Versione protocollo ${hello.protocolVersion} non supportata (richiesta $PROTOCOL_VERSION)" }
                _signals.value = client.listSignals()
                _state.value = LinkState.Streaming(hello.serverName, hello.acquisitionName)
                attempt = 0
                client.subscribe(lastSeq).collect { batch ->
                    lastSeq = onBatch(batch, lastSeq)
                }
                reason = "Il PC ha chiuso il flusso"
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (isFatal(t)) {
                    stopRecording()
                    _state.value = LinkState.Failed(describe(t))
                    return
                }
                reason = describe(t)
            } finally {
                client.close()
            }
            attempt++
            delay(backoffMs(attempt))
        }
    }

    private fun onBatch(batch: SampleBatch, lastSeq: Long): Long {
        val now = clock()
        val s = _stats.value
        val missing = lastSeq > 0 && (batch.gap || batch.seq != lastSeq + 1)
        val latest = HashMap(_latest.value)
        for (sample in batch.samplesList) {
            latest[sample.signalId] = LatestValue(sample.value, sample.timestampUs, sample.quality, now)
            val ring = synchronized(rings) { rings.getOrPut(sample.signalId) { Ring(trendCapacity) } }
            ring.add(sample.timestampUs, sample.value)
        }
        _latest.value = latest
        val n = batch.samplesCount
        synchronized(this) {
            writer?.let { w ->
                try {
                    w.append(batch.samplesList)
                    _recording.value = _recording.value.copy(samples = _recording.value.samples + n)
                } catch (e: Exception) {
                    writer = null
                    runCatching { w.close() }
                    _recording.value = RecordingState(error = "Registrazione interrotta: ${e.message}")
                }
            }
        }
        _stats.value = BatchStats(batch.seq, s.gaps + if (missing) 1 else 0, s.samples + n)
        return batch.seq
    }
}
