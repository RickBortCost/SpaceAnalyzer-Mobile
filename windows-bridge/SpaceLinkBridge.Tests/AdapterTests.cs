using System.Net;
using SpaceLinkBridge.Link;
using Xunit;

namespace SpaceLinkBridge.Tests;

public class AdapterTests
{
    [Theory]
    [InlineData("Ethernet 3", "Remote NDIS based Internet Sharing Device", "10.0.0.5", true)]
    [InlineData("Ethernet 2", "SAMSUNG Mobile USB Remote NDIS Network Device", "169.254.10.4", true)]
    [InlineData("Ethernet 4", "Samsung Mobile USB NCM Network Device", "10.9.9.9", true)]
    [InlineData("Ethernet 5", "USB Ethernet/RNDIS Gadget", "10.1.1.1", true)]
    [InlineData("Ethernet", "Intel(R) Ethernet Connection I219-V", "192.168.42.17", true)]   // Android's tethering subnet
    [InlineData("Ethernet", "Intel(R) Ethernet Connection I219-V", "192.168.1.17", false)]
    [InlineData("Wi-Fi", "Intel(R) Wi-Fi 6 AX201 160MHz", "192.168.1.30", false)]
    [InlineData("Wi-Fi 2", "Android Wireless Hotspot Adapter", "192.168.43.2", false)]
    [InlineData("VMware Network Adapter VMnet1", "VMware Virtual Ethernet Adapter", "192.168.10.1", false)]
    [InlineData("Bluetooth Network Connection", "Bluetooth Device (Personal Area Network)", "169.254.3.3", false)]
    [InlineData("vEthernet (Default Switch)", "Hyper-V Virtual Ethernet Adapter", "172.20.0.1", false)]
    public void RecognisesOnlyTheUsbTetheringAdapterOfAPhone(string name, string description, string ip, bool expected) =>
        Assert.Equal(expected, NetworkAdapters.LooksLikeUsbPhone(name, description, IPAddress.Parse(ip)));

    [Fact]
    public void RealAdapterListNeverThrowsAndNeverMarksLoopback()
    {
        var list = NetworkAdapters.List();
        Assert.All(list, a => Assert.False(IPAddress.IsLoopback(a.Ip)));
    }
}

public class UsbOnlyManagerTests : IDisposable
{
    readonly TempDir dir = new();
    readonly List<LocalAddress> adapters = new();
    MobileLinkManager manager;

    static LocalAddress Wifi() => new(IPAddress.Parse("192.168.1.30"), IPAddress.Parse("192.168.1.255"), "Wi-Fi", "Intel Wi-Fi 6", false);
    static LocalAddress Usb() => new(IPAddress.Loopback, IPAddress.Parse("127.255.255.255"), "Ethernet 3", "Remote NDIS based Internet Sharing Device", true);

    public UsbOnlyManagerTests()
    {
        manager = New();
    }

    MobileLinkManager New()
    {
        var m = new MobileLinkManager(dir.Path, bindAny: true, adapters: () => adapters.ToList());
        var probe = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0); probe.Start();
        var port = ((IPEndPoint)probe.LocalEndpoint).Port; probe.Stop();
        m.SetPortAsync(port).GetAwaiter().GetResult();
        return m;
    }

    public void Dispose() { manager.Dispose(); dir.Dispose(); }

    [Fact]
    public async Task DefaultIsUsbOnlyAndWaitsUntilThePhoneAdapterAppears()
    {
        adapters.Add(Wifi());
        await manager.SetEnabledAsync(true);
        Assert.True(manager.UsbOnly);
        Assert.False(manager.Running);                       // Wi-Fi alone never opens the port
        Assert.True(manager.WaitingForUsb);
        Assert.True(manager.Status()["waiting_usb"]!.GetValue<bool>());
        Assert.NotEmpty(manager.Fingerprint);                // readable while waiting

        adapters.Add(Usb());
        await manager.RefreshAsync();
        Assert.True(manager.Running);
        Assert.False(manager.WaitingForUsb);
        Assert.Equal(new[] { "127.0.0.1" }, manager.Status()["listening"]!.AsArray().Select(x => x!.ToString()));

        adapters.RemoveAll(a => a.UsbPhone);                 // cable pulled
        await manager.RefreshAsync();
        Assert.False(manager.Running);
        Assert.False(manager.Hub.Active);
        Assert.True(manager.WaitingForUsb);

        adapters.Add(Usb());                                 // plugged again
        await manager.RefreshAsync();
        Assert.True(manager.Running);
    }

    [Fact]
    public async Task AllInterfacesModeListensOnEveryAdapterAndIsRemembered()
    {
        adapters.Add(Wifi());
        await manager.SetEnabledAsync(true);
        await manager.SetUsbOnlyAsync(false);
        Assert.True(manager.Running);
        Assert.Equal(new[] { "tutte" }, manager.Status()["listening"]!.AsArray().Select(x => x!.ToString()));
        manager.Dispose();
        manager = new MobileLinkManager(dir.Path, bindAny: true, adapters: () => adapters.ToList());
        Assert.False(manager.UsbOnly);                      // survives a restart
        await manager.SetUsbOnlyAsync(true);
        Assert.False(manager.Running);
    }

    [Fact]
    public async Task ChangingTheUsbAddressMovesTheListener()
    {
        adapters.Add(Usb());
        await manager.SetEnabledAsync(true);
        Assert.Equal(new[] { "127.0.0.1" }, manager.Status()["listening"]!.AsArray().Select(x => x!.ToString()));
        adapters.Clear();
        adapters.Add(new LocalAddress(IPAddress.Parse("127.0.0.2"), IPAddress.Parse("127.255.255.255"), "Ethernet 3", "Remote NDIS based Internet Sharing Device", true));
        await manager.RefreshAsync();
        Assert.Equal(new[] { "127.0.0.2" }, manager.Status()["listening"]!.AsArray().Select(x => x!.ToString()));
        Assert.True(manager.Running);
    }
}
