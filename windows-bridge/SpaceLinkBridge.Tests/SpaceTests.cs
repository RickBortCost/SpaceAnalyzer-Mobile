using System.Net;
using Grpc.Core;
using Grpc.Net.Client;
using SpaceAnalyzer.App.MobileLink.Proto;
using SpaceLinkBridge.Link;
using SpaceLinkBridge.Space;
using Xunit;

namespace SpaceLinkBridge.Tests;

public class SpaceClientTests
{
    static Dictionary<string, LiveCursor> NoCursors() => new();

    [Fact]
    public async Task FirstPollOnlySetsCursorsThenOnlyNewPointsAreReturned()
    {
        await using var space = await FakeSpace.StartAsync();
        for (var i = 0; i < 5; i++) space.Add("ns=2;s=Temp", 1_700_000_000_000 + i, 20 + i);
        using var client = new SpaceClient(space.Url);
        var cursors = NoCursors();
        var first = await client.PollAsync(cursors, CancellationToken.None);
        Assert.Empty(first.Points);                          // history is not replayed to the phones
        Assert.Equal(new[] { "ns=2;s=Temp" }, first.NodeIds);
        Assert.Equal(5, cursors["ns=2;s=Temp"].Seq);

        space.Add("ns=2;s=Temp", 1_700_000_000_005, 25, quality: 1);
        space.Add("ns=2;s=Temp", 1_700_000_000_006, 26);
        var next = await client.PollAsync(cursors, CancellationToken.None);
        Assert.Equal(new[] { 25.0, 26.0 }, next.Points.Select(p => p.Value));
        Assert.Equal(new byte[] { 1, 0 }, next.Points.Select(p => p.Quality));
        Assert.Equal(new long[] { 6, 7 }, next.Points.Select(p => p.Seq));
        Assert.Empty((await client.PollAsync(cursors, CancellationToken.None)).Points);
    }

    [Fact]
    public async Task ClearedBufferStartsOverWithoutReplayingOrLosingTheNextPoints()
    {
        await using var space = await FakeSpace.StartAsync();
        space.Add("A", 1_700_000_000_000, 1);
        using var client = new SpaceClient(space.Url);
        var cursors = NoCursors();
        await client.PollAsync(cursors, CancellationToken.None);
        space.ClearBuffers();
        space.Add("A", 1_700_000_001_000, 7);
        space.Add("A", 1_700_000_001_001, 8);
        var reset = await client.PollAsync(cursors, CancellationToken.None);
        Assert.Empty(reset.Points);                           // new FIFO: baseline only
        space.Add("A", 1_700_000_001_002, 9);
        Assert.Equal(new[] { 9.0 }, (await client.PollAsync(cursors, CancellationToken.None)).Points.Select(p => p.Value));
    }

    [Fact]
    public async Task ExpiredPageTokenIsRefreshedOnce()
    {
        await using var space = await FakeSpace.StartAsync();
        space.Add("A", 1_700_000_000_000, 1);
        using var client = new SpaceClient(space.Url);
        var cursors = NoCursors();
        await client.PollAsync(cursors, CancellationToken.None);
        space.RotateToken("new-token");                       // Space restarted: old token is refused
        space.Add("A", 1_700_000_000_001, 2);
        Assert.Equal(new[] { 2.0 }, (await client.PollAsync(cursors, CancellationToken.None)).Points.Select(p => p.Value));
    }

    [Fact]
    public async Task OnlyReadRoutesAreEverCalled()
    {
        await using var space = await FakeSpace.StartAsync();
        space.Add("A", 1_700_000_000_000, 1);
        using var client = new SpaceClient(space.Url);
        await client.StatusAsync(CancellationToken.None);
        await client.SignalsAsync(CancellationToken.None);
        await client.PollAsync(NoCursors(), CancellationToken.None);
        Assert.All(space.Requests.Distinct(), r => Assert.Contains(r, new[] { "GET /api/status", "GET /api/config", "GET /", "POST /api/live/samples" }));
    }

    [Fact]
    public async Task NamesComeFromSelectionWorkspaceAndCalculations()
    {
        await using var space = await FakeSpace.StartAsync();
        using var client = new SpaceClient(space.Url);
        var names = await client.SignalsAsync(CancellationToken.None);
        Assert.Equal("Temperatura", names["ns=2;s=Temp"].Name);
        Assert.Equal("Pressione", names["ns=2;s=Press"].Name);
        Assert.Equal(("Potenza", "kW"), (names["calc:p"].Name, names["calc:p"].Unit));
    }

    [Fact]
    public void RefusesNonLocalAddresses() => Assert.Throws<ArgumentException>(() => new SpaceClient("http://192.168.1.10:5000"));

    [Fact]
    public async Task DiscoversSpaceByItsStatusAnswer()
    {
        await using var space = await FakeSpace.StartAsync();
        Assert.Equal(space.Url, await SpaceClient.DiscoverAsync(CancellationToken.None, space.Port - 3, 8));
        Assert.Null(await SpaceClient.DiscoverAsync(CancellationToken.None, 1, 3));
    }

    [Fact]
    public void DecodeRejectsForeignOrTruncatedData()
    {
        Assert.Throws<SpaceUnavailableException>(() => SpaceClient.Decode(new byte[] { 1, 2, 3 }, NoCursors()));
        Assert.Throws<SpaceUnavailableException>(() => SpaceClient.Decode(System.Text.Encoding.ASCII.GetBytes("XXXX0000"), NoCursors()));
        var good = FakeSpace.Payload(new() { new Dictionary<string, object> { ["nodeid"] = "A", ["buffer"] = 1, ["reset"] = false, ["points_returned"] = 3 } },
            new() { (new[] { 1.0, 2, 3 }, new[] { 1.0, 2, 3 }, new[] { 1.0, 2, 3 }, new byte[] { 0, 0, 0 }) });
        Assert.Throws<SpaceUnavailableException>(() => SpaceClient.Decode(good[..^16], NoCursors()));
    }

    [Fact]
    public void QualityBytesBecomeOpcUaStyleCodes() =>
        Assert.Equal(new uint[] { 0, 0x40000000, 0x80000000 }, new byte[] { 0, 1, 2 }.Select(SpaceFeeder.QualityCode));
}

public class FeederEndToEndTests : IAsyncLifetime
{
    readonly TempDir dir = new();
    MobileLinkManager manager = null!;
    FakeSpace space = null!;
    CancellationTokenSource stop = null!;
    Task feeding = Task.CompletedTask;
    SpaceFeeder feeder = null!;

    public async Task InitializeAsync()
    {
        manager = new MobileLinkManager(dir.Path, bindAny: false);
        var probe = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0); probe.Start();
        var port = ((IPEndPoint)probe.LocalEndpoint).Port; probe.Stop();
        await manager.SetPortAsync(port);
        await manager.SetEnabledAsync(true);
        space = await FakeSpace.StartAsync();
        stop = new CancellationTokenSource();
        feeder = new SpaceFeeder(manager.Hub, space.Url, 20, _ => { });
        feeding = feeder.RunAsync(stop.Token);
    }

    public async Task DisposeAsync()
    {
        stop.Cancel();
        try { await feeding; } catch (OperationCanceledException) { }
        await space.DisposeAsync();
        manager.Dispose(); dir.Dispose();
    }

    GrpcChannel Channel() => GrpcChannel.ForAddress($"https://127.0.0.1:{manager.BoundPort}", new GrpcChannelOptions
    {
        HttpHandler = new SocketsHttpHandler { SslOptions = { RemoteCertificateValidationCallback = (_, cert, _, _) => cert != null && MobileLinkCertificate.Fingerprint(cert) == manager.Fingerprint } },
    });

    Metadata Code() => new() { { AccessGate.HeaderName, manager.Status()["access_code"]!.GetValue<string>() } };

    static async Task Until(Func<bool> condition, string what)
    {
        for (var i = 0; i < 300 && !condition(); i++) await Task.Delay(20);
        Assert.True(condition(), "timeout: " + what);
    }

    [Fact]
    public async Task SpaceSamplesReachThePhoneWithNamesQualityAndTimestamps()
    {
        space.Add("ns=2;s=Temp", 1_700_000_000_000, 1);               // baseline
        await Until(() => manager.Hub.Signals().Count == 1, "signals");
        using var channel = Channel();
        var client = new SfaLink.SfaLinkClient(channel);
        var signals = (await client.ListSignalsAsync(new ListSignalsRequest(), Code())).Signals;
        Assert.Equal("Temperatura", signals.Single().Name);

        using var call = client.Subscribe(new SubscribeRequest(), Code());
        await Until(() => manager.Hub.SubscriberCount == 1, "subscriber");
        space.Add("ns=2;s=Temp", 1_700_000_000_123.5, 21.5);
        space.Add("ns=2;s=Temp", 1_700_000_000_124, 22.5, quality: 2);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var samples = new List<Sample>();
        await foreach (var b in call.ResponseStream.ReadAllAsync(timeout.Token)) { samples.AddRange(b.Samples); if (samples.Count >= 2) break; }
        Assert.Equal(new[] { 21.5, 22.5 }, samples.Select(s => s.Value));
        Assert.Equal(new uint[] { 0, 0x80000000 }, samples.Select(s => s.Quality));
        Assert.Equal(1_700_000_000_123_500L, samples[0].TimestampUs);
        Assert.True(feeder.Status.Connected);
        Assert.Equal("3.14.1", feeder.Status.Version);
    }

    [Fact]
    public async Task SelectionChangeInSpaceReconnectsThePhonesToReloadTheSignalList()
    {
        space.Add("ns=2;s=Temp", 1_700_000_000_000, 1);
        await Until(() => manager.Hub.Signals().Count == 1, "first signal");
        using var channel = Channel();
        var client = new SfaLink.SfaLinkClient(channel);
        using var call = client.Subscribe(new SubscribeRequest(), Code());
        await Until(() => manager.Hub.SubscriberCount == 1, "subscriber");
        space.Add("ns=2;s=Press", 1_700_000_000_000, 5);              // a second signal appears in Space
        var ended = await Assert.ThrowsAsync<RpcException>(async () =>
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await foreach (var _ in call.ResponseStream.ReadAllAsync(timeout.Token)) { }
        });
        Assert.Equal(StatusCode.Unavailable, ended.StatusCode);
        Assert.Equal(2, manager.Hub.Signals().Count);
    }

    [Fact]
    public async Task NamesSavedBySpaceAfterTheSelectionChangeAreAdoptedWithinSeconds()
    {
        space.Config = new { selected_nodes = Array.Empty<object>() };
        space.Add("ns=2;s=New", 1_700_000_000_000, 1);
        await Until(() => manager.Hub.Signals().Count == 1, "signal");
        Assert.Equal("ns=2;s=New", manager.Hub.Signals()[0].Name);          // the workspace is not saved yet
        space.Config = new { workspace = new { selected_nodes = new[] { new { nodeid = "ns=2;s=New", display_name = "Nuovo" } } } };
        await Until(() => manager.Hub.Signals()[0].Name == "Nuovo", "name");
    }

    [Fact]
    public async Task BridgeSurvivesSpaceGoingAwayAndComingBack()
    {
        space.Add("ns=2;s=Temp", 1_700_000_000_000, 1);
        await Until(() => feeder.Status.Connected, "connected");
        var port = space.Port;
        await space.DisposeAsync();
        await Until(() => !feeder.Status.Connected, "disconnected");
        Assert.NotEmpty(feeder.Status.Error);
        space = await FakeSpace.StartAsync(port);
        space.Add("ns=2;s=Temp", 1_700_000_001_000, 2);
        await Until(() => feeder.Status.Connected, "reconnected");
    }
}
