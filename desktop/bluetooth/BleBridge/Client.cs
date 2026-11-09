using System.Text;
using System.Text.Json;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;

namespace BleBridge;

internal sealed class GattClientLink : IDisposable
{
    private readonly ulong _address;
    private BluetoothLEDevice? _device;
    private GattSession? _session;
    private GattDeviceService? _service;
    private GattCharacteristic? _rx;
    private GattCharacteristic? _tx;
    private readonly FrameAssembler _assembler = new();
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private bool _closed;

    public string PeerId { get; private set; } = "";
    public string PeerName { get; private set; } = "";
    public string PeerPlatform { get; private set; } = "";
    public ulong Address => _address;
    public bool Ready => _rx != null && _tx != null && !_closed;

    public event Action<byte[]>? FrameReceived;
    public event Action? Disconnected;

    public GattClientLink(ulong address)
    {
        _address = address;
    }

    public async Task<bool> ConnectAsync()
    {
        try
        {
            _device = await BluetoothLEDevice.FromBluetoothAddressAsync(_address);
            if (_device == null) return false;
            _device.ConnectionStatusChanged += OnConnectionStatusChanged;
            _session = await GattSession.FromDeviceIdAsync(_device.BluetoothDeviceId);
            if (_session != null) _session.MaintainConnection = true;

            var services = await _device.GetGattServicesForUuidAsync(Proto.Service, BluetoothCacheMode.Uncached);
            if (services.Status != GattCommunicationStatus.Success || services.Services.Count == 0) return false;
            _service = services.Services[0];

            var infoResult = await _service.GetCharacteristicsForUuidAsync(Proto.Info);
            if (infoResult.Status != GattCommunicationStatus.Success || infoResult.Characteristics.Count == 0) return false;
            var infoChar = infoResult.Characteristics[0];
            var read = await infoChar.ReadValueAsync(BluetoothCacheMode.Uncached);
            if (read.Status != GattCommunicationStatus.Success) return false;
            var infoText = Encoding.UTF8.GetString(Proto.Bytes(read.Value));
            var info = JsonSerializer.Deserialize<WireInfo>(infoText);
            if (info == null || string.IsNullOrEmpty(info.Id)) return false;
            PeerId = info.Id;
            PeerName = string.IsNullOrWhiteSpace(info.Name) ? info.Id : info.Name;
            PeerPlatform = string.IsNullOrWhiteSpace(info.Platform) ? "android" : info.Platform;

            var rxResult = await _service.GetCharacteristicsForUuidAsync(Proto.Rx);
            if (rxResult.Status == GattCommunicationStatus.Success && rxResult.Characteristics.Count > 0)
            {
                _rx = rxResult.Characteristics[0];
            }
            var txResult = await _service.GetCharacteristicsForUuidAsync(Proto.Tx);
            if (txResult.Status == GattCommunicationStatus.Success && txResult.Characteristics.Count > 0)
            {
                _tx = txResult.Characteristics[0];
            }
            if (_rx == null || _tx == null) return false;

            _tx.ValueChanged += OnValueChanged;
            var cccd = await _tx.WriteClientCharacteristicConfigurationDescriptorAsync(
                GattClientCharacteristicConfigurationDescriptorValue.Notify);
            if (cccd != GattCommunicationStatus.Success) return false;
            return true;
        }
        catch
        {
            return false;
        }
    }

    private void OnValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var data = Proto.Bytes(args.CharacteristicValue);
        foreach (var frame in _assembler.Feed(data))
        {
            FrameReceived?.Invoke(frame);
        }
    }

    private void OnConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected && !_closed)
        {
            Disconnected?.Invoke();
        }
    }

    public int ChunkSize
    {
        get
        {
            var mtu = (int)(_session?.MaxPduSize ?? 23);
            if (mtu <= 0) mtu = 23;
            // ATT 属性值上限 512 字节，MTU 517 时减 3 会超限。
            return Math.Clamp(mtu - 3, 1, 512);
        }
    }

    public async Task<bool> SendAsync(byte[] data)
    {
        var rx = _rx;
        if (rx == null || _closed) return false;
        var chunk = ChunkSize;
        await _writeLock.WaitAsync();
        try
        {
            for (var offset = 0; offset < data.Length; offset += chunk)
            {
                var size = Math.Min(chunk, data.Length - offset);
                var slice = new byte[size];
                Buffer.BlockCopy(data, offset, slice, 0, size);
                var result = await rx.WriteValueWithResultAsync(
                    Proto.Buffer(slice), GattWriteOption.WriteWithResponse);
                if (result.Status != GattCommunicationStatus.Success) return false;
            }
            return true;
        }
        catch
        {
            return false;
        }
        finally
        {
            _writeLock.Release();
        }
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        try
        {
            if (_tx != null) _tx.ValueChanged -= OnValueChanged;
        }
        catch
        {
            // 已断开。
        }
        try
        {
            if (_tx != null)
            {
                _ = _tx.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.None);
            }
        }
        catch
        {
            // 已断开。
        }
        try
        {
            if (_device != null) _device.ConnectionStatusChanged -= OnConnectionStatusChanged;
        }
        catch
        {
            // 已断开。
        }
        try
        {
            if (_session != null) _session.MaintainConnection = false;
        }
        catch
        {
            // 已断开。
        }
        _service?.Dispose();
        _session?.Dispose();
        _device?.Dispose();
        _session = null;
        _device = null;
        _service = null;
        _rx = null;
        _tx = null;
        _writeLock.Dispose();
    }
}
