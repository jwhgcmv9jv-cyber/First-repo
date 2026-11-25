using System.IO.Pipes;
using System.Text;
using System.Text.Json;

namespace AppLimiter;

public sealed class Request
{
    public string Cmd { get; set; } = "";
    public string? Code { get; set; }
    public string? NewCode { get; set; }
    public string? Master { get; set; }
    public string? NewMaster { get; set; }
    public string? TargetExe { get; set; }
    public string? TargetPath { get; set; }
    public string? OriginalFileName { get; set; }
    public int? DailyFreeMinutes { get; set; }
    public int? ExtensionMinutes { get; set; }
    public int? RemainingMinutes { get; set; }
}

public sealed class Response
{
    public bool Ok { get; set; }
    public string? Message { get; set; }
    public string TargetExe { get; set; } = "";
    public string TargetPath { get; set; } = "";
    public long RemainingSeconds { get; set; }
    public bool Running { get; set; }
    public long BlockedSeq { get; set; }
    public int DailyFreeMinutes { get; set; }
    public int ExtensionMinutes { get; set; }
}

public static class PipeClient
{
    public const string PipeName = "AppLimiterPipe";

    /// <summary>Отправляет запрос службе. null — служба недоступна.</summary>
    public static Response? Send(Request req, int timeoutMs = 1500)
    {
        try
        {
            using var c = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut);
            c.Connect(timeoutMs);
            var data = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(req) + "\n");
            c.Write(data);
            c.Flush();
            using var r = new StreamReader(c, Encoding.UTF8);
            var task = r.ReadLineAsync();
            if (!task.Wait(timeoutMs * 4)) return null;
            return task.Result == null ? null : JsonSerializer.Deserialize<Response>(task.Result);
        }
        catch { return null; }
    }
}
