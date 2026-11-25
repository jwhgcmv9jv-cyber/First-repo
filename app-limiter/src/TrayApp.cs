using System.Diagnostics;

namespace AppLimiter;

/// <summary>Значок в области уведомлений: показывает остаток времени,
/// открывает окно ввода кода, когда служба закрыла приложение.</summary>
public sealed class TrayApp : ApplicationContext
{
    readonly NotifyIcon _icon;
    readonly SynchronizationContext _ui;
    Response? _st;
    long _seenSeq = -1;
    UnlockForm? _unlock;
    bool _dialogOpen;

    public static void Run()
    {
        using var mutex = new Mutex(true, @"Local\AppLimiterTray", out bool first);
        if (!first) return;
        Application.Run(new TrayApp());
    }

    public static string Fmt(long sec) =>
        sec >= 3600 ? $"{sec / 3600} ч {sec % 3600 / 60:00} мин" : $"{sec / 60}:{sec % 60:00}";

    TrayApp()
    {
        _ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        var menu = new ContextMenuStrip();
        menu.Items.Add("Ввести код (продлить время)…", null, (_, _) => ShowUnlock());
        menu.Items.Add("Настройки (мастер-код)…", null, (_, _) => ShowSettings());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Удалить AppLimiter…", null, (_, _) => StartUninstall());

        _icon = new NotifyIcon
        {
            Icon = SystemIcons.Shield,
            Text = "AppLimiter",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _icon.DoubleClick += (_, _) => ShowUnlock();

        var t = new Thread(PollLoop) { IsBackground = true };
        t.Start();
    }

    void PollLoop()
    {
        long prevRemain = long.MaxValue;
        while (true)
        {
            var st = PipeClient.Send(new Request { Cmd = "status" });
            _ui.Post(_ => OnStatus(st, prevRemain), null);
            if (st != null) prevRemain = st.Running ? st.RemainingSeconds : long.MaxValue;
            Thread.Sleep(1000);
        }
    }

    void OnStatus(Response? st, long prev)
    {
        _st = st;
        if (st == null) { _icon.Text = "AppLimiter: служба недоступна"; return; }

        var text = $"{st.TargetExe}: осталось {Fmt(st.RemainingSeconds)}";
        _icon.Text = text.Length > 63 ? text[..63] : text;

        if (st.Running)
        {
            foreach (var mark in new[] { 300L, 60L })
                if (prev > mark && st.RemainingSeconds <= mark)
                    _icon.ShowBalloonTip(5000, "AppLimiter",
                        $"{st.TargetExe} будет закрыт через {mark / 60} мин.", ToolTipIcon.Warning);
        }

        if (_seenSeq < 0) _seenSeq = st.BlockedSeq;      // первый опрос — без окна
        else if (st.BlockedSeq != _seenSeq)
        {
            _seenSeq = st.BlockedSeq;
            ShowUnlock();
        }
    }

    void ShowUnlock()
    {
        if (_unlock != null) { _unlock.Activate(); return; }
        if (_dialogOpen) return;
        _unlock = new UnlockForm(_st ?? PipeClient.Send(new Request { Cmd = "status" }));
        _unlock.FormClosed += (_, _) => { _unlock.Dispose(); _unlock = null; };
        _unlock.Show();
        _unlock.Activate();
    }

    void ShowSettings()
    {
        if (_dialogOpen || _unlock != null) return;
        _dialogOpen = true;
        try
        {
            string master = "";
            using (var p = new CodePromptForm("AppLimiter — настройки", "Введите мастер-код.", code =>
                   {
                       var r = PipeClient.Send(new Request { Cmd = "master", Master = code });
                       return r == null ? "Служба AppLimiter недоступна." : r.Ok ? null : r.Message;
                   }))
            {
                if (p.ShowDialog() != DialogResult.OK) return;
                master = p.Code;
            }

            var cur = PipeClient.Send(new Request { Cmd = "status" });
            while (true)
            {
                using var f = new ConfigForm(false, cur);
                if (f.ShowDialog() != DialogResult.OK) return;
                var t = f.Target!;
                var r = PipeClient.Send(new Request
                {
                    Cmd = "settings",
                    Master = master,
                    TargetExe = t.Exe,
                    TargetPath = t.Path,
                    OriginalFileName = t.OriginalFileName,
                    DailyFreeMinutes = f.DailyFree,
                    ExtensionMinutes = f.Extension,
                    RemainingMinutes = f.RemainingMinutes,
                    NewMaster = f.MasterCode.Length > 0 ? f.MasterCode : null,
                    NewCode = f.AccessCode.Length > 0 ? f.AccessCode : null,
                });
                if (r == null) { MessageBox.Show("Служба AppLimiter недоступна.", "AppLimiter"); return; }
                MessageBox.Show(r.Message, "AppLimiter", MessageBoxButtons.OK,
                    r.Ok ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
                if (r.Ok) return;
                cur = r;
            }
        }
        finally { _dialogOpen = false; }
    }

    static void StartUninstall()
    {
        try
        {
            Process.Start(new ProcessStartInfo(Environment.ProcessPath!, "--uninstall")
            { UseShellExecute = true, Verb = "runas" });
        }
        catch { /* пользователь отменил UAC */ }
    }
}
