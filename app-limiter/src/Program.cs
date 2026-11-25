using System.ServiceProcess;

namespace AppLimiter;

static class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        var mode = args.Length > 0 ? args[0].ToLowerInvariant() : "";

        if (mode == "--service")
        {
            ServiceBase.Run(new LimiterService());
            return 0;
        }

        ApplicationConfiguration.Initialize();

        switch (mode)
        {
            case "--tray":
                TrayApp.Run();
                return 0;

            case "--uninstall":
                if (!Installer.IsAdmin()) return Installer.RelaunchElevated("--uninstall");
                Installer.Uninstall();
                return 0;

            default:
                if (Installer.IsInstalled())
                {
                    MessageBox.Show(
                        "AppLimiter уже установлен и работает.\n" +
                        "Управление — через значок в области уведомлений (рядом с часами).",
                        "AppLimiter", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return 0;
                }
                if (!Installer.IsAdmin()) return Installer.RelaunchElevated("");
                Installer.Install();
                return 0;
        }
    }
}
