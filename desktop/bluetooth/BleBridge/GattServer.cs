using System.Text;
using System.Text.Json;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;

namespace BleBridge;

internal sealed class GattServer
{
    private readonly string _selfId;
    private readonly string _selfName;
    private GattServiceProvider? _provider;
    private GattLocalCharacteristic? _rx;
    private GattLocalCharacteristic? _tx;
    private readonly byte[] _infoBytes;
    private readonly HashSet<GattSession> _hooked = new();
    // 消息、ACK、心跳都从同一个 TX 特征发出去，必须整帧串行，否则分片会交错。
    private readonly SemaphoreSlim _notifyLock = new(1, 1);

    public event Action<string, bool>? SessionChanged;
    public event Action<string, byte[]>? FrameReceived;

    public GattServer(string selfId, string selfName)
    {
        _selfId = selfId;
        _selfName = selfName;
        var info = new WireInfo { Id = selfId, Name = selfName, Platform = "windows" };
        _infoBytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(info, Proto.Json));
    }

    public async Task StartAsync()
    {
        var created = await GattServiceProvider.CreateAsync(Proto.Service);
        if (created.Error != BluetoothError.Success || created.ServiceProvider == null)
        {
            throw new InvalidOperationException($"GATT 服务创建失败：{created.Error}");
        }
        _provider = created.ServiceProvider;
        var service = _provider.Service;

        var infoParams = new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = GattCharacteristicProperties.Read,
            UserDescription = "xlan info",
            ReadProtectionLevel = GattProtectionLevel.Plain,
        };
        var infoResult = await service.CreateCharacteristicAsync(Proto.Info, infoParams);
        if (infoResult.Error != BluetoothError.Success || infoResult.Characteristic == null)
        {
            throw new InvalidOperationException($"INFO 特征创建失败：{infoResult.Error}");
        }
        infoResult.Characteristic.ReadRequested += OnReadRequested;

        var rxParams = new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = GattCharacteristicProperties.Write
                | GattCharacteristicProperties.WriteWithoutResponse,
            UserDescription = "xlan rx",
            WriteProtectionLevel = GattProtectionLevel.Plain,
        };
        var rxResult = await service.CreateCharacteristicAsync(Proto.Rx, rxParams);
        if (rxResult.Error != BluetoothError.Success || rxResult.Characteristic == null)
        {
            throw new InvalidOperationException($"RX 特征创建失败：{rxResult.Error}");
        }
        _rx = rxResult.Characteristic;
        _rx.WriteRequested += OnWriteRequested;

        var txParams = new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = GattCharacteristicProperties.Notify,
            UserDescription = "xlan tx",
            ReadProtectionLevel = GattProtectionLevel.Plain,
        };
        var txResult = await service.CreateCharacteristicAsync(Proto.Tx, txParams);
        if (txResult.Error != BluetoothError.Success || txResult.Characteristic == null)
        {
            throw new InvalidOperationException($"TX 特征创建失败：{txResult.Error}");
        }
        _tx = txResult.Characteristic;
        _tx.SubscribedClientsChanged += OnSubscribedClientsChanged;

        _provider.StartAdvertising(new GattServiceProviderAdvertisingParameters
        {
            IsConnectable = true,
            IsDiscoverable = true,
        });
    }

    public void Stop()
    {
        try
        {
            _provider?.StopAdvertising();
        }
        catch
        {
            // 蓝牙已关闭时忽略。
        }
        _provider = null;
    }

    private void OnReadRequested(GattLocalCharacteristic sender, GattReadRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        _ = Task.Run(async () =>
        {
            try
            {
                var request = await args.GetRequestAsync();
                if (request == null) return;
                var offset = (int)request.Offset;
                if (offset >= _infoBytes.Length)
                {
                    request.RespondWithValue(Proto.Buffer(Array.Empty<byte>()));
                    return;
                }
                var slice = new byte[_infoBytes.Length - offset];
                Buffer.BlockCopy(_infoBytes, offset, slice, 0, slice.Length);
                request.RespondWithValue(Proto.Buffer(slice));
            }
            catch
            {
                // 对端提前断开。
            }
            finally
            {
                deferral.Complete();
            }
        });
    }

    private void OnWriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        _ = Task.Run(async () =>
        {
            try
            {
                var request = await args.GetRequestAsync();
                if (request == null) return;
                var sessionKey = args.Session?.DeviceId?.Id ?? "";
                var data = Proto.Bytes(request.Value);
                if (request.Option == GattWriteOption.WriteWithResponse)
                {
                    request.Respond();
                }
                if (sessionKey.Length > 0)
                {
                    var assembler = _assemblers.GetOrAdd(sessionKey, _ => new FrameAssembler());
                    foreach (var frame in assembler.Feed(data))
                    {
                        FrameReceived?.Invoke(sessionKey, frame);
                    }
                }
            }
            catch
            {
                // 对端提前断开。
            }
            finally
            {
                deferral.Complete();
            }
        });
    }

    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, FrameAssembler> _assemblers = new();

    private void OnSubscribedClientsChanged(GattLocalCharacteristic sender, object args)
    {
        foreach (var client in sender.SubscribedClients)
        {
            var session = client.Session;
            if (session == null) continue;
            var key = session.DeviceId?.Id ?? "";
            if (key.Length > 0)
            {
                SessionChanged?.Invoke(key, session.SessionStatus == GattSessionStatus.Active);
            }
            lock (_hooked)
            {
                if (_hooked.Add(session))
                {
                    session.SessionStatusChanged += (s, _) =>
                    {
                        var sessionKey = s.DeviceId?.Id ?? "";
                        if (sessionKey.Length > 0)
                        {
                            SessionChanged?.Invoke(sessionKey, s.SessionStatus == GattSessionStatus.Active);
                        }
                    };
                }
            }
        }
    }

    public bool HasSubscriber => (_tx?.SubscribedClients.Count ?? 0) > 0;

    // 一个通知包能带的最大属性值，用来把文件数据块压进单个 ATT 包。
    public int MaxNotificationSize
    {
        get
        {
            var clients = _tx?.SubscribedClients;
            if (clients == null) return 0;
            var size = 0;
            foreach (var client in clients)
            {
                var chunk = (int)client.MaxNotificationSize;
                if (chunk <= 3) continue;
                if (chunk > 512) chunk = 512;
                if (size == 0 || chunk < size) size = chunk;
            }
            return size;
        }
    }

    public async Task<bool> NotifyAsync(byte[] data)
    {
        var tx = _tx;
        if (tx == null) return false;
        var clients = tx.SubscribedClients;
        if (clients.Count == 0) return false;
        var sent = false;
        await _notifyLock.WaitAsync();
        try
        {
            foreach (var client in clients.ToArray())
            {
                var chunk = (int)client.MaxNotificationSize;
                if (chunk <= 3) chunk = 20;
                if (chunk > 512) chunk = 512;
                for (var offset = 0; offset < data.Length; offset += chunk)
                {
                    var size = Math.Min(chunk, data.Length - offset);
                    var slice = new byte[size];
                    Buffer.BlockCopy(data, offset, slice, 0, size);
                    try
                    {
                        await tx.NotifyValueAsync(Proto.Buffer(slice), client);
                        sent = true;
                    }
                    catch
                    {
                        // 单个订阅者断开不影响其他连接。
                    }
                }
            }
        }
        finally
        {
            _notifyLock.Release();
        }
        return sent;
    }
}
