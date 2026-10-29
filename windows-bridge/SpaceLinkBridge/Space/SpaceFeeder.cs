using SpaceLinkBridge.Link;

namespace SpaceLinkBridge.Space;

public sealed record FeederStatus(bool Connected, string Url, string Version, string State, int Signals, double SamplesPerSecond, string Error);

/// <summary>Polls Space and feeds the hub. Reconnects by itself when Space is started, closed or restarted.</summary>
public sealed class SpaceFeeder(MobileLinkHub hub, string? fixedUrl, int pollMs, Action<string> log)
{
    readonly object gate = new();
    FeederStatus status = new(false, "", "", "", 0, 0, "Ricerca di Space…");
    public FeederStatus Status { get { lock (gate) return status; } }
    void Set(FeederStatus s) { lock (gate) status = s; }

    public async Task RunAsync(CancellationToken ct)
    {
        SpaceClient? client = null;
        var cursors = new Dictionary<string, LiveCursor>();
        List<SpaceSignal> published = new();
        HashSet<string> knownIds = new();
        var namesAt = DateTime.MinValue;
        var window = new Queue<(DateTime At, int Count)>();
        var state = "";
        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (client == null)
                {
                    var url = fixedUrl ?? await SpaceClient.DiscoverAsync(ct);
                    if (url == null) { Set(new(false, "", "", "", published.Count, 0, "Space non trovato su 127.0.0.1:5000-5019: avviare Space")); await Task.Delay(2000, ct); continue; }
                    client = new SpaceClient(url);
                    state = await client.StatusAsync(ct);
                    cursors.Clear(); knownIds.Clear(); namesAt = DateTime.MinValue;
                    log($"Collegato a Space {client.Version} ({client.BaseUrl})");
                }
                var poll = await client.PollAsync(cursors, ct);
                var idsNow = poll.NodeIds.ToHashSet();
                if (!idsNow.SetEquals(knownIds) || DateTime.UtcNow - namesAt > TimeSpan.FromSeconds(10))
                {
                    var names = await client.SignalsAsync(ct);
                    var list = poll.NodeIds.Select(id => names.TryGetValue(id, out var s) ? s : new SpaceSignal(id, id, "")).ToList();
                    if (!list.SequenceEqual(published))
                    {
                        hub.SetSignals(list.Select(s => (s.NodeId, s.Name, s.Unit)));
                        published = list;
                        log($"Segnali disponibili: {list.Count}");
                    }
                    // Space saves the workspace (with the display names) about a second after a selection change:
                    // after a change in the list, read the names again soon instead of waiting the full interval.
                    var changed = !idsNow.SetEquals(knownIds);
                    knownIds = idsNow; namesAt = changed ? DateTime.UtcNow - TimeSpan.FromSeconds(8) : DateTime.UtcNow;
                }
                if (poll.Points.Count > 0)
                {
                    var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() * 1000;
                    hub.Publish(poll.Points.Select(p => new LinkSample(p.NodeId, p.Value, QualityCode(p.Quality),
                        p.TimeMs >= 1e12 ? (long)(p.TimeMs * 1000) : now)).ToList());
                }
                var at = DateTime.UtcNow;
                window.Enqueue((at, poll.Points.Count));
                while (window.Count > 0 && at - window.Peek().At > TimeSpan.FromSeconds(5)) window.Dequeue();
                var span = Math.Max(1, (at - window.Peek().At).TotalSeconds);
                Set(new(true, client.BaseUrl, client.Version, state, published.Count, window.Sum(w => w.Count) / span, ""));
                await Task.Delay(pollMs, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (Exception exc) when (exc is SpaceUnavailableException or HttpRequestException or IOException or System.Text.Json.JsonException)
            {
                if (client != null) log("Collegamento a Space perso: " + exc.Message);
                client?.Dispose(); client = null;
                Set(new(false, "", "", "", published.Count, 0, exc.Message));
                try { await Task.Delay(2000, ct); } catch (OperationCanceledException) { break; }
            }
        }
        client?.Dispose();
    }

    /// <summary>Space quality byte (0 Good, 1 Uncertain, 2 Bad) as an OPC UA style status code.</summary>
    public static uint QualityCode(byte quality) => quality switch { 0 => 0u, 1 => 0x40000000u, _ => 0x80000000u };
}
