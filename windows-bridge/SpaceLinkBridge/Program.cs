using System.Globalization;
using SpaceLinkBridge.Link;
using SpaceLinkBridge.Space;

namespace SpaceLinkBridge;

public static class Program
{
    public const string Version = "0.1.0";

    static readonly string Usage = """
        Space Link Bridge — ponte tra Space Fieldbus Analyzer e l'app Android.

        Uso: SpaceLinkBridge [opzioni]
          --space-url <url>   indirizzo di Space (predefinito: ricerca su 127.0.0.1:5000-5019)
          --port <n>          porta per il telefono (predefinita 47800, poi ricordata)
          --poll-ms <n>       intervallo di lettura da Space, 20-1000 ms (predefinito 100)
          --data-dir <cartella>  impostazioni e certificato (predefinita %LOCALAPPDATA%\SpaceLinkBridge)
          --new-code          genera subito un nuovo codice di accesso
          -h, --help          questa guida

        Tasti: N = nuovo codice di accesso, Q = esci.
        """;

    public static async Task<int> Main(string[] args)
    {
        string? spaceUrl = null, dataDir = null;
        int? port = null;
        var pollMs = 100;
        var newCode = false;
        try
        {
            for (var i = 0; i < args.Length; i++)
            {
                string Next() => i + 1 < args.Length ? args[++i] : throw new ArgumentException($"Manca il valore di {args[i]}");
                switch (args[i])
                {
                    case "--space-url": spaceUrl = Next(); break;
                    case "--port": port = int.Parse(Next(), CultureInfo.InvariantCulture); break;
                    case "--poll-ms": pollMs = int.Parse(Next(), CultureInfo.InvariantCulture); break;
                    case "--data-dir": dataDir = Next(); break;
                    case "--new-code": newCode = true; break;
                    case "-h" or "--help": Console.WriteLine(Usage); return 0;
                    default: throw new ArgumentException($"Opzione sconosciuta: {args[i]}");
                }
            }
            if (pollMs is < 20 or > 1000) throw new ArgumentException("--poll-ms tra 20 e 1000");
            if (spaceUrl != null) _ = new SpaceClient(spaceUrl);
        }
        catch (Exception exc) when (exc is ArgumentException or FormatException or UriFormatException)
        {
            Console.Error.WriteLine(exc.Message + "\n\n" + Usage);
            return 2;
        }

        dataDir ??= Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SpaceLinkBridge");
        Directory.CreateDirectory(dataDir);
        var log = new List<string>();
        void Log(string text) { lock (log) { log.Add($"{DateTime.Now:HH:mm:ss} {text}"); if (log.Count > 8) log.RemoveAt(0); } }

        using var manager = new MobileLinkManager(dataDir) { Log = Log };
        manager.AcquisitionName = () => "Space Fieldbus Analyzer";
        try
        {
            if (port is int p) await manager.SetPortAsync(p);
            if (newCode) await manager.RotateCodeAsync();
            await manager.SetEnabledAsync(true);
        }
        catch (BridgeException exc)
        {
            Console.Error.WriteLine("Impossibile avviare il servizio per il telefono: " + exc.Message);
            return 1;
        }

        using var stop = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; stop.Cancel(); };
        var feeder = new SpaceFeeder(manager.Hub, spaceUrl, pollMs, Log);
        var feederTask = feeder.RunAsync(stop.Token);

        var interactive = !Console.IsInputRedirected && !Console.IsOutputRedirected;
        var last = "";
        while (!stop.IsCancellationRequested)
        {
            if (interactive)
                while (Console.KeyAvailable)
                {
                    var key = Console.ReadKey(true).Key;
                    if (key == ConsoleKey.Q) stop.Cancel();
                    else if (key == ConsoleKey.N) { await manager.RotateCodeAsync(); Log("Nuovo codice di accesso generato"); }
                }
            var text = Render(manager, feeder.Status, log);
            if (text != last)
            {
                if (interactive) Console.Clear();
                Console.WriteLine(text);
                last = text;
            }
            try { await Task.Delay(500, stop.Token); } catch (OperationCanceledException) { }
        }
        try { await feederTask; } catch (OperationCanceledException) { }
        return 0;
    }

    static string Render(MobileLinkManager manager, FeederStatus space, List<string> log)
    {
        var s = manager.Status();
        var lines = new List<string>
        {
            $"Space Link Bridge {Version}",
            new string('─', 60),
            space.Connected
                ? $"Space: collegato a {space.Url} (V{space.Version}) · {space.Signals} segnali · {space.SamplesPerSecond:F0} campioni/s"
                : $"Space: NON collegato — {space.Error}",
            $"Telefono: in ascolto sulla porta {s["port"]} · telefoni collegati: {s["clients"]}",
            "",
            $"Codice di accesso : {s["access_code"]}",
            $"Impronta (SHA-256): {s["fingerprint"]}",
            $"Indirizzi del PC  : {string.Join(" · ", ((System.Text.Json.Nodes.JsonArray)s["addresses"]!).Select(a => a!.ToString()))}",
            "",
            "Nell'app: Collega → scegliere il PC → inserire il codice → Verifica PC → confrontare l'impronta → Connetti.",
            "Al primo avvio Windows chiede l'accesso al firewall: con il tethering USB spuntare anche le reti pubbliche.",
            "",
            "[N] nuovo codice   [Q] esci",
            "",
        };
        lock (log) lines.AddRange(log);
        return string.Join("\n", lines);
    }
}
