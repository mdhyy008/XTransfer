using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;

namespace BleBridge;

internal sealed class BleScanner
{
    private BluetoothLEAdvertisementWatcher? _watcher;

    public event Action<ulong, short, string>? Found;
    public event Action<string>? Error;

    public bool Running => _watcher?.Status == BluetoothLEAdvertisementWatcherStatus.Started;

    public void Start()
    {
        Stop();
        var watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active,
        };
        var filter = new BluetoothLEAdvertisementFilter
        {
            Advertisement = new BluetoothLEAdvertisement(),
        };
        filter.Advertisement.ServiceUuids.Add(Proto.Service);
        watcher.AdvertisementFilter = filter;
        watcher.Received += (_, args) =>
        {
            Found?.Invoke(
                args.BluetoothAddress,
                args.RawSignalStrengthInDBm,
                args.Advertisement.LocalName ?? string.Empty);
        };
        watcher.Stopped += (_, args) =>
        {
            if (args.Error != BluetoothError.Success &&
                args.Error != BluetoothError.RadioNotAvailable &&
                args.Error != BluetoothError.DisabledByPolicy)
            {
                Error?.Invoke($"扫描停止：{args.Error}");
            }
        };
        _watcher = watcher;
        watcher.Start();
    }

    public void Stop()
    {
        var watcher = _watcher;
        _watcher = null;
        if (watcher == null) return;
        try
        {
            watcher.Stop();
        }
        catch
        {
            // 蓝牙已关闭。
        }
    }
}
