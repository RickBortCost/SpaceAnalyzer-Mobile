package it.spaceanalyzer.mobile.link

import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import it.spaceanalyzer.link.v1.HelloReply
import it.spaceanalyzer.link.v1.HelloRequest
import it.spaceanalyzer.link.v1.ListSignalsReply
import it.spaceanalyzer.link.v1.ListSignalsRequest
import it.spaceanalyzer.link.v1.Sample
import it.spaceanalyzer.link.v1.SampleBatch
import it.spaceanalyzer.link.v1.SfaLinkGrpc
import it.spaceanalyzer.link.v1.Signal
import it.spaceanalyzer.link.v1.SubscribeRequest
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real TLS + gRPC round trip between the production client and a minimal server. */
class LinkIntegrationTest {
    private lateinit var server: Server
    private val resumeRequests = mutableListOf<Long>()

    @Before fun start() {
        val impl = object : SfaLinkGrpc.SfaLinkImplBase() {
            override fun hello(r: HelloRequest, o: StreamObserver<HelloReply>) {
                o.onNext(HelloReply.newBuilder().setProtocolVersion(1).setServerName("PC-Test").setAcquisitionName("Acq").build()); o.onCompleted()
            }
            override fun listSignals(r: ListSignalsRequest, o: StreamObserver<ListSignalsReply>) {
                o.onNext(ListSignalsReply.newBuilder().addSignals(Signal.newBuilder().setId(7).setName("Temp").setUnit("°C")).build()); o.onCompleted()
            }
            override fun subscribe(r: SubscribeRequest, o: StreamObserver<SampleBatch>) {
                resumeRequests += r.resumeAfterSeq
                for (i in 1..5) o.onNext(SampleBatch.newBuilder().setSeq(r.resumeAfterSeq + i)
                    .addSamples(Sample.newBuilder().setSignalId(7).setTimestampUs(i * 1000L).setValue(i * 1.5)).build())
                o.onCompleted()
            }
        }
        val auth = object : ServerInterceptor {
            override fun <A, B> interceptCall(call: ServerCall<A, B>, h: Metadata, next: ServerCallHandler<A, B>): ServerCall.Listener<A> {
                if (h.get(SfaLinkClient.ACCESS_CODE_KEY) != "123456") {
                    call.close(Status.UNAUTHENTICATED.withDescription("codice"), Metadata())
                    return object : ServerCall.Listener<A>() {}
                }
                return next.startCall(call, h)
            }
        }
        server = NettyServerBuilder.forPort(0)
            .sslContext(GrpcSslContexts.forServer(TestCert.certFile, TestCert.keyFile).build())
            .intercept(auth).addService(impl).build().start()
    }

    @After fun stop() { if (::server.isInitialized) server.shutdownNow() }

    private fun client(pin: String = TestCert.fingerprint, code: String = "123456") =
        SfaLinkClient("127.0.0.1", server.port, pin, code)

    @Test fun pairingReadsTheServerFingerprint() {
        assertEquals(TestCert.fingerprint, fetchServerFingerprint(InetSocketAddress("127.0.0.1", server.port), 3000))
    }

    @Test fun helloSignalsAndStreamWithResume() = runBlocking {
        client().use { c ->
            withTimeout(10_000) {
                assertEquals("PC-Test", c.hello().serverName)
                assertEquals(listOf(SignalInfo(7, "Temp", "°C")), c.listSignals())
                val batches = c.subscribe(10).toList()
                assertEquals(listOf(11L, 12L, 13L, 14L, 15L), batches.map { it.seq })
                assertEquals(7.5, batches.last().samplesList[0].value, 0.0)
            }
        }
        assertEquals(listOf(10L), resumeRequests)
    }

    @Test fun wrongAccessCodeIsFatal() = runBlocking {
        val failure = client(code = "000000").use { c -> runCatching { withTimeout(10_000) { c.hello() } }.exceptionOrNull() }
        assertTrue("atteso errore, ottenuto $failure", failure != null && isFatal(failure))
    }

    @Test fun wrongPinIsFatalAndNothingIsSent() = runBlocking {
        val failure = client(pin = "00:" + TestCert.fingerprint.drop(3)).use { c ->
            runCatching { withTimeout(10_000) { c.hello() } }.exceptionOrNull()
        }
        assertTrue("atteso errore, ottenuto $failure", failure != null && isFatal(failure))
        assertTrue(describe(failure!!).contains("Certificato"))
    }
}
