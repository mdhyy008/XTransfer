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

    // 帧 = 2 字节大端长度 + UTF-8 JSON。
    public static byte[] Frame(string json)
    {
        var payload = Encoding.UTF8.GetBytes(json);
        var framed = new byte[payload.Length + 2];
        framed[0] = (byte)(payload.Length >> 8);
        framed[1] = (byte)(payload.Length & 0xFF);
        payload.CopyTo(framed, 2);
        return framed;
    }
}

internal sealed class FrameAssembler
{
    private readonly List<byte> _buffer = new();

    public IEnumerable<string> Feed(byte[] chunk)
    {
        _buffer.AddRange(chunk);
        var frames = new List<string>();
        while (_buffer.Count >= 2)
        {
            var length = (_buffer[0] << 8) | _buffer[1];
            if (length <= 0 || length > 8192)
            {
                _buffer.Clear();
                break;
            }
            if (_buffer.Count < length + 2) break;
            var payload = _buffer.GetRange(2, length).ToArray();
            _buffer.RemoveRange(0, length + 2);
            frames.Add(Encoding.UTF8.GetString(payload));
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
