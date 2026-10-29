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
    readonly Func<IReadOnlyList<LocalAddress>> adapters;
    bool enabled;
    bool usbOnly = true;
    IReadOnlyList<IPAddress> binds = Array.Empty<IPAddress>();
    int port = DefaultPort;
    string code;
    string error = "";
    MobileLinkServer? server;
    MobileLinkBeacon? beacon;
    X509Certificate2? certificate;
    AccessGate? access;

    /// <param name="bindAny">Listen on the PC's network adapters; false = loopback only (tests).</param>
    /// <param name="adapters">Source of the PC's IPv4 addresses (replaced in tests).</param>
    public MobileLinkManager(string dataDir, bool bindAny = true, Func<IReadOnlyList<LocalAddress>>? adapters = null)
    {
        this.adapters = adapters ?? NetworkAdapters.List;
        directory = Path.Combine(dataDir, "mobile_link");
        settingsPath = Path.Combine(directory, "settings.json");
        this.bindAny = bindAny;
        var saved = File.Exists(settingsPath) ? TryRead() : null;
        enabled = saved?["enabled"]?.GetValue<bool>() ?? false;
        usbOnly = saved?["usb_only"]?.GetValue<bool>() ?? true;
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
        File.WriteAllText(temporary, new JsonObject { ["enabled"] = enabled, ["port"] = port, ["access_code"] = code, ["usb_only"] = usbOnly }.ToJsonString());
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

    /// <summary>Where to listen: loopback in tests; the USB-tethering adapter(s) of the phone by default; every adapter if the user asked.</summary>
    List<IPAddress> DesiredBinds()
    {
        if (!bindAny) return new() { IPAddress.Loopback };
        if (!usbOnly) return new() { IPAddress.Any };
        return adapters().Where(a => a.UsbPhone).Select(a => a.Ip).Distinct().ToList();
    }

    IEnumerable<LocalAddress> BeaconTargets() => usbOnly ? adapters().Where(a => a.UsbPhone) : adapters();

    async Task StartLocked()
    {
        if (server != null) return;
        var wanted = DesiredBinds();
        if (wanted.Count == 0) return;           // waiting for the USB adapter: RefreshAsync starts the server when it appears
        MobileLinkServer? starting = null;
        try
        {
            certificate ??= MobileLinkCertificate.LoadOrCreate(directory);
            access = new AccessGate(code);
            starting = new MobileLinkServer();
            Hub.Active = true;
            await starting.StartAsync(wanted, port, certificate, access, Hub, AcquisitionName);
            server = starting;
            binds = wanted;
            beacon = bindAny ? new MobileLinkBeacon(starting.BoundPort, BeaconTargets) : null;
            error = "";
            Log($"In ascolto sulla porta {starting.BoundPort} ({string.Join(", ", wanted.Select(w => w.Equals(IPAddress.Any) ? "tutte le schede" : w.ToString()))})");
        }
        catch (Exception exc)
        {
            Hub.Active = false;
            if (starting != null) await starting.DisposeAsync();
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
        binds = Array.Empty<IPAddress>();
        if (wasRunning) Log("Servizio fermato");
    }

    /// <summary>Follows the adapters: starts when the phone's USB adapter appears, stops when it goes away, moves when its address changes.</summary>
    public async Task RefreshAsync()
    {
        await gate.WaitAsync();
        try
        {
            if (!enabled) return;
            var wanted = DesiredBinds();
            if (server == null) { if (wanted.Count > 0) await StartLocked(); return; }
            if (wanted.Count == binds.Count && wanted.All(binds.Contains)) return;
            await StopLocked();
            if (wanted.Count > 0) await StartLocked();
        }
        catch (BridgeException) { }
        finally { gate.Release(); }
    }

    /// <summary>true = listen only on the phone's USB adapter (default); false = every adapter of the PC.</summary>
    public async Task SetUsbOnlyAsync(bool value)
    {
        await gate.WaitAsync();
        try { usbOnly = value; Save(); }
        finally { gate.Release(); }
        await RefreshAsync();
    }

    public bool UsbOnly => usbOnly;
    /// <summary>The service is on but no USB adapter of the phone exists yet.</summary>
    public bool WaitingForUsb => enabled && server == null && bindAny && usbOnly && DesiredBinds().Count == 0;

    public int BoundPort => server?.BoundPort ?? port;
    /// <summary>Known before the service starts (the phone's adapter may not exist yet), so the user can read it at once.</summary>
    public string Fingerprint
    {
        get
        {
            try { certificate ??= MobileLinkCertificate.LoadOrCreate(directory); }
            catch (Exception exc) when (exc is IOException or UnauthorizedAccessException or System.Security.Cryptography.CryptographicException) { return ""; }
            return MobileLinkCertificate.Fingerprint(certificate);
        }
    }

    public JsonObject Status() => new()
    {
        ["enabled"] = enabled,
        ["running"] = server != null,
        ["port"] = port,
        ["access_code"] = code,
        ["fingerprint"] = Fingerprint,
        ["clients"] = Hub.SubscriberCount,
        ["signals"] = Hub.Signals().Count,
        ["addresses"] = new JsonArray(adapters().Select(a => (JsonNode?)a.Ip.ToString()).ToArray()),
        ["adapters"] = new JsonArray(adapters().Select(a => (JsonNode?)new JsonObject { ["ip"] = a.Ip.ToString(), ["name"] = a.Name, ["description"] = a.Description, ["usb"] = a.UsbPhone }).ToArray()),
        ["usb_only"] = usbOnly,
        ["waiting_usb"] = WaitingForUsb,
        ["listening"] = new JsonArray(binds.Select(b => (JsonNode?)(b.Equals(IPAddress.Any) ? "tutte" : b.ToString())).ToArray()),
        ["error"] = error,
    };

    public void Dispose()
    {
        try { SetEnabledAsync(false, remember: false).Wait(TimeSpan.FromSeconds(10)); } catch (Exception) { }
        certificate?.Dispose(); certificate = null;
        gate.Dispose();
    }
}
