using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json.Nodes;

namespace SpaceLinkBridge.Link;

/// <summary>
/// Settings, lifecycle and status of the phone link. The bridge starts listening as soon as it runs
/// (the executable itself is the opt-in); the access code and port are remembered.
/// </summary>
public sealed class BridgeException(string message) : Exception(message);

public sealed class MobileLinkManager : IDisposable
{
    public const int DefaultPort = 47800;
    public MobileLinkHub Hub { get; } = new();
    public Func<string> AcquisitionName { get; set; } = () => "Space Fieldbus Analyzer";
    public Action<string> Log { get; set; } = _ => { };

    readonly string directory;
    readonly string settingsPath;
    readonly SemaphoreSlim gate = new(1, 1);
    readonly bool bindAny;
    bool enabled;
    int port = DefaultPort;
    string code;
    string error = "";
    MobileLinkServer? server;
    MobileLinkBeacon? beacon;
    X509Certificate2? certificate;
    AccessGate? access;

    /// <param name="bindAny">Listen on every interface (the phone arrives through the USB-tethering adapter); tests use loopback.</param>
    public MobileLinkManager(string dataDir, bool bindAny = true)
    {
        directory = Path.Combine(dataDir, "mobile_link");
        settingsPath = Path.Combine(directory, "settings.json");
        this.bindAny = bindAny;
        var saved = File.Exists(settingsPath) ? TryRead() : null;
        enabled = saved?["enabled"]?.GetValue<bool>() ?? false;
        port = saved?["port"]?.GetValue<int>() is int p and >= 1024 and <= 65535 ? p : DefaultPort;
        code = saved?["access_code"]?.GetValue<string>() is { Length: >= 6 } c ? c : NewCode();
    }

    JsonObject? TryRead()
    {
        try { return JsonNode.Parse(File.ReadAllText(settingsPath)) as JsonObject; }
        catch (Exception exc) when (exc is IOException or System.Text.Json.JsonException or InvalidOperationException) { return null; }
    }

    static string NewCode() => RandomNumberGenerator.GetInt32(0, 100_000_000).ToString("D8");

    void Save()
    {
        Directory.CreateDirectory(directory);
        var temporary = settingsPath + ".tmp";
        File.WriteAllText(temporary, new JsonObject { ["enabled"] = enabled, ["port"] = port, ["access_code"] = code }.ToJsonString());
        File.Move(temporary, settingsPath, overwrite: true);
    }

    public bool Running => server != null;

    public async Task AutoStartAsync()
    {
        if (enabled) await SetEnabledAsync(true, remember: false);
    }

    public async Task SetEnabledAsync(bool on, bool remember = true)
    {
        await gate.WaitAsync();
        try
        {
            if (on) await StartLocked(); else await StopLocked();
            if (remember) { enabled = on; Save(); }
        }
        finally { gate.Release(); }
    }

    /// <summary>A new code locks out every phone paired with the old one: their streams end and they must enter the new code.</summary>
    public async Task RotateCodeAsync()
    {
        await gate.WaitAsync();
        try
        {
            code = NewCode();
            Save();
            if (access != null) access.Code = code;
            Hub.AbortAll("Codice di accesso cambiato");
        }
        finally { gate.Release(); }
    }

    public async Task SetPortAsync(int newPort)
    {
        if (newPort is < 1024 or > 65535) throw new BridgeException("Porta tra 1024 e 65535");
        await gate.WaitAsync();
        try
        {
            if (newPort == port) return;
            var wasRunning = server != null;
            await StopLocked();
            port = newPort;
            Save();
            if (wasRunning) await StartLocked();
        }
        finally { gate.Release(); }
    }

    async Task StartLocked()
    {
        if (server != null) return;
        MobileLinkServer? starting = null;
        try
        {
            certificate = MobileLinkCertificate.LoadOrCreate(directory);
            access = new AccessGate(code);
            starting = new MobileLinkServer();
            Hub.Active = true;
            await starting.StartAsync(bindAny ? IPAddress.Any : IPAddress.Loopback, port, certificate, access, Hub, AcquisitionName);
            server = starting;
            beacon = bindAny ? new MobileLinkBeacon(starting.BoundPort) : null;
            error = "";
            Log($"In ascolto sulla porta {starting.BoundPort}");
        }
        catch (Exception exc)
        {
            Hub.Active = false;
            if (starting != null) await starting.DisposeAsync();
            certificate?.Dispose(); certificate = null;
            error = exc is IOException or System.Net.Sockets.SocketException or Microsoft.AspNetCore.Connections.AddressInUseException
                ? $"Porta {port} non disponibile: {exc.Message}" : exc.Message;
            throw new BridgeException(error);
        }
    }

    async Task StopLocked()
    {
        var wasRunning = server != null;
        beacon?.Dispose(); beacon = null;
        Hub.Active = false;
        Hub.Reset();
        if (server != null) await server.DisposeAsync();
        server = null;
        certificate?.Dispose(); certificate = null;
        if (wasRunning) Log("Servizio fermato");
    }

    public int BoundPort => server?.BoundPort ?? port;
    public string Fingerprint => certificate is { } c ? MobileLinkCertificate.Fingerprint(c) : "";

    public JsonObject Status() => new()
    {
        ["enabled"] = enabled,
        ["running"] = server != null,
        ["port"] = port,
        ["access_code"] = code,
        ["fingerprint"] = Fingerprint,
        ["clients"] = Hub.SubscriberCount,
        ["signals"] = Hub.Signals().Count,
        ["addresses"] = new JsonArray(MobileLinkBeacon.Targets().Select(t => (JsonNode?)t.Local.ToString()).ToArray()),
        ["error"] = error,
    };

    public void Dispose()
    {
        try { SetEnabledAsync(false, remember: false).Wait(TimeSpan.FromSeconds(10)); } catch (Exception) { }
        gate.Dispose();
    }
}
