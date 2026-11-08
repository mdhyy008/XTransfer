using System.IO.Pipes;
using System.Text;
using System.Text.Json;

namespace BleBridge;

internal sealed class PipeLink
{
    private readonly string _name;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private NamedPipeClientStream? _pipe;
    private StreamWriter? _writer;

    public event Action<string>? LineReceived;
    public event Action? Closed;

    public PipeLink(string name)
    {
        _name = name;
    }

    public async Task<bool> ConnectAsync(TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            var pipe = new NamedPipeClientStream(".", _name, PipeDirection.InOut, PipeOptions.Asynchronous);
            try
            {
                await pipe.ConnectAsync(1000);
                _pipe = pipe;
                _writer = new StreamWriter(pipe, new UTF8Encoding(false)) { AutoFlush = true };
                _ = Task.Run(ReadLoopAsync);
                return true;
            }
            catch
            {
                pipe.Dispose();
                await Task.Delay(300);
            }
        }
        return false;
    }

    private async Task ReadLoopAsync()
    {
        try
        {
            using var reader = new StreamReader(_pipe!, new UTF8Encoding(false));
            while (true)
            {
                var line = await reader.ReadLineAsync();
                if (line == null) break;
                if (line.Length == 0) continue;
                LineReceived?.Invoke(line);
            }
        }
        catch
        {
            // 管道断开按正常退出处理。
        }
        Closed?.Invoke();
    }

    public void Send(object payload)
    {
        var writer = _writer;
        if (writer == null) return;
        var line = JsonSerializer.Serialize(payload, Proto.Json);
        _writeLock.Wait();
        try
        {
            writer.WriteLine(line);
        }
        catch
        {
            // 对端已经断开，交给 Closed 收尾。
        }
        finally
        {
            _writeLock.Release();
        }
    }
}
