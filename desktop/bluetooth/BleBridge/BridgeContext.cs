using System.Collections.Concurrent;
using System.Text.Json;
using Windows.Devices.Bluetooth;

namespace BleBridge;

internal sealed class PeerDevice
{
    public string id { get; set; } = "";
    public string name { get; set; } = "";
    public string platform { get; set; } = "";
    public string address { get; set; } = "";
    public int rssi { get; set; }
    public bool connected { get; set; }
    public bool connecting { get; set; }
}

internal sealed class BridgeContext : ApplicationContext
{
    private readonly PipeLink _pipe;
    private GattServer? _server;
    private BleScanner? _scanner;
    private string _selfId = "";
    private string _selfName = "";
    private bool _started;
    private ulong _localAddress;

    private readonly ConcurrentDictionary<string, PeerDevice> _devices = new();
    private readonly ConcurrentDictionary<ulong, string> _addressToId = new();
    private readonly ConcurrentDictionary<string, ulong> _idToAddress = new();
    private readonly ConcurrentDictionary<ulong, byte> _introspecting = new();
    private readonly ConcurrentDictionary<string, GattClientLink> _clients = new();
    private readonly ConcurrentDictionary<string, string> _sessionPeer = new();
    private readonly ConcurrentDictionary<string, PendingSend> _pending = new();
    private readonly ConcurrentDictionary<string, byte> _seen = new();
    private readonly ConcurrentQueue<string> _seenOrder = new();
    private readonly SemaphoreSlim _connectLock = new(1, 1);
    private int _emitScheduled;

    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan HeartbeatTimeout = TimeSpan.FromSeconds(15);
    private readonly ConcurrentDictionary<string, long> _lastBeat = new();
    private readonly ConcurrentDictionary<string, long> _lastConnectAttempt = new();
    private CancellationTokenSource? _heartbeatCts;

    private sealed class PendingSend
    {
        public byte[] Frame = Array.Empty<byte>();
    }

    public BridgeContext(string pipeName)
    {
        _pipe = new PipeLink(pipeName);
        _pipe.LineReceived += OnLine;
        _pipe.Closed += Exit;
    }

    public void Begin()
    {
        _ = Task.Run(async () =>
        {
            if (!await _pipe.ConnectAsync(TimeSpan.FromSeconds(10)))
            {
                Exit();
                return;
            }
            _pipe.Send(new { evt = "ready" });
        });
    }

    private void Exit()
    {
        try
        {
            Teardown();
        }
        finally
        {
            Environment.Exit(0);
        }
    }

    private void OnLine(string line)
    {
        try
        {
            using var doc = JsonDocument.Parse(line);
            var root = doc.RootElement;
            var cmd = root.TryGetProperty("cmd", out var cmdEl) ? cmdEl.GetString() : null;
            switch (cmd)
            {
                case "start":
                    _selfId = root.TryGetProperty("selfId", out var idEl) ? (idEl.GetString() ?? "") : "";
                    _selfName = root.TryGetProperty("selfName", out var nameEl) ? (nameEl.GetString() ?? "") : "";
                    _ = StartBleAsync();
                    break;
                case "stop":
                    Teardown();
                    EmitState(null);
                    break;
                case "send":
                {
                    var to = root.TryGetProperty("to", out var toEl) ? (toEl.GetString() ?? "") : "";
                    var id = root.TryGetProperty("id", out var msgEl) ? (msgEl.GetString() ?? "") : "";
                    var body = root.TryGetProperty("body", out var bodyEl) ? (bodyEl.GetString() ?? "") : "";
                    var ts = root.TryGetProperty("ts", out var tsEl) ? tsEl.GetInt64() : 0L;
                    if (to.Length > 0 && id.Length > 0 && body.Length > 0)
                    {
                        _ = SendMessageAsync(to, id, body, ts);
                    }
                    break;
                }
                case "connect":
                {
                    var to = root.TryGetProperty("to", out var toEl) ? (toEl.GetString() ?? "") : "";
                    if (to.Length > 0) _ = ConnectAsync(to);
                    break;
                }
                case "shutdown":
                    Exit();
                    break;
                case "ping":
                    _pipe.Send(new { evt = "pong" });
                    break;
            }
        }
        catch
        {
            // 忽略无法解析的命令。
        }
    }

    private async Task StartBleAsync()
    {
        if (_started || string.IsNullOrEmpty(_selfId)) return;
        _started = true;
        try
        {
            _localAddress = await GetLocalAddressAsync();
            var server = new GattServer(_selfId, _selfName);
            server.SessionChanged += OnServerSessionChanged;
            server.FrameReceived += OnServerFrame;
            await server.StartAsync();
            _server = server;

            var scanner = new BleScanner();
            scanner.Found += OnFound;
            scanner.Error += message => EmitState(message);
            scanner.Start();
            _scanner = scanner;

            EmitState(null);
            EmitDevices();
            _heartbeatCts = new CancellationTokenSource();
            _ = HeartbeatLoopAsync(_heartbeatCts.Token);
            _ = Task.Run(async () =>
            {
                await Task.Delay(1000);
                EmitState(null);
            });
        }
        catch (Exception error)
        {
            _started = false;
            EmitState(error.Message);
        }
    }

    private void Teardown()
    {
        _started = false;
        _heartbeatCts?.Cancel();
        _heartbeatCts?.Dispose();
        _heartbeatCts = null;
        _lastBeat.Clear();
        _lastConnectAttempt.Clear();
        _scanner?.Stop();
        _scanner = null;
        _server?.Stop();
        _server = null;
        foreach (var link in _clients.Values)
        {
            link.Dispose();
        }
        _clients.Clear();
        _devices.Clear();
        _addressToId.Clear();
        _idToAddress.Clear();
        _introspecting.Clear();
        foreach (var id in _pending.Keys)
        {
            if (_pending.TryRemove(id, out _))
            {
                _pipe.Send(new { evt = "failed", id, reason = "蓝牙已关闭" });
            }
        }
        _seen.Clear();
        while (_seenOrder.TryDequeue(out _)) { }
    }

    private static async Task<ulong> GetLocalAddressAsync()
    {
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            return adapter?.BluetoothAddress ?? 0UL;
        }
        catch
        {
            return 0UL;
        }
    }

    private void OnFound(ulong address, short rssi, string localName)
    {
        if (address == 0 || address == _localAddress) return;
        if (_addressToId.TryGetValue(address, out var knownId))
        {
            if (_devices.TryGetValue(knownId, out var device))
            {
                device.rssi = rssi;
                if (!string.IsNullOrWhiteSpace(localName) && device.name == device.id)
                {
                    device.name = localName;
                }
                ScheduleEmit();
            }
            MaybeAutoConnect(knownId);
            return;
        }
        if (!_introspecting.TryAdd(address, 0)) return;
        _ = Task.Run(async () =>
        {
            try
            {
                using var link = new GattClientLink(address);
                if (await link.ConnectAsync())
                {
                    RegisterPeer(address, link.PeerId, link.PeerName, link.PeerPlatform, rssi);
                }
            }
            catch
            {
                // 单台设备识别失败不影响其他设备。
            }
            finally
            {
                _introspecting.TryRemove(address, out _);
            }
        });
    }

    private void RegisterPeer(ulong address, string id, string name, string platform, int rssi)
    {
        if (string.IsNullOrEmpty(id) || id == _selfId) return;
        _addressToId[address] = id;
        _idToAddress[id] = address;
        var device = _devices.GetOrAdd(id, _ => new PeerDevice { id = id });
        device.name = string.IsNullOrWhiteSpace(name) ? id : name;
        device.platform = string.IsNullOrWhiteSpace(platform) ? "android" : platform;
        device.address = FormatAddress(address);
        device.rssi = rssi;
        device.connected = _clients.TryGetValue(id, out var link) && link.Ready;
        ScheduleEmit();
        MaybeAutoConnect(id);
    }

    // 发现即自动建链。桌面端一直开着扫描，统一由这一侧主动连，
    // 手机退到后台停扫描也能被连上；已经有人连进来时不再重复建链。
    private void MaybeAutoConnect(string peerId)
    {
        if (string.IsNullOrEmpty(peerId) || peerId == _selfId) return;
        if (_server?.HasSubscriber == true) return;
        if (_devices.TryGetValue(peerId, out var device) && (device.connected || device.connecting)) return;
        if (_clients.TryGetValue(peerId, out var link) && link.Ready) return;
        var now = NowSec();
        if (_lastConnectAttempt.TryGetValue(peerId, out var last) && now - last < 15) return;
        _lastConnectAttempt[peerId] = now;
        _ = ConnectAsync(peerId);
    }

    private static long NowSec() => DateTimeOffset.UtcNow.ToUnixTimeSeconds();

    private async Task HeartbeatLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(HeartbeatInterval, token);
            }
            catch
            {
                break;
            }
            try
            {
                await BeatOnceAsync();
            }
            catch
            {
                // 单次心跳失败由各自的超时逻辑收尾。
            }
        }
    }

    private async Task BeatOnceAsync()
    {
        var ping = Proto.Frame(JsonSerializer.Serialize(
            new WireMessage { Type = "h", From = _selfId }, Proto.Json));
        var server = _server;
        if (server != null && server.HasSubscriber)
        {
            _ = server.NotifyAsync(ping);
        }
        foreach (var pair in _clients.ToArray())
        {
            var link = pair.Value;
            if (!link.Ready) continue;
            if (!await link.SendAsync(ping))
            {
                DropClient(pair.Key, link);
            }
        }
        var now = NowSec();
        foreach (var pair in _clients.ToArray())
        {
            if (!_lastBeat.TryGetValue(pair.Key, out var at))
            {
                _lastBeat[pair.Key] = now;
                continue;
            }
            if (now - at > (long)HeartbeatTimeout.TotalSeconds)
            {
                DropClient(pair.Key, pair.Value);
            }
        }
        foreach (var device in _devices.Values)
        {
            if (!device.connected || _clients.ContainsKey(device.id)) continue;
            if (_lastBeat.TryGetValue(device.id, out var at) &&
                now - at > (long)HeartbeatTimeout.TotalSeconds)
            {
                device.connected = false;
                ScheduleEmit();
            }
        }
    }

    private void DropClient(string peerId, GattClientLink link)
    {
        if (_clients.TryGetValue(peerId, out var existing) && existing == link)
        {
            _clients.TryRemove(peerId, out _);
        }
        link.Dispose();
        _lastBeat.TryRemove(peerId, out _);
        if (_devices.TryGetValue(peerId, out var device))
        {
            device.connected = _server?.HasSubscriber == true;
            ScheduleEmit();
        }
    }

    private static string FormatAddress(ulong address)
    {
        var bytes = BitConverter.GetBytes(address);
        return string.Join(":", bytes.Take(6).Select(b => b.ToString("X2")));
    }

    private void OnServerSessionChanged(string sessionKey, bool active)
    {
        // 对端连进来时先用蓝牙地址反查是哪台设备，界面不必等首帧就能亮起来。
        _ = Task.Run(async () =>
        {
            var id = await ResolveSessionPeerAsync(sessionKey);
            if (string.IsNullOrEmpty(id)) return;
            _sessionPeer[sessionKey] = id;
            if (active) _lastBeat[id] = NowSec();
            if (_devices.TryGetValue(id, out var device))
            {
                device.connected = active;
                ScheduleEmit();
            }
        });
    }

    private async Task<string?> ResolveSessionPeerAsync(string sessionKey)
    {
        if (_sessionPeer.TryGetValue(sessionKey, out var known)) return known;
        try
        {
            using var device = await BluetoothLEDevice.FromIdAsync(sessionKey);
            if (device == null) return null;
            return _addressToId.TryGetValue(device.BluetoothAddress, out var id) ? id : null;
        }
        catch
        {
            return null;
        }
    }

    private void OnServerFrame(string sessionKey, string json)
    {
        var sender = HandleFrame(json, null);
        if (string.IsNullOrEmpty(sender)) return;
        _sessionPeer[sessionKey] = sender;
        if (_devices.TryGetValue(sender, out var device))
        {
            device.connected = true;
            ScheduleEmit();
        }
    }

    private void OnClientFrame(GattClientLink link, string json)
    {
        HandleFrame(json, link);
        if (_devices.TryGetValue(link.PeerId, out var device))
        {
            device.connected = true;
            ScheduleEmit();
        }
    }

    private string? HandleFrame(string json, GattClientLink? link)
    {
        WireMessage? message;
        try
        {
            message = JsonSerializer.Deserialize<WireMessage>(json);
        }
        catch
        {
            return null;
        }
        if (message == null) return null;

        if (!string.IsNullOrEmpty(message.From) && message.From != _selfId)
        {
            _lastBeat[message.From] = NowSec();
        }
        if (message.Type == "h")
        {
            Reply(JsonSerializer.Serialize(new WireMessage { Type = "b", From = _selfId }, Proto.Json), link);
            // 心跳也带 from，外设侧收到就能确认对端会话对应哪台设备。
            return message.From == _selfId ? null : message.From;
        }
        if (message.Type == "b") return message.From == _selfId ? null : message.From;

        if (message.Type == "a")
        {
            if (_pending.TryRemove(message.Id, out _))
            {
                _pipe.Send(new { evt = "sent", id = message.Id });
            }
            return null;
        }
        if (message.Type != "m" || string.IsNullOrEmpty(message.Id)) return null;

        Ack(message.Id, link);

        var senderId = message.From;
        if (string.IsNullOrEmpty(senderId) || senderId == _selfId) return null;
        if (!Remember(message.Id)) return senderId;

        var peer = _devices.GetOrAdd(senderId, _ => new PeerDevice { id = senderId });
        if (!string.IsNullOrWhiteSpace(message.FromName)) peer.name = message.FromName;
        if (!string.IsNullOrWhiteSpace(message.Platform)) peer.platform = message.Platform;
        peer.connected = true;

        _pipe.Send(new
        {
            evt = "message",
            id = message.Id,
            from = senderId,
            fromName = peer.name,
            platform = peer.platform,
            body = message.Body,
            ts = message.Ts,
        });
        ScheduleEmit();
        return senderId;
    }

    private void Ack(string messageId, GattClientLink? link)
    {
        Reply(JsonSerializer.Serialize(new WireMessage { Type = "a", Id = messageId }, Proto.Json), link);
    }

    private void Reply(string json, GattClientLink? link)
    {
        var frame = Proto.Frame(json);
        if (link != null && link.Ready)
        {
            _ = link.SendAsync(frame);
            return;
        }
        var server = _server;
        if (server != null)
        {
            _ = server.NotifyAsync(frame);
        }
    }

    private bool Remember(string id)
    {
        if (!_seen.TryAdd(id, 0)) return false;
        _seenOrder.Enqueue(id);
        while (_seenOrder.Count > 512 && _seenOrder.TryDequeue(out var old))
        {
            _seen.TryRemove(old, out _);
        }
        return true;
    }

    private async Task ConnectAsync(string peerId)
    {
        if (string.IsNullOrEmpty(peerId)) return;
        if (_clients.TryGetValue(peerId, out var existing) && existing.Ready) return;
        if (_devices.TryGetValue(peerId, out var device))
        {
            device.connecting = true;
            ScheduleEmit();
        }
        try
        {
            await EnsureClientAsync(peerId);
        }
        finally
        {
            if (_devices.TryGetValue(peerId, out device))
            {
                device.connecting = false;
                ScheduleEmit();
            }
        }
    }

    private async Task<GattClientLink?> EnsureClientAsync(string peerId)
    {
        if (_clients.TryGetValue(peerId, out var existing) && existing.Ready)
        {
            return existing;
        }
        await _connectLock.WaitAsync();
        try
        {
            if (_clients.TryGetValue(peerId, out existing) && existing.Ready)
            {
                return existing;
            }
            if (existing != null)
            {
                existing.Dispose();
                _clients.TryRemove(peerId, out _);
            }
            if (!_idToAddress.TryGetValue(peerId, out var address)) return null;
            var link = new GattClientLink(address);
            link.FrameReceived += frame => OnClientFrame(link, frame);
            link.Disconnected += () =>
            {
                _clients.TryRemove(peerId, out _);
                if (_devices.TryGetValue(peerId, out var device))
                {
                    device.connected = false;
                    ScheduleEmit();
                }
            };
            if (!await link.ConnectAsync())
            {
                link.Dispose();
                return null;
            }
            _clients[peerId] = link;
            _lastBeat[peerId] = NowSec();
            if (_devices.TryGetValue(peerId, out var peer))
            {
                peer.connected = true;
                peer.name = string.IsNullOrWhiteSpace(link.PeerName) ? peer.name : link.PeerName;
                peer.platform = string.IsNullOrWhiteSpace(link.PeerPlatform) ? peer.platform : link.PeerPlatform;
            }
            ScheduleEmit();
            return link;
        }
        finally
        {
            _connectLock.Release();
        }
    }

    private async Task SendMessageAsync(string to, string id, string body, long ts)
    {
        var message = new WireMessage
        {
            Type = "m",
            Id = id,
            From = _selfId,
            FromName = _selfName,
            Platform = "windows",
            Body = body,
            Ts = ts,
        };
        var frame = Proto.Frame(JsonSerializer.Serialize(message, Proto.Json));
        _pending[id] = new PendingSend { Frame = frame };

        try
        {
            if (!await TrySendAsync(to, frame))
            {
                if (await WaitAckAsync(id, 4000)) return;
                if (!await TrySendAsync(to, frame))
                {
                    Fail(id, "蓝牙未连接");
                    return;
                }
            }
            if (await WaitAckAsync(id, 6000)) return;

            // 通知可能被丢弃，重发一次。
            if (await TrySendAsync(to, frame) && await WaitAckAsync(id, 6000)) return;
            Fail(id, "蓝牙未确认");
        }
        finally
        {
            _pending.TryRemove(id, out _);
        }
    }

    private async Task<bool> WaitAckAsync(string id, int timeoutMs)
    {
        var waited = 0;
        while (waited < timeoutMs)
        {
            if (!_pending.ContainsKey(id)) return true;
            await Task.Delay(100);
            waited += 100;
        }
        return !_pending.ContainsKey(id);
    }

    private void Fail(string id, string reason)
    {
        if (_pending.TryRemove(id, out _))
        {
            _pipe.Send(new { evt = "failed", id, reason });
        }
    }

    private async Task<bool> TrySendAsync(string peerId, byte[] frame)
    {
        var server = _server;
        if (server != null && server.HasSubscriber)
        {
            if (await server.NotifyAsync(frame)) return true;
        }
        var link = await EnsureClientAsync(peerId);
        if (link != null && link.Ready)
        {
            return await link.SendAsync(frame);
        }
        return false;
    }

    private void ScheduleEmit()
    {
        if (Interlocked.Exchange(ref _emitScheduled, 1) == 1) return;
        _ = Task.Run(async () =>
        {
            await Task.Delay(300);
            Interlocked.Exchange(ref _emitScheduled, 0);
            EmitDevices();
        });
    }

    private void EmitDevices()
    {
        var devices = _devices.Values
            .OrderByDescending(d => d.rssi)
            .ToList();
        _pipe.Send(new { evt = "devices", devices });
    }

    private void EmitState(string? error)
    {
        _pipe.Send(new
        {
            evt = "state",
            on = _started,
            advertising = _server != null,
            scanning = _scanner?.Running ?? false,
            error,
        });
    }
}
