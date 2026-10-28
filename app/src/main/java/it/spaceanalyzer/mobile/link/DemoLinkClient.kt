package it.spaceanalyzer.mobile.link

import it.spaceanalyzer.link.v1.HelloReply
import it.spaceanalyzer.link.v1.Sample
import it.spaceanalyzer.link.v1.SampleBatch
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

const val DEMO_HOST = "demo"
const val DEMO_NAME = "Modalità prova (dati simulati)"

/**
 * Local test source: no network, no PC. Values are synthetic and every surface that shows them
 * (state, live page, recording header) says so. It exists to try the app without a Space server.
 */
class DemoLinkClient : LinkClient {
    override suspend fun hello(): HelloReply = HelloReply.newBuilder().setProtocolVersion(PROTOCOL_VERSION)
        .setServerName(DEMO_NAME).setAcquisitionName("DEMO simulata").build()

    override suspend fun listSignals() = listOf(
        SignalInfo(1, "Seno 1 Hz", "V"), SignalInfo(2, "Seno 0,1 Hz", "bar"),
        SignalInfo(3, "Dente di sega 0,5 Hz", "°C"), SignalInfo(4, "Onda quadra 0,25 Hz", ""))

    override fun subscribe(resumeAfterSeq: Long): Flow<SampleBatch> = flow {
        val t0 = System.nanoTime()
        var seq = resumeAfterSeq
        while (currentCoroutineContext().isActive) {
            val t = (System.nanoTime() - t0) / 1e9
            val nowUs = System.currentTimeMillis() * 1000
            val values = doubleArrayOf(sin(2 * PI * t), 5 + 3 * sin(2 * PI * 0.1 * t),
                20 + 10 * ((t * 0.5) % 1.0), if ((t * 0.5).toLong() % 2 == 0L) 0.0 else 1.0)
            emit(SampleBatch.newBuilder().setSeq(++seq).apply {
                values.forEachIndexed { i, v ->
                    addSamples(Sample.newBuilder().setSignalId(i + 1).setTimestampUs(nowUs).setValue(v))
                }
            }.build())
            delay(50)
        }
    }

    override fun close() = Unit
}
