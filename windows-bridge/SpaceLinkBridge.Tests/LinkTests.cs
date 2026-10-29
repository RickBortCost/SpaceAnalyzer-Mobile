using System.Net;
using System.Security.Cryptography.X509Certificates;
using Grpc.Core;
using Grpc.Net.Client;
using SpaceLinkBridge.Link;
using SpaceAnalyzer.App.MobileLink.Proto;
using Xunit;
using ProtoSample = SpaceAnalyzer.App.MobileLink.Proto.Sample;

namespace SpaceLinkBridge.Tests;

public sealed class TempDir : IDisposable
{
    public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "slb-tests-" + Guid.NewGuid().ToString("N"));
    public TempDir() => Directory.CreateDirectory(Path);
    public void Dispose() { try { Directory.Delete(Path, true); } catch (IOException) { } }
}

public class MobileLinkHubTests
{
    static LinkSample Row(string nid, double? value, bool good = true, long? status = null, long timestampUs = 1_700_000_000_000_000) =>
        new(nid, value, good ? 0u : (status is long c and > 0 ? unchecked((uint)c) : 0x80000000u), timestampUs);

    static MobileLinkHub NewHub(params string[] nodes)
    {
        var hub = new MobileLinkHub { Active = true };
        hub.SetSignals(nodes.Select(n => (n, "Nome " + n, "bar")));
        return hub;
    }

    static async Task<List<SampleBatch>> Take(MobileLinkHub.Subscription sub, int count)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var list = new List<SampleBatch>();
        await foreach (var b in sub.ReadAllAsync(timeout.Token)) { list.Add(b); if (list.Count == count) break; }
        return list;
    }

    [Fact]
    public async Task PublishesNumberedBatchesWithMicrosecondTimestampsAndQuality()
    {
        var hub = NewHub("ns=2;s=A", "ns=2;s=B");
        using var sub = hub.Subscribe(null, 0);
        hub.Publish(new[] { Row("ns=2;s=A", 1.5), Row("ns=2;s=B", 2.5, good: false, status: 0x80350000) });
        hub.Publish(new[] { Row("ns=2;s=A", 3.5, good: false) });
        var batches = await Take(sub, 2);
        Assert.Equal(new ulong[] { 1, 2 }, batches.Select(b => b.Seq));
        var first = batches[0].Samples;
        Assert.Equal(1_700_000_000_000_000L, first[0].TimestampUs);
        Assert.Equal((1u, 1.5, 0u), (first[0].SignalId, first[0].Value, first[0].Quality));
        Assert.Equal((2u, 2.5, 0x80350000u), (first[1].SignalId, first[1].Value, first[1].Quality));
        Assert.Equal(0x80000000u, batches[1].Samples[0].Quality);
    }

    [Fact]
    public void SkipsNonNumericAndNonFiniteValuesAndDoesNothingWhenInactive()
    {
        var hub = NewHub("A");
        hub.Publish(new[] { Row("A", null), Row("A", double.NaN), Row("A", double.PositiveInfinity) });
        Assert.Equal(0, hub.Sequence);
        hub.Active = false;
        hub.Publish(new[] { Row("A", 1) });
        Assert.Equal(0, hub.Sequence);
    }

    [Fact]
    public async Task ResumeReplaysMissedBatchesWithoutGap()
    {
        var hub = NewHub("A");
        for (var i = 1; i <= 5; i++) hub.Publish(new[] { Row("A", i) });
        using var sub = hub.Subscribe(null, 2);
        var batches = await Take(sub, 3);
        Assert.Equal(new ulong[] { 3, 4, 5 }, batches.Select(b => b.Seq));
        Assert.All(batches, b => Assert.False(b.Gap));
    }

    [Fact]
    public async Task ResumeAtTheCurrentSequenceHasNoGapAndDeliversLiveBatches()
    {
        var hub = NewHub("A");
        hub.Publish(new[] { Row("A", 1) });
        using var sub = hub.Subscribe(null, 1);
        hub.Publish(new[] { Row("A", 2) });
        var b = (await Take(sub, 1))[0];
        Assert.Equal(2UL, b.Seq);
        Assert.False(b.Gap);
    }

    [Fact]
    public async Task GapIsFlaggedWhenTheHistoryNoLongerCoversTheResumePoint()
    {
        var hub = NewHub("A");
        for (var i = 1; i <= MobileLinkHub.HistoryBatches + 10; i++) hub.Publish(new[] { Row("A", i) });
        using var sub = hub.Subscribe(null, 3);
        var first = (await Take(sub, 1))[0];
        Assert.True(first.Gap);
        Assert.True(first.Seq > 4);
    }

    [Fact]
    public async Task GapIsFlaggedWhenThePhoneIsAheadOfTheServerCounter()
    {
        var hub = NewHub("A");
        hub.Publish(new[] { Row("A", 1) });
        using var sub = hub.Subscribe(null, 9999);
        hub.Publish(new[] { Row("A", 2) });
        var b = (await Take(sub, 1))[0];
        Assert.True(b.Gap);
        Assert.Equal(2UL, b.Seq);
    }

    [Fact]
    public async Task FilterKeepsOnlyWantedSignalsAndTheSequenceContiguous()
    {
        var hub = NewHub("A", "B");
        using var sub = hub.Subscribe(new HashSet<uint> { 2 }, 0);
        hub.Publish(new[] { Row("A", 1) });
        hub.Publish(new[] { Row("A", 1), Row("B", 7) });
        var batches = await Take(sub, 2);
        Assert.Equal(new ulong[] { 1, 2 }, batches.Select(b => b.Seq));
        Assert.Empty(batches[0].Samples);
        Assert.Equal(7, batches[1].Samples.Single().Value);
    }

    [Fact]
    public async Task ChangingTheSelectionEndsTheStreamsSoPhonesReloadTheSignalList()
    {
        var hub = NewHub("A");
        using var sub = hub.Subscribe(null, 0);
        hub.SetSignals(new[] { ("A", "Nome A", "bar"), ("B", "Nome B", "") });
        var error = await Assert.ThrowsAsync<RpcException>(() => Take(sub, 1));
        Assert.Equal(StatusCode.Unavailable, error.StatusCode);
        Assert.Equal(new[] { "A", "B" }, hub.Signals().Select(s => s.NodeId));
        Assert.Equal(new uint[] { 1, 2 }, hub.Signals().Select(s => s.Id));
    }

    [Fact]
    public void SameSelectionAgainKeepsTheStreamsAndTheIds()
    {
        var hub = NewHub("A");
        using var sub = hub.Subscribe(null, 0);
        hub.SetSignals(new[] { ("A", "Nome A", "bar") });
        Assert.Equal(1, hub.SubscriberCount);
    }

    [Fact]
    public void SignalIdsStayStableAcrossSelections()
    {
        var hub = NewHub("A", "B");
        hub.SetSignals(new[] { ("B", "Nome B", "bar") });
        Assert.Equal(2u, hub.Signals().Single().Id);
        hub.SetSignals(new[] { ("A", "Nome A", "bar"), ("B", "Nome B", "bar") });
        Assert.Equal(new uint[] { 1, 2 }, hub.Signals().Select(s => s.Id));
    }

    [Fact]
    public void SamplesOfSignalsNotSetAreIgnored()
    {
        var hub = NewHub("A");
        hub.Publish(new[] { Row("unknown", 1) });
        Assert.Equal(0, hub.Sequence);
    }

    [Fact]
    public async Task SlowSubscriberLosesOldestBatchesAndSeesTheJump()
    {
        var hub = NewHub("A");
        using var sub = hub.Subscribe(null, 0);
        for (var i = 1; i <= 4500; i++) hub.Publish(new[] { Row("A", i) });
        var batches = await Take(sub, 4000);
        Assert.Equal(4500UL, batches.Last().Seq);
        Assert.True(batches[0].Seq > 1);
    }

    [Fact]
    public void ResetDropsHistory()
    {
        var hub = NewHub("A");
        hub.Publish(new[] { Row("A", 1) });
        hub.Reset();
        using var sub = hub.Subscribe(null, 1);
        Assert.Equal(1, hub.SubscriberCount);
    }
}

public class MobileLinkSecurityTests
{
    [Fact]
    public void CodeIsCheckedAndLockedOutAfterFiveFailures()
    {
        var gate = new AccessGate("12345678");
        gate.Check("12345678");
        for (var i = 0; i < 5; i++)
            Assert.Equal(StatusCode.Unauthenticated, Assert.Throws<RpcException>(() => gate.Check("00000000")).StatusCode);
        Assert.Equal(StatusCode.ResourceExhausted, Assert.Throws<RpcException>(() => gate.Check("12345678")).StatusCode);
    }

    [Fact]
    public void MissingCodeIsRejected() =>
        Assert.Equal(StatusCode.Unauthenticated, Assert.Throws<RpcException>(() => new AccessGate("12345678").Check(null)).StatusCode);

    [Fact]
    public void FingerprintIsColonSeparatedUppercaseSha256AndCertificateIsReusedFromDisk()
    {
        using var dir = new TempDir();
        using var first = MobileLinkCertificate.LoadOrCreate(dir.Path);
        using var again = MobileLinkCertificate.LoadOrCreate(dir.Path);
        var fp = MobileLinkCertificate.Fingerprint(first);
        Assert.Matches("^([0-9A-F]{2}:){31}[0-9A-F]{2}$", fp);
        Assert.Equal(fp, MobileLinkCertificate.Fingerprint(again));
        Assert.True(first.HasPrivateKey);
    }

    [Fact]
    public void ProtocolHasNoOperationThatChangesSpace() =>
        Assert.Equal(new[] { "Hello", "ListSignals", "Subscribe" }, SfaLink.Descriptor.Methods.Select(m => m.Name).OrderBy(n => n).ToArray());
}

public class MobileLinkServerTests : IAsyncLifetime
{
    readonly TempDir dir = new();
    MobileLinkManager manager = null!;

    public Task InitializeAsync()
    {
        manager = new MobileLinkManager(dir.Path, bindAny: false);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() { manager.Dispose(); dir.Dispose(); return Task.CompletedTask; }

    async Task<int> StartOnFreePort()
    {
        var probe = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        probe.Start(); var port = ((IPEndPoint)probe.LocalEndpoint).Port; probe.Stop();
        await manager.SetPortAsync(port > 1023 ? port : 50000);
        await manager.SetEnabledAsync(true);
        return manager.BoundPort;
    }

    /// <summary>Same trust rule as the phone: only the fingerprint the user confirmed.</summary>
    static GrpcChannel Channel(int port, string pinned) => GrpcChannel.ForAddress($"https://127.0.0.1:{port}", new GrpcChannelOptions
    {
        HttpHandler = new SocketsHttpHandler
        {
            SslOptions = { RemoteCertificateValidationCallback = (_, cert, _, _) => cert != null && MobileLinkCertificate.Fingerprint(cert) == pinned },
        },
    });

    /// <summary>A live-only subscription starts counting when it reaches the server: wait for it before publishing.</summary>
    async Task WaitForSubscribers(int count)
    {
        for (var i = 0; i < 200 && manager.Hub.SubscriberCount < count; i++) await Task.Delay(25);
        Assert.Equal(count, manager.Hub.SubscriberCount);
    }

    static Metadata Code(string code) => new() { { AccessGate.HeaderName, code } };

    [Fact]
    public async Task HelloSignalsAndStreamWorkEndToEndOverPinnedTls()
    {
        var port = await StartOnFreePort();
        manager.Hub.SetSignals(new[] { ("ns=2;s=Temp", "Temperatura", "°C"), ("ns=2;s=Press", "Pressione", "bar") });
        var status = manager.Status();
        using var channel = Channel(port, status["fingerprint"]!.GetValue<string>());
        var client = new SfaLink.SfaLinkClient(channel);
        var headers = Code(status["access_code"]!.GetValue<string>());

        var hello = await client.HelloAsync(new HelloRequest { ProtocolVersion = 1 }, headers);
        Assert.Equal(1u, hello.ProtocolVersion);
        Assert.Equal(Environment.MachineName, hello.ServerName);
        Assert.Equal("Space Fieldbus Analyzer", hello.AcquisitionName);

        var signals = (await client.ListSignalsAsync(new ListSignalsRequest(), headers)).Signals;
        Assert.Equal(new[] { "Temperatura", "Pressione" }, signals.Select(s => s.Name));
        Assert.Equal("°C", signals[0].Unit);

        using var call = client.Subscribe(new SubscribeRequest(), headers);
        await WaitForSubscribers(1);
        for (var i = 1; i <= 3; i++)
            manager.Hub.Publish(new[] { new LinkSample("ns=2;s=Temp", 20 + i, 0, 1_700_000_000_000_000 + i) });
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var received = new List<SampleBatch>();
        await foreach (var b in call.ResponseStream.ReadAllAsync(timeout.Token)) { received.Add(b); if (received.Count == 3) break; }
        Assert.Equal(new ulong[] { 1, 2, 3 }, received.Select(b => b.Seq));
        Assert.Equal(23, received[2].Samples.Single().Value);
        Assert.Equal(1u, received[2].Samples.Single().SignalId);

        // Resume: the phone reconnects and asks for what it missed.
        using var resumed = client.Subscribe(new SubscribeRequest { ResumeAfterSeq = 1 }, headers);
        var replay = new List<SampleBatch>();
        await foreach (var b in resumed.ResponseStream.ReadAllAsync(timeout.Token)) { replay.Add(b); if (replay.Count == 2) break; }
        Assert.Equal(new ulong[] { 2, 3 }, replay.Select(b => b.Seq));
        Assert.All(replay, b => Assert.False(b.Gap));
    }

    [Fact]
    public async Task WrongOrMissingCodeIsUnauthenticatedAndAWrongPinNeverReachesTheService()
    {
        var port = await StartOnFreePort();
        var status = manager.Status();
        var fingerprint = status["fingerprint"]!.GetValue<string>();
        using (var channel = Channel(port, fingerprint))
        {
            var client = new SfaLink.SfaLinkClient(channel);
            var wrong = await Assert.ThrowsAsync<RpcException>(async () => await client.HelloAsync(new HelloRequest(), Code("00000000")));
            Assert.Equal(StatusCode.Unauthenticated, wrong.StatusCode);
            var missing = await Assert.ThrowsAsync<RpcException>(async () => await client.HelloAsync(new HelloRequest()));
            Assert.Equal(StatusCode.Unauthenticated, missing.StatusCode);
            var stream = client.Subscribe(new SubscribeRequest(), Code("00000000"));
            var denied = await Assert.ThrowsAsync<RpcException>(async () => await stream.ResponseStream.MoveNext(CancellationToken.None));
            Assert.Equal(StatusCode.Unauthenticated, denied.StatusCode);
        }
        using var wrongPin = Channel(port, "00:" + fingerprint[3..]);
        await Assert.ThrowsAsync<RpcException>(async () =>
            await new SfaLink.SfaLinkClient(wrongPin).HelloAsync(new HelloRequest(), Code(status["access_code"]!.GetValue<string>())));
    }

    [Fact]
    public async Task RotatingTheCodeEndsOpenStreamsAndLocksOutTheOldCode()
    {
        var port = await StartOnFreePort();
        var status = manager.Status();
        using var channel = Channel(port, status["fingerprint"]!.GetValue<string>());
        var client = new SfaLink.SfaLinkClient(channel);
        var oldCode = status["access_code"]!.GetValue<string>();
        using var call = client.Subscribe(new SubscribeRequest(), Code(oldCode));
        await WaitForSubscribers(1);
        await manager.RotateCodeAsync();
        var ended = await Assert.ThrowsAsync<RpcException>(async () =>
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await foreach (var _ in call.ResponseStream.ReadAllAsync(timeout.Token)) { }
        });
        Assert.Equal(StatusCode.Unavailable, ended.StatusCode);
        var rejected = await Assert.ThrowsAsync<RpcException>(async () => await client.HelloAsync(new HelloRequest(), Code(oldCode)));
        Assert.Equal(StatusCode.Unauthenticated, rejected.StatusCode);
        var newCode = manager.Status()["access_code"]!.GetValue<string>();
        Assert.NotEqual(oldCode, newCode);
        Assert.Equal(1u, (await client.HelloAsync(new HelloRequest(), Code(newCode))).ProtocolVersion);
    }

    [Fact]
    public async Task SettingsSurviveARestartAndStartingIsOptIn()
    {
        Assert.False(manager.Status()["enabled"]!.GetValue<bool>());
        Assert.False(manager.Running);
        await StartOnFreePort();
        var code = manager.Status()["access_code"]!.GetValue<string>();
        var port = manager.Status()["port"]!.GetValue<int>();
        manager.Dispose();
        var reopened = new MobileLinkManager(dir.Path, bindAny: false);
        try
        {
            Assert.True(reopened.Status()["enabled"]!.GetValue<bool>());
            Assert.Equal(code, reopened.Status()["access_code"]!.GetValue<string>());
            Assert.Equal(port, reopened.Status()["port"]!.GetValue<int>());
            await reopened.AutoStartAsync();
            Assert.True(reopened.Running);
            await reopened.SetEnabledAsync(false);
            Assert.False(reopened.Running);
            Assert.False(reopened.Hub.Active);
        }
        finally { reopened.Dispose(); }
        manager = new MobileLinkManager(dir.Path, bindAny: false);
    }

    [Fact]
    public async Task PortInUseIsReportedAsAUserErrorAndLeavesNothingRunning()
    {
        var blocker = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        blocker.Start();
        try
        {
            var busy = ((IPEndPoint)blocker.LocalEndpoint).Port;
            await manager.SetPortAsync(busy);
            await Assert.ThrowsAsync<BridgeException>(() => manager.SetEnabledAsync(true));
            Assert.False(manager.Running);
            Assert.False(manager.Hub.Active);
            Assert.Contains("non disponibile", manager.Status()["error"]!.GetValue<string>());
        }
        finally { blocker.Stop(); }
    }

    [Fact]
    public async Task OnlyTheLinkServiceIsReachableOnThePhonePort()
    {
        var port = await StartOnFreePort();
        using var http = new HttpClient(new SocketsHttpHandler
        {
            SslOptions = { RemoteCertificateValidationCallback = (_, _, _, _) => true },
        }) { DefaultRequestVersion = HttpVersion.Version20, DefaultVersionPolicy = HttpVersionPolicy.RequestVersionExact };
        foreach (var path in new[] { "/", "/api/status", "/static/app.js", "/api/mobile-link" })
            Assert.Equal(HttpStatusCode.NotFound, (await http.GetAsync($"https://127.0.0.1:{port}{path}")).StatusCode);
    }
}
