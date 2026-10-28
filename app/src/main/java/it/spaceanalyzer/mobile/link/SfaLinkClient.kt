package it.spaceanalyzer.mobile.link

import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import io.grpc.stub.MetadataUtils
import it.spaceanalyzer.link.v1.HelloReply
import it.spaceanalyzer.link.v1.HelloRequest
import it.spaceanalyzer.link.v1.ListSignalsRequest
import it.spaceanalyzer.link.v1.SampleBatch
import it.spaceanalyzer.link.v1.SfaLinkGrpc
import it.spaceanalyzer.link.v1.SubscribeRequest
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

const val PROTOCOL_VERSION = 1

/** Everything the session needs from the PC. Deliberately has no operation that changes the PC. */
interface LinkClient : AutoCloseable {
    suspend fun hello(): HelloReply
    suspend fun listSignals(): List<SignalInfo>
    fun subscribe(resumeAfterSeq: Long): Flow<SampleBatch>
}

class SfaLinkClient(host: String, port: Int, pinnedFingerprint: String, accessCode: String) : LinkClient {
    private val channel: ManagedChannel = OkHttpChannelBuilder.forAddress(host, port)
        .useTransportSecurity()
        .sslSocketFactory(pinnedSocketFactory(pinnedFingerprint))
        .hostnameVerifier(PinnedHostnameVerifier)
        // A pulled USB cable produces no TCP reset: detect it with HTTP/2 pings.
        .keepAliveTime(10, TimeUnit.SECONDS)
        .keepAliveTimeout(5, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .build()
    private val headers = Metadata().apply { put(ACCESS_CODE_KEY, accessCode) }
    private val blocking = SfaLinkGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    private val async = SfaLinkGrpc.newStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))

    override suspend fun hello(): HelloReply = withContext(Dispatchers.IO) {
        blocking.withDeadlineAfter(RPC_TIMEOUT_S, TimeUnit.SECONDS).hello(
            HelloRequest.newBuilder().setProtocolVersion(PROTOCOL_VERSION).setClientName("SpaceAnalyzerMobile").build())
    }

    override suspend fun listSignals(): List<SignalInfo> = withContext(Dispatchers.IO) {
        blocking.withDeadlineAfter(RPC_TIMEOUT_S, TimeUnit.SECONDS).listSignals(ListSignalsRequest.getDefaultInstance())
            .signalsList.map { SignalInfo(it.id, it.name, it.unit) }
    }

    override fun subscribe(resumeAfterSeq: Long): Flow<SampleBatch> = callbackFlow {
        var call: ClientCallStreamObserver<SubscribeRequest>? = null
        val observer = object : ClientResponseObserver<SubscribeRequest, SampleBatch> {
            override fun beforeStart(requestStream: ClientCallStreamObserver<SubscribeRequest>) { call = requestStream }
            // Blocking here propagates back-pressure to the HTTP/2 window.
            override fun onNext(value: SampleBatch) { trySendBlocking(value) }
            override fun onError(t: Throwable) { close(t) }
            override fun onCompleted() { close() }
        }
        async.withWaitForReady().subscribe(
            SubscribeRequest.newBuilder().setResumeAfterSeq(resumeAfterSeq.coerceAtLeast(0)).build(), observer)
        awaitClose { call?.cancel("Sottoscrizione chiusa dal client", null) }
    }

    override fun close() {
        channel.shutdownNow()
        channel.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        const val RPC_TIMEOUT_S = 8L
        val ACCESS_CODE_KEY: Metadata.Key<String> = Metadata.Key.of("x-sfa-access-code", Metadata.ASCII_STRING_MARSHALLER)
    }
}

/** True when retrying cannot help and the user must act (wrong code, wrong certificate, wrong version). */
fun isFatal(t: Throwable): Boolean {
    var c: Throwable? = t
    while (c != null) { if (c is CertificateException) return true; c = c.cause }
    val status = when (t) {
        is StatusRuntimeException -> t.status
        is StatusException -> t.status
        else -> return t is IllegalStateException
    }
    return status.code in setOf(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED,
        Status.Code.UNIMPLEMENTED, Status.Code.FAILED_PRECONDITION, Status.Code.INVALID_ARGUMENT)
}

fun describe(t: Throwable): String {
    var c: Throwable? = t
    while (c != null) {
        if (c is CertificateException) return "Certificato del PC non corrispondente: ricollegare e confermare l’impronta"
        c = c.cause
    }
    val status = when (t) {
        is StatusRuntimeException -> t.status
        is StatusException -> t.status
        else -> return t.message ?: t.javaClass.simpleName
    }
    return when (status.code) {
        Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED -> "Codice di accesso rifiutato dal PC"
        Status.Code.UNAVAILABLE -> "PC non raggiungibile"
        Status.Code.DEADLINE_EXCEEDED -> "Timeout del PC"
        else -> "${status.code}: ${status.description.orEmpty().take(120)}"
    }
}
