using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.RegularExpressions;

namespace SpaceLinkBridge.Link;

/// <summary>An IPv4 address of this PC with its adapter; <see cref="UsbPhone"/> marks the adapter created by USB tethering.</summary>
public sealed record LocalAddress(IPAddress Ip, IPAddress Broadcast, string Name, string Description, bool UsbPhone);

public static class NetworkAdapters
{
    static readonly Regex PhoneAdapter = new(@"rndis|remote\s*ndis|\bncm\b|samsung|android|galaxy|usb\s*ethernet|usb\s*gadget|mobile\s*usb", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    /// <summary>
    /// Windows names the adapter of Android USB tethering «Remote NDIS based Internet Sharing Device» (or «…USB NCM…»),
    /// and Android hands out 192.168.42.x on it. Wi-Fi, Ethernet, VPN and Bluetooth adapters never match.
    /// </summary>
    public static bool LooksLikeUsbPhone(string name, string description, IPAddress ip)
    {
        if (ip.AddressFamily != AddressFamily.InterNetwork) return false;
        var b = ip.GetAddressBytes();
        if (b[0] == 192 && b[1] == 168 && b[2] == 42) return true;
        var text = name + " " + description;
        if (Regex.IsMatch(text, @"wi-?fi|wireless|wlan|bluetooth|virtual|vmware|hyper-v|vpn|loopback", RegexOptions.IgnoreCase)) return false;
        return PhoneAdapter.IsMatch(text);
    }

    public static IReadOnlyList<LocalAddress> List()
    {
        var result = new List<LocalAddress>();
        NetworkInterface[] interfaces;
        try { interfaces = NetworkInterface.GetAllNetworkInterfaces(); }
        catch (NetworkInformationException) { return result; }
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
                result.Add(new LocalAddress(address.Address, new IPAddress(broadcast), nic.Name, nic.Description, LooksLikeUsbPhone(nic.Name, nic.Description, address.Address)));
            }
        }
        return result;
    }
}
