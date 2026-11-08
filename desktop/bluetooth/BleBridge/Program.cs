namespace BleBridge;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        var pipe = GetArg(args, "--pipe");
        if (string.IsNullOrWhiteSpace(pipe))
        {
            return;
        }
        ApplicationConfiguration.Initialize();
        var context = new BridgeContext(pipe);
        context.Begin();
        Application.Run(context);
    }

    private static string? GetArg(string[] args, string name)
    {
        for (var i = 0; i < args.Length - 1; i++)
        {
            if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase))
            {
                return args[i + 1];
            }
        }
        return null;
    }
}
