using System.Buffers.Binary;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace SpaceLinkBridge.Space;

public sealed record SpaceSignal(string NodeId, string Name, string Unit);

/// <summary>Position in Space's Live FIFO of one signal: the identity of the buffer and the last sequence received.</summary>
public sealed record LiveCursor(long Buffer, long Seq);

public sealed record LivePoint(string NodeId, double TimeMs, double Value, byte Quality, long Seq);

public sealed record LivePoll(List<LivePoint> Points, List<string> NodeIds);

public sealed class SpaceUnavailableException(string message, Exception? inner = null) : Exception(message, inner);

/// <summary>
/// Reads the Live data of a running Space Fieldbus Analyzer through the same local API its own web page uses.
/// Only reads: GET /api/status, GET /api/config, GET / (page token) and POST /api/live/samples (buffer read).
/// Nothing that changes the acquisition, the selection or a recording is ever called.
/// </summary>
public sealed class SpaceClient : IDisposable
{
    public const int FirstPort = 5000, PortCount = 20;
    static readonly Regex Token = new("<meta\\s+content=\"([^\"]+)\"\\s+name=\"opcua-token\"", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    readonly HttpClient http;
    string? token;

    public string BaseUrl { get; }
    public string Version { get; private set; } = "";

    public SpaceClient(string baseUrl, HttpMessageHandler? handler = null)
    {
        var uri = new Uri(baseUrl.TrimEnd('/'));
        // Space answers only to its own machine: a remote address could never work and must not be probed.
        if (!(uri.IsLoopback || uri.Host == "localhost")) throw new ArgumentException("Space è raggiungibile solo dallo stesso PC (127.0.0.1)");
        BaseUrl = uri.GetLeftPart(UriPartial.Authority);
        http = handler == null ? new HttpClient(new SocketsHttpHandler { UseProxy = false }) : new HttpClient(handler);
        http.Timeout = TimeSpan.FromSeconds(10);
    }

    /// <summary>First Space instance answering on 127.0.0.1:5000-5019 (its default port range), or null.</summary>
    public static async Task<string?> DiscoverAsync(CancellationToken ct, int firstPort = FirstPort, int count = PortCount)
    {
        using var probe = new HttpClient(new SocketsHttpHandler { UseProxy = false }) { Timeout = TimeSpan.FromMilliseconds(700) };
        for (var port = firstPort; port < firstPort + count; port++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                var url = $"http://127.0.0.1:{port}";
                using var doc = JsonDocument.Parse(await probe.GetStringAsync(url + "/api/status", ct));
                if (doc.RootElement.TryGetProperty("app", out var app) && app.GetString() == "Space Fieldbus Analyzer") return url;
            }
            catch (Exception exc) when ((exc is HttpRequestException or TaskCanceledException or JsonException or IOException) && !ct.IsCancellationRequested) { }
        }
        return null;
    }

    public async Task<string> StatusAsync(CancellationToken ct)
    {
        using var doc = JsonDocument.Parse(await Get("/api/status", ct));
        var root = doc.RootElement;
        if (!root.TryGetProperty("app", out var app) || app.GetString() != "Space Fieldbus Analyzer")
            throw new SpaceUnavailableException("L'indirizzo non è Space Fieldbus Analyzer");
        Version = root.TryGetProperty("version", out var v) ? v.GetString() ?? "" : "";
        return root.TryGetProperty("state", out var state) ? state.GetString() ?? "" : "";
    }

    /// <summary>Names and units of the signals: selection of the workspace, saved selection and calculated series.</summary>
    public async Task<Dictionary<string, SpaceSignal>> SignalsAsync(CancellationToken ct)
    {
        using var doc = JsonDocument.Parse(await Get("/api/config", ct));
        var result = new Dictionary<string, SpaceSignal>();
        var root = doc.RootElement;
        void Nodes(JsonElement array)
        {
            if (array.ValueKind != JsonValueKind.Array) return;
            foreach (var node in array.EnumerateArray())
            {
                if (node.ValueKind != JsonValueKind.Object || Str(node, "nodeid") is not { Length: > 0 } id) continue;
                result[id] = new SpaceSignal(id, Str(node, "display_name") is { Length: > 0 } n ? n : id, Str(node, "unit") ?? "");
            }
        }
        if (root.TryGetProperty("selected_nodes", out var saved)) Nodes(saved);
        if (root.TryGetProperty("workspace", out var workspace) && workspace.ValueKind == JsonValueKind.Object && workspace.TryGetProperty("selected_nodes", out var ws)) Nodes(ws);
        if (root.TryGetProperty("calculations", out var calc) && calc.ValueKind == JsonValueKind.Object && calc.TryGetProperty("formulas", out var formulas) && formulas.ValueKind == JsonValueKind.Array)
            foreach (var f in formulas.EnumerateArray())
                if (f.ValueKind == JsonValueKind.Object && Str(f, "nodeid") is { Length: > 0 } id)
                    result[id] = new SpaceSignal(id, Str(f, "name") is { Length: > 0 } n ? n : id, Str(f, "unit") is { Length: > 0 } u ? u : Str(f, "unit_hint") ?? "");
        return result;
    }

    static string? Str(JsonElement e, string name) => e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    /// <summary>
    /// New samples of every Live signal after the given cursors. A signal seen for the first time (or whose buffer
    /// was replaced, e.g. after «Pulisci») only sets its cursor: the history is not replayed to the phones.
    /// </summary>
    public async Task<LivePoll> PollAsync(Dictionary<string, LiveCursor> cursors, CancellationToken ct)
    {
        var body = new StringBuilder("{\"cursors\":{");
        var first = true;
        foreach (var (nid, c) in cursors)
        {
            if (!first) body.Append(',');
            first = false;
            body.Append(JsonSerializer.Serialize(nid)).Append(":{\"buffer\":").Append(c.Buffer).Append(",\"seq\":").Append(c.Seq).Append('}');
        }
        body.Append("}}");
        for (var attempt = 0; ; attempt++)
        {
            await EnsureToken(ct);
            using var request = new HttpRequestMessage(HttpMethod.Post, BaseUrl + "/api/live/samples") { Content = new StringContent(body.ToString(), Encoding.UTF8, "application/json") };
            request.Headers.Add("X-OPCUA-Token", token);
            using var response = await Send(request, ct);
            if (response.StatusCode == HttpStatusCode.Forbidden && attempt == 0) { token = null; continue; }
            if (!response.IsSuccessStatusCode) throw new SpaceUnavailableException($"Space ha risposto {(int)response.StatusCode} a /api/live/samples");
            return Decode(await response.Content.ReadAsByteArrayAsync(ct), cursors);
        }
    }

    /// <summary>Decodes the binary response ("SAS1": JSON header, then per signal Float64 sequence, time, value and Uint8 quality blocks, each padded to 8 bytes).</summary>
    public static LivePoll Decode(byte[] data, Dictionary<string, LiveCursor> cursors)
    {
        if (data.Length < 8 || Encoding.ASCII.GetString(data, 0, 4) != "SAS1") throw new SpaceUnavailableException("Risposta di Space non riconosciuta");
        var headerLength = checked((int)BinaryPrimitives.ReadUInt32LittleEndian(data.AsSpan(4)));
        if (8 + headerLength > data.Length) throw new SpaceUnavailableException("Risposta di Space troncata");
        using var doc = JsonDocument.Parse(data.AsMemory(8, headerLength));
        var root = doc.RootElement;
        var withSeq = root.TryGetProperty("with_seq", out var ws) && ws.ValueKind == JsonValueKind.True;
        static int Align(int n) => n + (8 - n % 8) % 8;
        var offset = Align(8 + headerLength);
        var points = new List<LivePoint>();
        var ids = new List<string>();
        if (!root.TryGetProperty("signals", out var signals) || signals.ValueKind != JsonValueKind.Array) return new LivePoll(points, ids);
        foreach (var signal in signals.EnumerateArray())
        {
            var nid = Str(signal, "nodeid") ?? throw new SpaceUnavailableException("Segnale senza nodeid");
            var n = signal.TryGetProperty("points_returned", out var pr) && pr.TryGetInt32(out var count) ? count : 0;
            var buffer = signal.TryGetProperty("buffer", out var b) && b.TryGetInt64(out var bid) ? bid : 0;
            var reset = signal.TryGetProperty("reset", out var r) && r.ValueKind == JsonValueKind.True;
            var need = (withSeq ? Align(n * 8) : 0) + Align(n * 8) * 2 + Align(n);
            if (n < 0 || offset + need > data.Length) throw new SpaceUnavailableException("Risposta di Space troncata");
            var seq = withSeq ? offset : -1;
            if (withSeq) offset += Align(n * 8);
            var time = offset; offset += Align(n * 8);
            var value = offset; offset += Align(n * 8);
            var quality = offset; offset += Align(n);
            ids.Add(nid);
            var known = cursors.TryGetValue(nid, out var cursor) && cursor.Buffer == buffer && !reset;
            var last = cursor?.Seq ?? 0;
            for (var i = 0; i < n; i++)
            {
                var s = withSeq ? (long)BitConverter.ToDouble(data, seq + i * 8) : last + i + 1;
                if (known) points.Add(new LivePoint(nid, BitConverter.ToDouble(data, time + i * 8), BitConverter.ToDouble(data, value + i * 8), data[quality + i], s));
                last = Math.Max(last, s);
            }
            cursors[nid] = new LiveCursor(buffer, known || n == 0 ? Math.Max(last, cursor?.Seq ?? 0) : last);
        }
        // Signals no longer in Space (selection changed): forget their cursors.
        foreach (var gone in cursors.Keys.Where(k => !ids.Contains(k)).ToList()) cursors.Remove(gone);
        return new LivePoll(points, ids);
    }

    async Task EnsureToken(CancellationToken ct)
    {
        if (token != null) return;
        var html = await Get("/", ct);
        var m = Token.Match(html);
        token = m.Success ? m.Groups[1].Value : throw new SpaceUnavailableException("Pagina di Space senza token");
    }

    async Task<string> Get(string path, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, BaseUrl + path);
        using var response = await Send(request, ct);
        if (!response.IsSuccessStatusCode) throw new SpaceUnavailableException($"Space ha risposto {(int)response.StatusCode} a {path}");
        return await response.Content.ReadAsStringAsync(ct);
    }

    async Task<HttpResponseMessage> Send(HttpRequestMessage request, CancellationToken ct)
    {
        try { return await http.SendAsync(request, ct); }
        catch (Exception exc) when ((exc is HttpRequestException or TaskCanceledException or IOException) && !ct.IsCancellationRequested)
        { throw new SpaceUnavailableException("Space non risponde: " + exc.Message, exc); }
    }

    public void Dispose() => http.Dispose();
}
