using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Grpc.Core;
using Grpc.Core.Interceptors;

namespace SpaceLinkBridge.Link;

/// <summary>Access code check with a lock-out against guessing.</summary>
public sealed class AccessGate(string code)
{
    public const string HeaderName = "x-sfa-access-code";
    const int MaxFailures = 5;
    static readonly TimeSpan Window = TimeSpan.FromSeconds(60);

    readonly object gate = new();
    readonly Queue<DateTime> failures = new();
    volatile string code = code;

    public string Code { get => code; set => code = value; }

    /// <summary>Throws the RPC error to return; returns normally when the code is right.</summary>
    public void Check(string? supplied)
    {
        lock (gate)
        {
            var now = DateTime.UtcNow;
            while (failures.Count > 0 && now - failures.Peek() > Window) failures.Dequeue();
            if (failures.Count >= MaxFailures)
                throw new RpcException(new Status(StatusCode.ResourceExhausted, "Troppi tentativi con un codice errato: attendere un minuto"));
            if (supplied != null && Equal(supplied, code)) return;
            failures.Enqueue(now);
        }
        throw new RpcException(new Status(StatusCode.Unauthenticated, "Codice di accesso errato"));
    }

    static bool Equal(string a, string b) =>
        CryptographicOperations.FixedTimeEquals(SHA256.HashData(Encoding.UTF8.GetBytes(a)), SHA256.HashData(Encoding.UTF8.GetBytes(b)));
}

/// <summary>Every call, unary or streaming, must carry the access code.</summary>
public sealed class AccessInterceptor(AccessGate gate) : Interceptor
{
    void Verify(ServerCallContext context) => gate.Check(context.RequestHeaders.GetValue(AccessGate.HeaderName));

    public override Task<TResponse> UnaryServerHandler<TRequest, TResponse>(TRequest request, ServerCallContext context, UnaryServerMethod<TRequest, TResponse> continuation)
    {
        Verify(context);
        return continuation(request, context);
    }

    public override Task ServerStreamingServerHandler<TRequest, TResponse>(TRequest request, IServerStreamWriter<TResponse> responseStream, ServerCallContext context, ServerStreamingServerMethod<TRequest, TResponse> continuation)
    {
        Verify(context);
        return continuation(request, responseStream, context);
    }
}

/// <summary>Self-signed certificate of the phone link. The phone trusts its SHA-256 fingerprint, nothing else.</summary>
public static class MobileLinkCertificate
{
    public static string Fingerprint(X509Certificate certificate) =>
        Convert.ToHexString(SHA256.HashData(certificate.GetRawCertData())).Chunk(2).Select(c => new string(c)).Aggregate((a, b) => a + ":" + b);

    /// <summary>Loads the saved certificate or creates one (a new certificate means the phones must pair again).</summary>
    public static X509Certificate2 LoadOrCreate(string directory)
    {
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "server.pfx");
        if (File.Exists(path))
        {
            try
            {
                var saved = new X509Certificate2(path, (string?)null, X509KeyStorageFlags.DefaultKeySet);
                if (saved.NotAfter > DateTime.Now.AddDays(30) && saved.HasPrivateKey) return saved;
                saved.Dispose();
            }
            catch (CryptographicException) { }
        }
        var created = Create();
        var pfx = created.Export(X509ContentType.Pfx);
        var temporary = path + ".tmp";
        File.WriteAllBytes(temporary, pfx);
        File.Move(temporary, path, overwrite: true);
        created.Dispose();
        // Re-imported from the PFX: a key created in memory is not usable by the Windows TLS stack.
        return new X509Certificate2(path, (string?)null, X509KeyStorageFlags.DefaultKeySet);
    }

    public static X509Certificate2 Create()
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest($"CN=Space Fieldbus Analyzer ({Environment.MachineName})", key, HashAlgorithmName.SHA256);
        // Names are not what the phone checks (it pins the fingerprint) but stricter clients, and tests, expect a SAN.
        var names = new SubjectAlternativeNameBuilder();
        names.AddDnsName("localhost");
        names.AddDnsName(Environment.MachineName);
        names.AddIpAddress(System.Net.IPAddress.Loopback);
        request.CertificateExtensions.Add(names.Build());
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, false));
        return request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));
    }
}
