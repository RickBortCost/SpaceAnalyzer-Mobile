using System.Net;
using System.Security.Cryptography.X509Certificates;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using SpaceAnalyzer.App.MobileLink.Proto;

namespace SpaceLinkBridge.Link;

/// <summary>
/// gRPC endpoint for the phone. It is a host of its own, separate from the browser UI: only the SFA Link
/// service is reachable on its port, never the UI or its API, whatever the network the phone is on.
/// </summary>
public sealed class MobileLinkServer : IAsyncDisposable
{
    WebApplication? app;

    public int BoundPort { get; private set; }

    public async Task StartAsync(IReadOnlyList<IPAddress> binds, int port, X509Certificate2 certificate, AccessGate gate, MobileLinkHub hub, Func<string> acquisitionName)
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.AddServerHeader = false;
            options.Limits.Http2.KeepAlivePingDelay = TimeSpan.FromSeconds(15);
            options.Limits.Http2.KeepAlivePingTimeout = TimeSpan.FromSeconds(10);
            foreach (var bind in binds)
                options.Listen(bind, port, listen =>
                {
                    listen.Protocols = HttpProtocols.Http2;
                    listen.UseHttps(certificate);
                });
        });
        builder.Services.AddSingleton(hub);
        builder.Services.AddSingleton(gate);
        builder.Services.AddSingleton(acquisitionName);
        builder.Services.AddGrpc(options =>
        {
            options.Interceptors.Add<AccessInterceptor>();
            options.MaxReceiveMessageSize = 16 * 1024;
        });
        var built = builder.Build();
        built.MapGrpcService<SfaLinkService>();
        await built.StartAsync();
        app = built;
        var address = built.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.First();
        BoundPort = new Uri(address.Replace("[::]", "localhost").Replace("0.0.0.0", "localhost")).Port;
    }

    public async Task StopAsync()
    {
        if (app is not { } running) return;
        app = null;
        try { await running.StopAsync(TimeSpan.FromSeconds(5)); }
        finally { await running.DisposeAsync(); }
    }

    public async ValueTask DisposeAsync() => await StopAsync();
}
