using System.Diagnostics;
using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.ServiceProcess;
using System.Text;
using System.Text.Json;

namespace AppLimiter;

/// <summary>Служба Windows (LocalSystem): считает время, закрывает приложение, проверяет коды.
/// Обычный пользователь не может её остановить или изменить её настройки.</summary>
public sealed class LimiterService : ServiceBase
{
    public const string Name = "AppLimiter";

    readonly object _lock = new();
    readonly CancellationTokenSource _cts = new();
    readonly Dictionary<string, bool> _pathMatch = new(StringComparer.OrdinalIgnoreCase);
    Config _cfg = new();
    long _blockedSeq;
    bool _running;
    DateTime _lastSave = DateTime.UtcNow;
    readonly List<Thread> _threads = new();

    public LimiterService()
    {
        ServiceName = Name;
        CanStop = true;
        CanShutdown = true;
        CanPauseAndContinue = false;
    }

    protected override void OnStart(string[] args)
    {
        _cfg = ConfigStore.Load() ?? new Config();
        StartThread(MonitorLoop);
        StartThread(() => PipeLoop().GetAwaiter().GetResult());
        StartThread(TrayLoop);
    }

    void StartThread(Action a)
    {
        var t = new Thread(() => { try { a(); } catch (OperationCanceledException) { } }) { IsBackground = true };
        _threads.Add(t);
        t.Start();
    }

    protected override void OnStop() => Shutdown();
    protected override void OnShutdown() => Shutdown();

    void Shutdown()
    {
        _cts.Cancel();
        lock (_lock) Save();
    }

    void Save()
    {
        try { ConfigStore.Save(_cfg); _lastSave = DateTime.UtcNow; } catch { }
    }

    // ---------------- Учёт времени ----------------

    void MonitorLoop()
    {
        var sw = Stopwatch.StartNew();
        double acc = 0;
        while (!_cts.IsCancellationRequested)
        {
            Thread.Sleep(500);
            double dt = sw.Elapsed.TotalSeconds;
            sw.Restart();
            try
            {
                lock (_lock)
                {
                    var today = Config.Today();
                    if (string.CompareOrdinal(today, _cfg.LastResetDate) > 0)
                    {
                        _cfg.LastResetDate = today;
                        _cfg.RemainingSeconds = _cfg.DailyFreeMinutes * 60L;
                        Save();
                    }

                    var procs = FindTargets();
                    _running = procs.Count > 0;
                    if (_running && _cfg.RemainingSeconds > 0)
                    {
                        acc += dt;
                        while (acc >= 1 && _cfg.RemainingSeconds > 0) { _cfg.RemainingSeconds--; acc -= 1; }
                    }
                    if (_running && _cfg.RemainingSeconds <= 0)
                    {
                        foreach (var p in procs) { try { p.Kill(); } catch { } }
                        _running = false;
                        _blockedSeq++;
                        Save();
                    }
                    foreach (var p in procs) p.Dispose();
                    if (DateTime.UtcNow - _lastSave > TimeSpan.FromSeconds(15)) Save();
                }
            }
            catch { }
        }
    }

    List<Process> FindTargets()
    {
        var result = new List<Process>();
        if (string.IsNullOrEmpty(_cfg.TargetExe)) return result;
        var targetName = Path.GetFileNameWithoutExtension(_cfg.TargetExe);
        foreach (var p in Process.GetProcesses())
        {
            bool match = false;
            try
            {
                if (p.SessionId != 0 && p.Id != Environment.ProcessId)
                {
                    if (p.ProcessName.Equals(targetName, StringComparison.OrdinalIgnoreCase)) match = true;
                    else
                    {
                        var path = Native.GetProcessPath(p.Id);
                        if (path != null)
                        {
                            if (!_pathMatch.TryGetValue(path, out match))
                            {
                                match = MatchPath(path);
                                if (_pathMatch.Count > 5000) _pathMatch.Clear();
                                _pathMatch[path] = match;
                            }
                        }
                    }
                }
            }
            catch { }
            if (match) result.Add(p); else p.Dispose();
        }
        return result;
    }

    bool MatchPath(string path)
    {
        if (_cfg.TargetPath.Length > 0 && path.Equals(_cfg.TargetPath, StringComparison.OrdinalIgnoreCase)) return true;
        if (_cfg.OriginalFileName.Length == 0) return false;
        try
        {
            var orig = FileVersionInfo.GetVersionInfo(path).OriginalFilename?.Trim() ?? "";
            if (orig.EndsWith(".mui", StringComparison.OrdinalIgnoreCase)) orig = orig[..^4];
            return orig.Equals(_cfg.OriginalFileName, StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    // ---------------- Значок в трее для пользователя ----------------

    void TrayLoop()
    {
        var exe = Environment.ProcessPath!;
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                uint sid = Native.WTSGetActiveConsoleSessionId();
                if (sid != 0xFFFFFFFF && sid != 0)
                {
                    bool has = false;
                    foreach (var p in Process.GetProcessesByName("AppLimiter"))
                    {
                        try { if (p.SessionId == sid) has = true; } catch { }
                        p.Dispose();
                    }
                    if (!has) Native.StartInSession(sid, exe, "--tray");
                }
            }
            catch { }
            _cts.Token.WaitHandle.WaitOne(5000);
        }
    }

    // ---------------- Канал связи с интерфейсом ----------------

    async Task PipeLoop()
    {
        var sec = new PipeSecurity();
        sec.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null),
            PipeAccessRights.ReadWrite, AccessControlType.Allow));
        sec.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            PipeAccessRights.FullControl, AccessControlType.Allow));
        sec.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            PipeAccessRights.FullControl, AccessControlType.Allow));

        var ct = _cts.Token;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var server = NamedPipeServerStreamAcl.Create(PipeClient.PipeName, PipeDirection.InOut, 1,
                    PipeTransmissionMode.Byte, PipeOptions.Asynchronous, 4096, 4096, sec);
                await server.WaitForConnectionAsync(ct);
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeout.CancelAfter(5000);
                using var reader = new StreamReader(server, Encoding.UTF8, false, 4096, leaveOpen: true);
                var line = await reader.ReadLineAsync(timeout.Token);
                Response resp;
                try { resp = Handle(JsonSerializer.Deserialize<Request>(line ?? "") ?? new Request()); }
                catch { resp = new Response { Ok = false, Message = "Неверный запрос." }; }
                var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(resp) + "\n");
                await server.WriteAsync(bytes, timeout.Token);
                await server.FlushAsync(timeout.Token);
                try { server.WaitForPipeDrain(); } catch { }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch { await Task.Delay(200); }
        }
    }

    Response Status(bool ok = true, string? msg = null) => new()
    {
        Ok = ok,
        Message = msg,
        TargetExe = _cfg.TargetExe,
        TargetPath = _cfg.TargetPath,
        RemainingSeconds = Math.Max(0, _cfg.RemainingSeconds),
        Running = _running,
        BlockedSeq = _blockedSeq,
        DailyFreeMinutes = _cfg.DailyFreeMinutes,
        ExtensionMinutes = _cfg.ExtensionMinutes,
    };

    Response Handle(Request q)
    {
        lock (_lock)
        {
            switch (q.Cmd)
            {
                case "status":
                    return Status();

                case "unlock":
                {
                    if (!Codes.IsAccessCode(q.Code)) return Status(false, "Код — это ровно 4 цифры.");
                    if (!Codes.IsAccessCode(q.NewCode)) return Status(false, "Новый код должен состоять ровно из 4 цифр.");
                    if (q.NewCode == q.Code) return Status(false, "Новый код должен отличаться от текущего.");
                    var lockMsg = _cfg.CheckLockout();
                    if (lockMsg != null) return Status(false, lockMsg);
                    if (!Codes.Verify(q.Code, _cfg.CodeHash, _cfg.CodeSalt))
                    {
                        var m = _cfg.RegisterFailure("Код");
                        Save();
                        return Status(false, m);
                    }
                    if (Codes.Verify(q.NewCode, _cfg.MasterHash, _cfg.MasterSalt))
                        return Status(false, "Новый код не должен совпадать с мастер-кодом.");
                    _cfg.FailedAttempts = 0;
                    (_cfg.CodeHash, _cfg.CodeSalt) = Codes.Hash(q.NewCode!);
                    _cfg.RemainingSeconds = Math.Max(0, _cfg.RemainingSeconds) + _cfg.ExtensionMinutes * 60L;
                    Save();
                    return Status(true, $"Добавлено {_cfg.ExtensionMinutes} мин. Код изменён — в следующий раз понадобится новый.");
                }

                case "master":
                {
                    var err = CheckMaster(q.Master);
                    return err == null ? Status() : Status(false, err);
                }

                case "settings":
                {
                    var err = CheckMaster(q.Master);
                    if (err != null) return Status(false, err);
                    // сначала всё проверяем, потом применяем
                    TargetInfo? t = null;
                    if (q.TargetExe != null)
                    {
                        t = TargetInfo.FromInput(q.TargetExe, out var terr);
                        if (t == null) return Status(false, terr);
                    }
                    bool newMaster = !string.IsNullOrEmpty(q.NewMaster), newCode = !string.IsNullOrEmpty(q.NewCode);
                    if (newMaster && !Codes.IsMasterCode(q.NewMaster)) return Status(false, "Мастер-код: от 4 до 8 цифр.");
                    if (newCode && !Codes.IsAccessCode(q.NewCode)) return Status(false, "Код доступа: ровно 4 цифры.");
                    var effMaster = newMaster ? q.NewMaster : q.Master;
                    if (newCode && q.NewCode == effMaster)
                        return Status(false, "Код доступа не должен совпадать с мастер-кодом.");
                    if (newMaster && !newCode && Codes.Verify(q.NewMaster, _cfg.CodeHash, _cfg.CodeSalt))
                        return Status(false, "Мастер-код не должен совпадать с кодом доступа.");

                    if (t != null)
                    {
                        _cfg.TargetExe = t.Exe;
                        _cfg.TargetPath = q.TargetPath ?? "";
                        _cfg.OriginalFileName = q.OriginalFileName ?? "";
                        _pathMatch.Clear();
                    }
                    if (q.DailyFreeMinutes is int d) _cfg.DailyFreeMinutes = Math.Clamp(d, 0, 1440);
                    if (q.ExtensionMinutes is int e) _cfg.ExtensionMinutes = Math.Clamp(e, 1, 1440);
                    if (q.RemainingMinutes is int r) _cfg.RemainingSeconds = Math.Clamp(r, 0, 1440) * 60L;
                    if (newMaster) (_cfg.MasterHash, _cfg.MasterSalt) = Codes.Hash(q.NewMaster!);
                    if (newCode) (_cfg.CodeHash, _cfg.CodeSalt) = Codes.Hash(q.NewCode!);
                    Save();
                    return Status(true, "Настройки сохранены.");
                }

                default:
                    return Status(false, "Неизвестная команда.");
            }
        }
    }

    string? CheckMaster(string? master)
    {
        var lockMsg = _cfg.CheckLockout();
        if (lockMsg != null) return lockMsg;
        if (Codes.Verify(master, _cfg.MasterHash, _cfg.MasterSalt)) { _cfg.FailedAttempts = 0; return null; }
        var m = _cfg.RegisterFailure("Мастер-код");
        Save();
        return m;
    }
}
