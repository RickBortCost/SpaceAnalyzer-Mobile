using System.Net;
using System.Net.NetworkInformation;
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

    public MobileLinkBeacon(int linkPort, int beaconPort = DefaultPort)
    {
        var payload = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { sfa = 1, name = Environment.MachineName, port = linkPort }));
        loop = Task.Run(async () =>
        {
            while (!stop.IsCancellationRequested)
            {
                foreach (var (local, broadcast) in Targets())
                {
                    try
                    {
                        using var udp = new UdpClient(new IPEndPoint(local, 0)) { EnableBroadcast = true };
                        await udp.SendAsync(payload, new IPEndPoint(broadcast, beaconPort));
                    }
                    catch (SocketException) { }
                    catch (ObjectDisposedException) { }
                }
                try { await Task.Delay(2000, stop.Token); } catch (OperationCanceledException) { }
            }
        });
    }

    /// <summary>(own address, directed broadcast address) of every usable IPv4 interface.</summary>
    public static IEnumerable<(IPAddress Local, IPAddress Broadcast)> Targets()
    {
        NetworkInterface[] interfaces;
        try { interfaces = NetworkInterface.GetAllNetworkInterfaces(); }
        catch (NetworkInformationException) { yield break; }
        foreach (var nic in interfaces)
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var address in nic.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily != AddressFamily.InterNetwork || address.IPv4Mask == null) continue;
                var ip = address.Address.GetAddressBytes();
                var mask = address.IPv4Mask.GetAddressBytes();
                var broadcast = new byte[4];
                for (var i = 0; i < 4; i++) broadcast[i] = (byte)(ip[i] | ~mask[i]);
                yield return (address.Address, new IPAddress(broadcast));
            }
        }
    }

    public void Dispose()
    {
        stop.Cancel();
        try { loop.Wait(TimeSpan.FromSeconds(3)); } catch (AggregateException) { }
        stop.Dispose();
    }
}
