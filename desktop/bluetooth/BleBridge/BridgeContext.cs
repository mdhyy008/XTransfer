using System.Collections.Concurrent;
using System.Text;
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

    private sealed class OutgoingFile
    {
        public string Id = "";
        public string To = "";
        public string Name = "";
        public byte[] Data = Array.Empty<byte>();
        // null = 对方已接受；非空 = 拒绝原因。
        public readonly TaskCompletionSource<string?> Accept = new(TaskCreationOptions.RunContinuationsAsynchronously);
        // null = 对方已确认收完；非空 = 失败原因。
        public readonly TaskCompletionSource<string?> Done = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private sealed class IncomingFile
    {
        public string Id = "";
        public string From = "";
        public string Name = "";
        public long Size;
        public string Dir = "";
        public readonly MemoryStream Buffer = new();
        public long Received;
        public int NextSeq;
        public long LastAt;
        public long LastReport;
        public readonly TaskCompletionSource<(bool ok, string dir)> Decision = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private readonly ConcurrentDictionary<string, OutgoingFile> _outgoingFiles = new();
    private readonly ConcurrentDictionary<string, IncomingFile> _incomingFiles = new();

    private const long FileLimit = 5L * 1024 * 1024;
    private static readonly TimeSpan FileAcceptTimeout = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan FileFinishTimeout = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan FileOfferDecisionTimeout = TimeSpan.FromSeconds(10);
    private const long FileStallSeconds = 30;

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
                case "sendfile":
                {
                    var to = GetString(root, "to");
                    var id = GetString(root, "id");
                    var name = GetString(root, "name");
                    var path = GetString(root, "path");
                    if (to.Length > 0 && id.Length > 0 && path.Length > 0)
                    {
                        _ = SendFileAsync(to, id, name, path);
                    }
                    break;
                }
                case "fileaccept":
                {
                    if (_incomingFiles.TryGetValue(GetString(root, "id"), out var file))
                    {
                        file.Decision.TrySetResult((true, GetString(root, "dir")));
                    }
                    break;
                }
                case "filereject":
                {
                    if (_incomingFiles.TryGetValue(GetString(root, "id"), out var file))
                    {
                        file.Decision.TrySetResult((false, GetString(root, "reason")));
                    }
                    break;
                }
                case "filecancel":
                {
                    var id = GetString(root, "id");
                    if (id.Length > 0) _ = CancelFileAsync(id);
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
            await EmitRadioAsync();
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
        foreach (var pair in _outgoingFiles.ToArray())
        {
            if (_outgoingFiles.TryRemove(pair.Key, out var file))
            {
                file.Accept.TrySetResult("蓝牙已关闭");
                file.Done.TrySetResult("蓝牙已关闭");
            }
        }
        foreach (var pair in _incomingFiles.ToArray())
        {
            if (_incomingFiles.TryRemove(pair.Key, out var file))
            {
                file.Decision.TrySetResult((false, "蓝牙已关闭"));
                _pipe.Send(new { evt = "filefailed", id = pair.Key, reason = "蓝牙已关闭" });
            }
        }
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

    private static string GetString(JsonElement root, string name) =>
        root.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.String
            ? (el.GetString() ?? "")
            : "";

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
        foreach (var pair in _incomingFiles.ToArray())
        {
            if (now - pair.Value.LastAt <= FileStallSeconds) continue;
            if (_incomingFiles.TryRemove(pair.Key, out _))
            {
                _pipe.Send(new { evt = "filefailed", id = pair.Key, reason = "接收超时" });
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

    private void OnServerFrame(string sessionKey, byte[] payload)
    {
        var sender = HandlePayload(payload, null);
        if (string.IsNullOrEmpty(sender)) return;
        _sessionPeer[sessionKey] = sender;
        if (_devices.TryGetValue(sender, out var device))
        {
            device.connected = true;
            ScheduleEmit();
        }
    }

    private void OnClientFrame(GattClientLink link, byte[] payload)
    {
        HandlePayload(payload, link);
        if (_devices.TryGetValue(link.PeerId, out var device))
        {
            device.connected = true;
            ScheduleEmit();
        }
    }

    private string? HandlePayload(byte[] payload, GattClientLink? link)
    {
        if (payload.Length == 0) return null;
        if (payload[0] == Proto.TypeData)
        {
            HandleFileChunk(payload, link);
            return null;
        }
        return HandleFrame(Encoding.UTF8.GetString(payload), link);
    }

    private string? HandleFrame(string json, GattClientLink? link)
    {
        WireFile? control;
        try
        {
            control = JsonSerializer.Deserialize<WireFile>(json);
        }
        catch
        {
            return null;
        }
        if (control == null) return null;
        switch (control.Type)
        {
            case "fo":
                return HandleFileOffer(control, link);
            case "fe":
                HandleFileFinish(control, link);
                return null;
            case "fa":
            case "fr":
            case "fk":
            case "fx":
            case "fc":
                HandleFileControl(control, link);
                return null;
        }

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

    // ---------- 文件 ----------

    private string? HandleFileOffer(WireFile message, GattClientLink? link)
    {
        var id = message.Id;
        var from = message.From;
        if (string.IsNullOrEmpty(id) || string.IsNullOrEmpty(from) || from == _selfId) return null;
        _lastBeat[from] = NowSec();

        var reason = message.Size <= 0 || message.Size > FileLimit
            ? "文件过大"
            : string.IsNullOrWhiteSpace(message.Name) ? "文件名为空" : null;
        if (reason != null)
        {
            Reply(JsonSerializer.Serialize(new { t = "fr", id, reason }, Proto.Json), link);
            return from;
        }

        var incoming = new IncomingFile
        {
            Id = id,
            From = from,
            Name = message.Name,
            Size = message.Size,
            LastAt = NowSec(),
        };
        if (!_incomingFiles.TryAdd(id, incoming))
        {
            Reply(JsonSerializer.Serialize(new { t = "fr", id, reason = "重复文件" }, Proto.Json), link);
            return from;
        }

        var peer = _devices.GetOrAdd(from, _ => new PeerDevice { id = from });
        if (!string.IsNullOrWhiteSpace(message.FromName)) peer.name = message.FromName;
        if (!string.IsNullOrWhiteSpace(message.Platform)) peer.platform = message.Platform;
        peer.connected = true;

        _pipe.Send(new
        {
            evt = "fileoffer",
            id,
            from,
            fromName = peer.name,
            platform = peer.platform,
            name = message.Name,
            size = message.Size,
            ts = message.Ts,
        });

        // 发文件的一侧要等这里答复 fa/fr 才开始传数据，超时就当对方不收。
        _ = Task.Run(async () =>
        {
            var done = await Task.WhenAny(incoming.Decision.Task, Task.Delay(FileOfferDecisionTimeout));
            (bool ok, string dir) decision = done == incoming.Decision.Task
                ? await incoming.Decision.Task
                : (false, "对方未接收");
            if (!_incomingFiles.TryGetValue(id, out var current) || current != incoming) return;
            if (decision.ok && !string.IsNullOrWhiteSpace(decision.dir))
            {
                incoming.Dir = decision.dir;
                Reply(JsonSerializer.Serialize(new { t = "fa", id }, Proto.Json), link);
                return;
            }
            _incomingFiles.TryRemove(id, out _);
            var reason = string.IsNullOrWhiteSpace(decision.dir) ? "对方未接收" : decision.dir;
            Reply(JsonSerializer.Serialize(new { t = "fr", id, reason }, Proto.Json), link);
        });

        ScheduleEmit();
        return from;
    }

    private void HandleFileChunk(byte[] body, GattClientLink? link)
    {
        if (body.Length < 8) return;
        var idLen = body[1];
        if (idLen <= 0 || body.Length < 2 + idLen + 4) return;
        var id = Encoding.UTF8.GetString(body, 2, idLen);
        var p = 2 + idLen;
        var seq = (body[p] << 24) | (body[p + 1] << 16) | (body[p + 2] << 8) | body[p + 3];
        if (!_incomingFiles.TryGetValue(id, out var file)) return;

        var count = body.Length - p - 4;
        lock (file)
        {
            if (!_incomingFiles.TryGetValue(id, out var current) || current != file) return;
            if (seq != file.NextSeq)
            {
                _incomingFiles.TryRemove(id, out _);
                _pipe.Send(new { evt = "filefailed", id, reason = "数据块顺序错误" });
                Reply(JsonSerializer.Serialize(new { t = "fx", id, reason = "数据块顺序错误" }, Proto.Json), link);
                return;
            }
            file.Buffer.Write(body, p + 4, count);
            file.Received += count;
            file.NextSeq += 1;
            file.LastAt = NowSec();
            var now = Environment.TickCount64;
            if (now - file.LastReport >= 200)
            {
                file.LastReport = now;
                _pipe.Send(new { evt = "fileprogress", id, received = file.Received, total = file.Size, dir = "in" });
            }
        }
    }

    private void HandleFileFinish(WireFile message, GattClientLink? link)
    {
        var id = message.Id;
        if (string.IsNullOrEmpty(id)) return;
        if (!_incomingFiles.TryRemove(id, out var file)) return;

        string? reason = null;
        byte[] bytes;
        lock (file)
        {
            bytes = file.Buffer.ToArray();
            if (file.Received != file.Size || bytes.LongLength != file.Size) reason = "文件不完整";
            else if (string.IsNullOrWhiteSpace(file.Dir)) reason = "未选择保存位置";
        }
        if (reason != null)
        {
            _pipe.Send(new { evt = "filefailed", id, reason });
            Reply(JsonSerializer.Serialize(new { t = "fx", id, reason }, Proto.Json), link);
            return;
        }

        string path;
        try
        {
            path = SaveFile(file.Dir, file.Name, bytes);
        }
        catch (Exception error)
        {
            _pipe.Send(new { evt = "filefailed", id, reason = error.Message });
            Reply(JsonSerializer.Serialize(new { t = "fx", id, reason = "保存失败" }, Proto.Json), link);
            return;
        }

        _pipe.Send(new { evt = "fileprogress", id, received = file.Size, total = file.Size, dir = "in" });
        _pipe.Send(new { evt = "filedone", id, name = file.Name, path, bytes = file.Size });
        Reply(JsonSerializer.Serialize(new { t = "fk", id }, Proto.Json), link);
    }

    private void HandleFileControl(WireFile message, GattClientLink? link)
    {
        var id = message.Id;
        if (string.IsNullOrEmpty(id)) return;
        switch (message.Type)
        {
            case "fa":
                if (_outgoingFiles.TryGetValue(id, out var accepted)) accepted.Accept.TrySetResult(null);
                break;
            case "fr":
            {
                var reason = string.IsNullOrWhiteSpace(message.Reason) ? "对方未接收" : message.Reason;
                if (_outgoingFiles.TryGetValue(id, out var rejected))
                {
                    rejected.Accept.TrySetResult(reason);
                    rejected.Done.TrySetResult(reason);
                }
                break;
            }
            case "fk":
                if (_outgoingFiles.TryGetValue(id, out var finished)) finished.Done.TrySetResult(null);
                break;
            case "fx":
            {
                var reason = string.IsNullOrWhiteSpace(message.Reason) ? "接收失败" : message.Reason;
                if (_outgoingFiles.TryGetValue(id, out var failed)) failed.Done.TrySetResult(reason);
                break;
            }
            case "fc":
                if (_incomingFiles.TryRemove(id, out _))
                {
                    _pipe.Send(new { evt = "filefailed", id, reason = "对方已取消" });
                }
                if (_outgoingFiles.TryGetValue(id, out var canceled)) canceled.Done.TrySetResult("已取消");
                break;
        }
    }

    private async Task SendFileAsync(string to, string id, string name, string path)
    {
        if (_outgoingFiles.ContainsKey(id)) return;
        byte[] data;
        try
        {
            data = await File.ReadAllBytesAsync(path);
        }
        catch
        {
            _pipe.Send(new { evt = "filefailed", id, reason = "读取文件失败" });
            return;
        }
        if (data.Length == 0)
        {
            _pipe.Send(new { evt = "filefailed", id, reason = "文件为空" });
            return;
        }
        if (data.Length > FileLimit)
        {
            _pipe.Send(new { evt = "filefailed", id, reason = "单个文件不能超过 5 MB" });
            return;
        }
        if (string.IsNullOrWhiteSpace(name)) name = Path.GetFileName(path);

        var outgoing = new OutgoingFile { Id = id, To = to, Name = name, Data = data };
        _outgoingFiles[id] = outgoing;
        try
        {
            var offer = Proto.Frame(JsonSerializer.Serialize(new
            {
                t = "fo",
                id,
                from = _selfId,
                fromName = _selfName,
                platform = "windows",
                name,
                size = (long)data.Length,
                ts = NowSec(),
            }, Proto.Json));
            if (!await TrySendAsync(to, offer)) throw new InvalidOperationException("蓝牙未连接");

            var rejection = await AwaitTimeout(outgoing.Accept.Task, FileAcceptTimeout, "对方未接收");
            if (rejection != null) throw new InvalidOperationException(rejection);

            var chunk = FileChunk(to, Encoding.UTF8.GetByteCount(id));
            var offset = 0;
            var seq = 0;
            var reported = 0L;
            while (offset < data.Length)
            {
                var count = Math.Min(chunk, data.Length - offset);
                if (!await TrySendAsync(to, Proto.DataFrame(id, seq, data, offset, count)))
                {
                    throw new InvalidOperationException("蓝牙连接已断开");
                }
                offset += count;
                seq += 1;
                var now = Environment.TickCount64;
                if (now - reported >= 200 || offset >= data.Length)
                {
                    reported = now;
                    _pipe.Send(new { evt = "fileprogress", id, sent = (long)offset, total = (long)data.Length, dir = "out" });
                }
            }

            var finish = Proto.Frame(JsonSerializer.Serialize(new { t = "fe", id }, Proto.Json));
            if (!await TrySendAsync(to, finish)) throw new InvalidOperationException("蓝牙连接已断开");
            var failure = await AwaitTimeout(outgoing.Done.Task, FileFinishTimeout, "等待对方确认超时");
            if (failure != null) throw new InvalidOperationException(failure);

            _pipe.Send(new { evt = "filesent", id, name, size = (long)data.Length });
        }
        catch (Exception error)
        {
            _pipe.Send(new { evt = "filefailed", id, reason = error.Message });
            _ = TrySendAsync(to, Proto.Frame(JsonSerializer.Serialize(new { t = "fc", id }, Proto.Json)));
        }
        finally
        {
            _outgoingFiles.TryRemove(id, out _);
        }
    }

    private static async Task<string?> AwaitTimeout(Task<string?> task, TimeSpan timeout, string fallback)
    {
        var done = await Task.WhenAny(task, Task.Delay(timeout));
        return done == task ? await task : fallback;
    }

    private int FileChunk(string peerId, int idLen)
    {
        var size = 0;
        var server = _server;
        if (server != null && server.HasSubscriber) size = server.MaxNotificationSize;
        if (size <= 0 && _clients.TryGetValue(peerId, out var link) && link.Ready) size = link.ChunkSize;
        if (size <= 0) return 20;
        size = Math.Clamp(size, 20, 512);
        return Math.Max(20, size - 6 - idLen);
    }

    private async Task CancelFileAsync(string id)
    {
        if (_outgoingFiles.TryRemove(id, out var outgoing))
        {
            outgoing.Accept.TrySetResult("已取消");
            outgoing.Done.TrySetResult("已取消");
            await TrySendAsync(outgoing.To, Proto.Frame(JsonSerializer.Serialize(new { t = "fc", id }, Proto.Json)));
        }
        if (_incomingFiles.TryRemove(id, out _))
        {
            _pipe.Send(new { evt = "filefailed", id, reason = "已取消" });
        }
    }

    private static string SaveFile(string dir, string name, byte[] data)
    {
        Directory.CreateDirectory(dir);
        var safe = SafeName(name);
        var path = Path.Combine(dir, safe);
        var stem = Path.GetFileNameWithoutExtension(safe);
        var ext = Path.GetExtension(safe);
        var index = 1;
        while (File.Exists(path))
        {
            path = Path.Combine(dir, $"{stem} ({index}){ext}");
            index += 1;
        }
        File.WriteAllBytes(path, data);
        return path;
    }

    private static string SafeName(string name)
    {
        var raw = Path.GetFileName(name.Trim());
        var bad = Path.GetInvalidFileNameChars();
        var clean = new string(raw.Select(c => bad.Contains(c) ? '_' : c).ToArray()).Trim();
        if (string.IsNullOrEmpty(clean)) clean = "file";
        return clean.Length > 120 ? clean[..120] : clean;
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

    private async Task EmitRadioAsync()
    {
        var version = "";
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter != null)
            {
                version = adapter.IsExtendedAdvertisingSupported
                    ? "5.0+"
                    : adapter.IsLowEnergySupported ? "4.x" : "";
            }
        }
        catch
        {
            // 取不到就留空，界面显示“未知”。
        }
        var address = _localAddress == 0 ? "" : FormatAddress(_localAddress);
        _pipe.Send(new { evt = "radio", version, address });
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
