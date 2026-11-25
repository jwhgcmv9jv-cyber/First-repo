using System.Diagnostics;
using System.Security.Principal;
using System.ServiceProcess;
using Microsoft.Win32;

namespace AppLimiter;

public static class Installer
{
    const string UninstallKey = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AppLimiter";
    static readonly string InstallDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "AppLimiter");
    static readonly string InstalledExe = Path.Combine(InstallDir, "AppLimiter.exe");

    public static bool IsAdmin() =>
        new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);

    public static int RelaunchElevated(string args)
    {
        try
        {
            Process.Start(new ProcessStartInfo(Environment.ProcessPath!, args) { UseShellExecute = true, Verb = "runas" });
            return 0;
        }
        catch
        {
            MessageBox.Show("Нужны права администратора.", "AppLimiter", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return 1;
        }
    }

    public static bool IsInstalled() =>
        ServiceController.GetServices().Any(s => s.ServiceName == LimiterService.Name);

    static int Run(string file, params string[] args)
    {
        var psi = new ProcessStartInfo(file) { UseShellExecute = false, CreateNoWindow = true };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi)!;
        p.WaitForExit(30000);
        return p.HasExited ? p.ExitCode : -1;
    }

    // ---------------- Установка ----------------

    public static void Install()
    {
        using var form = new ConfigForm(setup: true);
        if (form.ShowDialog() != DialogResult.OK) return;
        var t = form.Target!;

        try
        {
            // 1. Копируем программу в Program Files (обычный пользователь не сможет её изменить)
            Directory.CreateDirectory(InstallDir);
            var self = Environment.ProcessPath!;
            if (!string.Equals(Path.GetFullPath(self), InstalledExe, StringComparison.OrdinalIgnoreCase))
                File.Copy(self, InstalledExe, overwrite: true);

            // 2. Настройки — в защищённую папку
            ConfigStore.ProtectDir();
            var cfg = new Config
            {
                TargetExe = t.Exe,
                TargetPath = t.Path,
                OriginalFileName = t.OriginalFileName,
                DailyFreeMinutes = form.DailyFree,
                ExtensionMinutes = form.Extension,
                RemainingSeconds = form.DailyFree * 60L,
                LastResetDate = Config.Today(),
            };
            (cfg.MasterHash, cfg.MasterSalt) = Codes.Hash(form.MasterCode);
            (cfg.CodeHash, cfg.CodeSalt) = Codes.Hash(form.AccessCode);
            ConfigStore.Save(cfg);

            // 3. Служба: автозапуск, перезапуск при сбое
            var sc = Path.Combine(Environment.SystemDirectory, "sc.exe");
            int rc = Run(sc, "create", LimiterService.Name, "binPath=", $"\"{InstalledExe}\" --service",
                "start=", "auto", "DisplayName=", "AppLimiter");
            if (rc != 0) throw new Exception($"Не удалось создать службу (sc create, код {rc}).");
            Run(sc, "description", LimiterService.Name, "Ограничение времени работы приложения");
            Run(sc, "failure", LimiterService.Name, "reset=", "0", "actions=", "restart/2000/restart/2000/restart/2000");
            Run(sc, "failureflag", LimiterService.Name, "1");
            using (var svc = new ServiceController(LimiterService.Name))
            {
                svc.Start();
                svc.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(20));
            }

            // 4. Запись в «Установленные приложения» — удаление пойдёт через мастер-код
            using (var k = Registry.LocalMachine.CreateSubKey(UninstallKey))
            {
                k.SetValue("DisplayName", "AppLimiter");
                k.SetValue("DisplayVersion", "1.0.0");
                k.SetValue("Publisher", "AppLimiter");
                k.SetValue("DisplayIcon", InstalledExe);
                k.SetValue("InstallLocation", InstallDir);
                k.SetValue("UninstallString", $"\"{InstalledExe}\" --uninstall");
                k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            }

            MessageBox.Show(
                $"AppLimiter установлен.\n\nОграничено: {t.Exe}\n" +
                $"Бесплатно в день: {form.DailyFree} мин, за один ввод кода: {form.Extension} мин.\n\n" +
                "Значок появится в области уведомлений (рядом с часами).\n" +
                "Удаление — только с мастер-кодом (Параметры → Приложения или меню значка).",
                "AppLimiter", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show("Ошибка установки: " + ex.Message, "AppLimiter", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    // ---------------- Удаление ----------------

    public static void Uninstall()
    {
        bool serviceExists = IsInstalled();

        // Проверяем мастер-код. Если служба работает — через неё (она же ведёт счётчик попыток).
        using (var prompt = new CodePromptForm("AppLimiter — удаление",
                   "Для удаления AppLimiter введите мастер-код, заданный при установке.", CheckMasterForUninstall))
        {
            if (prompt.ShowDialog() != DialogResult.OK) return;
        }

        try
        {
            var sc = Path.Combine(Environment.SystemDirectory, "sc.exe");
            if (serviceExists)
            {
                try
                {
                    using var svc = new ServiceController(LimiterService.Name);
                    if (svc.Status != ServiceControllerStatus.Stopped)
                    {
                        svc.Stop();
                        svc.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(20));
                    }
                }
                catch { }
                Run(sc, "delete", LimiterService.Name);
            }

            foreach (var p in Process.GetProcessesByName("AppLimiter"))
            {
                try { if (p.Id != Environment.ProcessId) p.Kill(); } catch { }
                p.Dispose();
            }

            Registry.LocalMachine.DeleteSubKeyTree(UninstallKey, throwOnMissingSubKey: false);
            try { Directory.Delete(ConfigStore.Dir, recursive: true); } catch { }

            // Сам exe удалить, пока он запущен, нельзя — удаляем папку после выхода.
            if (Directory.Exists(InstallDir))
                Process.Start(new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "cmd.exe"),
                    $"/c ping 127.0.0.1 -n 4 > nul & rmdir /s /q \"{InstallDir}\"")
                { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Path.GetTempPath() });

            MessageBox.Show("AppLimiter удалён.", "AppLimiter", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show("Ошибка удаления: " + ex.Message, "AppLimiter", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    static string? CheckMasterForUninstall(string code)
    {
        var r = PipeClient.Send(new Request { Cmd = "master", Master = code });
        if (r != null) return r.Ok ? null : r.Message;

        // Служба не запущена — проверяем по файлу настроек.
        var cfg = ConfigStore.Load();
        if (cfg == null) return null; // настроек нет — защищать нечего
        var lockMsg = cfg.CheckLockout();
        if (lockMsg != null) return lockMsg;
        if (Codes.Verify(code, cfg.MasterHash, cfg.MasterSalt)) return null;
        var m = cfg.RegisterFailure("Мастер-код");
        try { ConfigStore.Save(cfg); } catch { }
        return m;
    }
}
