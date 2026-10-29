using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace SpaceLinkBridge.Link;

/// <summary>
/// UDP announcement that lets the phone find this PC: {"sfa":1,"name":"...","port":47800} every 2 s.
/// It is sent from every connected IPv4 interface to its own broadcast address (Windows would otherwise
/// use the first adapter only, and the USB-tethering adapter is rarely that one). It is only a hint:
/// trust comes from the certificate fingerprint the user confirms on the phone.
/// </summary>
public sealed class MobileLinkBeacon : IDisposable
{
    public const int DefaultPort = 47810;
    readonly CancellationTokenSource stop = new();
    readonly Task loop;

    public MobileLinkBeacon(int linkPort, Func<IEnumerable<LocalAddress>> addresses, int beaconPort = DefaultPort)
    {
        var payload = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { sfa = 1, name = Environment.MachineName, port = linkPort }));
        loop = Task.Run(async () =>
        {
            while (!stop.IsCancellationRequested)
            {
                foreach (var target in addresses())
                {
                    try
                    {
                        using var udp = new UdpClient(new IPEndPoint(target.Ip, 0)) { EnableBroadcast = true };
                        await udp.SendAsync(payload, new IPEndPoint(target.Broadcast, beaconPort));
                    }
                    catch (SocketException) { }
                    catch (ObjectDisposedException) { }
                }
                try { await Task.Delay(2000, stop.Token); } catch (OperationCanceledException) { }
            }
        });
    }

    public void Dispose()
    {
        stop.Cancel();
        try { loop.Wait(TimeSpan.FromSeconds(3)); } catch (AggregateException) { }
        stop.Dispose();
    }
}
