using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Windows.Security.Cryptography;
using Windows.Storage.Streams;

namespace BleBridge;

internal static class Proto
{
    public static readonly Guid Service = new("0000f0e0-0000-1000-8000-00805f9b34fb");
    public static readonly Guid Info = new("0000f0e1-0000-1000-8000-00805f9b34fb");
    public static readonly Guid Rx = new("0000f0e2-0000-1000-8000-00805f9b34fb");
    public static readonly Guid Tx = new("0000f0e3-0000-1000-8000-00805f9b34fb");

    public static readonly JsonSerializerOptions Json = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        WriteIndented = false
    };

    public static IBuffer Buffer(byte[] data) => CryptographicBuffer.CreateFromByteArray(data);

    public static byte[] Bytes(IBuffer buffer)
    {
        CryptographicBuffer.CopyToByteArray(buffer, out var data);
        return data ?? Array.Empty<byte>();
    }

    // 帧体首字节：'{' 是 JSON 控制帧，0x01 是文件数据块。
    public const byte TypeData = 0x01;

    // 帧 = 2 字节大端长度 + 帧体。
    public static byte[] Wrap(byte[] body)
    {
        var framed = new byte[body.Length + 2];
        framed[0] = (byte)(body.Length >> 8);
        framed[1] = (byte)(body.Length & 0xFF);
        body.CopyTo(framed, 2);
        return framed;
    }

    public static byte[] Frame(string json) => Wrap(Encoding.UTF8.GetBytes(json));

    // 数据块帧体：类型 1B + id 长度 1B + id + 序号 4B + 数据。
    public static byte[] DataFrame(string id, int seq, byte[] data, int offset, int count)
    {
        var idBytes = Encoding.UTF8.GetBytes(id);
        var body = new byte[2 + idBytes.Length + 4 + count];
        body[0] = TypeData;
        body[1] = (byte)idBytes.Length;
        idBytes.CopyTo(body, 2);
        var p = 2 + idBytes.Length;
        body[p] = (byte)(seq >> 24);
        body[p + 1] = (byte)(seq >> 16);
        body[p + 2] = (byte)(seq >> 8);
        body[p + 3] = (byte)seq;
        Array.Copy(data, offset, body, p + 4, count);
        return Wrap(body);
    }
}

internal sealed class FrameAssembler
{
    private readonly List<byte> _buffer = new();

    public IEnumerable<byte[]> Feed(byte[] chunk)
    {
        _buffer.AddRange(chunk);
        var frames = new List<byte[]>();
        while (_buffer.Count >= 2)
        {
            var length = (_buffer[0] << 8) | _buffer[1];
            // 文件数据块最大 512 字节，留出余量。
            if (length <= 0 || length > 4096)
            {
                _buffer.Clear();
                break;
            }
            if (_buffer.Count < length + 2) break;
            var payload = _buffer.GetRange(2, length).ToArray();
            _buffer.RemoveRange(0, length + 2);
            frames.Add(payload);
        }
        return frames;
    }
}

internal sealed class WireMessage
{
    [JsonPropertyName("t")] public string Type { get; set; } = "m";
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("from")] public string From { get; set; } = "";
    [JsonPropertyName("fromName")] public string FromName { get; set; } = "";
    [JsonPropertyName("platform")] public string Platform { get; set; } = "";
    [JsonPropertyName("body")] public string Body { get; set; } = "";
    [JsonPropertyName("ts")] public long Ts { get; set; }
}

internal sealed class WireInfo
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("platform")] public string Platform { get; set; } = "";
}

internal sealed class WireFile
{
    [JsonPropertyName("t")] public string Type { get; set; } = "";
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("from")] public string From { get; set; } = "";
    [JsonPropertyName("fromName")] public string FromName { get; set; } = "";
    [JsonPropertyName("platform")] public string Platform { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("size")] public long Size { get; set; }
    [JsonPropertyName("ts")] public long Ts { get; set; }
    [JsonPropertyName("reason")] public string Reason { get; set; } = "";
}
