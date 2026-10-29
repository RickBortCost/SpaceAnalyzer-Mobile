using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace SpaceLinkBridge.Tests;

/// <summary>One point of the fake Live FIFO, with the sequence Space would assign.</summary>
public sealed record FakePoint(double TimeMs, double Value, byte Quality, long Seq);

/// <summary>In-process stand-in for Space's local API: same routes, token rule and binary format as the real service.</summary>
public sealed class FakeSpace : IAsyncDisposable
{
    public const string Token = "tok-123";
    readonly WebApplication app;
    readonly object gate = new();
    readonly Dictionary<string, List<FakePoint>> points = new();
    readonly Dictionary<string, long> bufferIds = new();
    long nextBuffer = 100;
    string token = Token;
    public List<string> Requests { get; } = new();
    public object Config { get; set; } = new
    {
        selected_nodes = new[] { new { nodeid = "ns=2;s=Temp", display_name = "Temperatura" } },
        workspace = new { selected_nodes = new[] { new { nodeid = "ns=2;s=Press", display_name = "Pressione" } } },
        calculations = new { formulas = new[] { new { nodeid = "calc:p", name = "Potenza", unit = "kW", unit_hint = "" } } },
    };
    public int Port { get; private set; }
    public string Url => $"http://127.0.0.1:{Port}";

    public static async Task<FakeSpace> StartAsync(int port = 0) { var s = new FakeSpace(port); await s.app.StartAsync(); s.Port = new Uri(s.app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.First()).Port; return s; }

    FakeSpace(int port)
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(o => o.Listen(System.Net.IPAddress.Loopback, port));
        app = builder.Build();
        app.MapGet("/", ctx => { Log(ctx); ctx.Response.ContentType = "text/html"; return ctx.Response.WriteAsync($"<html><head><meta content=\"{token}\" name=\"opcua-token\"></head></html>"); });
        app.MapGet("/api/status", ctx => { Log(ctx); return ctx.Response.WriteAsJsonAsync(new { app = "Space Fieldbus Analyzer", version = "3.14.1", state = "connected", connected = true }); });
        app.MapGet("/api/config", ctx => { Log(ctx); return ctx.Response.WriteAsJsonAsync(Config); });
        app.MapPost("/api/live/samples", async ctx =>
        {
            Log(ctx);
            if (ctx.Request.Headers["X-OPCUA-Token"] != token) { ctx.Response.StatusCode = 403; await ctx.Response.WriteAsJsonAsync(new { error = "Pagina scaduta: ricaricare l'interfaccia" }); return; }
            using var doc = await JsonDocument.ParseAsync(ctx.Request.Body);
            var cursors = doc.RootElement.GetProperty("cursors");
            byte[] body;
            lock (gate) body = Encode(cursors);
            ctx.Response.ContentType = "application/x-space-analyzer-samples";
            await ctx.Response.Body.WriteAsync(body);
        });
    }

    void Log(HttpContext ctx) { lock (gate) Requests.Add(ctx.Request.Method + " " + ctx.Request.Path); }

    /// <summary>Adds points to a signal and returns their sequences (continuing the FIFO).</summary>
    public void Add(string nodeId, double timeMs, double value, byte quality = 0)
    {
        lock (gate)
        {
            if (!points.TryGetValue(nodeId, out var list)) { points[nodeId] = list = new(); bufferIds[nodeId] = nextBuffer++; }
            list.Add(new FakePoint(timeMs, value, quality, (list.Count == 0 ? 0 : list[^1].Seq) + 1));
        }
    }

    /// <summary>«Pulisci»: the FIFO is replaced, so clients mirroring the old one must start over.</summary>
    public void ClearBuffers() { lock (gate) { foreach (var k in points.Keys.ToList()) { points[k] = new(); bufferIds[k] = nextBuffer++; } } }

    public void RotateToken(string value) { lock (gate) token = value; }

    byte[] Encode(JsonElement cursors)
    {
        var header = new List<object>();
        var blocks = new List<(double[] s, double[] t, double[] v, byte[] q)>();
        foreach (var (nid, list) in points)
        {
            var same = cursors.TryGetProperty(nid, out var c) && c.GetProperty("buffer").GetInt64() == bufferIds[nid];
            var after = same ? c.GetProperty("seq").GetInt64() : 0;
            var take = list.Where(p => p.Seq > after).ToList();
            header.Add(new Dictionary<string, object> { ["nodeid"] = nid, ["buffer"] = bufferIds[nid], ["reset"] = !same, ["drop_last"] = false, ["gap"] = false, ["first_seq"] = list.Count == 0 ? 0 : list[0].Seq, ["points_returned"] = take.Count });
            blocks.Add((take.Select(p => (double)p.Seq).ToArray(), take.Select(p => p.TimeMs).ToArray(), take.Select(p => p.Value).ToArray(), take.Select(p => p.Quality).ToArray()));
        }
        return Payload(header, blocks);
    }

    /// <summary>The "SAS1" layout of SamplePayload.WriteAsync.</summary>
    public static byte[] Payload(List<object> header, List<(double[] s, double[] t, double[] v, byte[] q)> blocks)
    {
        var json = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new Dictionary<string, object> { ["with_seq"] = true, ["signals"] = header }));
        using var ms = new MemoryStream();
        ms.Write("SAS1"u8);
        ms.Write(BitConverter.GetBytes((uint)json.Length));
        ms.Write(json); Pad(ms);
        foreach (var (s, t, v, q) in blocks)
        {
            foreach (var d in new[] { s, t, v }) { foreach (var x in d) ms.Write(BitConverter.GetBytes(x)); Pad(ms); }
            ms.Write(q); Pad(ms);
        }
        return ms.ToArray();
    }

    static void Pad(MemoryStream ms) { while (ms.Length % 8 != 0) ms.WriteByte(0); }

    public ValueTask DisposeAsync() => app.DisposeAsync();
}
