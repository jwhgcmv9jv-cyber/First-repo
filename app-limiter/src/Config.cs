using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace AppLimiter;

/// <summary>Настройки и состояние. Хранится в %ProgramData%\AppLimiter\config.json
/// (доступ только SYSTEM и Администраторам). Коды хранятся только в виде хэшей.</summary>
public sealed class Config
{
    public string TargetExe { get; set; } = "";        // например "chrome.exe"
    public string TargetPath { get; set; } = "";       // полный путь (если выбран через «Обзор»)
    public string OriginalFileName { get; set; } = ""; // из свойств файла — ловит переименованные копии
    public int DailyFreeMinutes { get; set; }          // бесплатные минуты в день (0 = всегда нужен код)
    public int ExtensionMinutes { get; set; } = 60;    // сколько минут даёт один ввод кода

    public string MasterHash { get; set; } = "";
    public string MasterSalt { get; set; } = "";
    public string CodeHash { get; set; } = "";
    public string CodeSalt { get; set; } = "";

    public long RemainingSeconds { get; set; }
    public string LastResetDate { get; set; } = "";
    public int FailedAttempts { get; set; }
    public DateTime LockoutUntilUtc { get; set; }

    public const int MaxAttempts = 5;
    public const int LockoutMinutes = 5;

    public string? CheckLockout()
    {
        var left = LockoutUntilUtc - DateTime.UtcNow;
        return left > TimeSpan.Zero
            ? $"Слишком много неверных попыток. Подождите {(int)Math.Ceiling(left.TotalMinutes)} мин."
            : null;
    }

    public string RegisterFailure(string what)
    {
        FailedAttempts++;
        if (FailedAttempts >= MaxAttempts)
        {
            FailedAttempts = 0;
            LockoutUntilUtc = DateTime.UtcNow.AddMinutes(LockoutMinutes);
            return $"{what} неверный. Ввод заблокирован на {LockoutMinutes} мин.";
        }
        return $"{what} неверный. Осталось попыток: {MaxAttempts - FailedAttempts}.";
    }

    public static string Today() => DateTime.Now.ToString("yyyy-MM-dd");
}

public static class ConfigStore
{
    public static readonly string Dir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AppLimiter");
    public static readonly string FilePath = Path.Combine(Dir, "config.json");

    static readonly JsonSerializerOptions Opts = new() { WriteIndented = true };

    public static Config? Load()
    {
        try { return JsonSerializer.Deserialize<Config>(File.ReadAllText(FilePath)); }
        catch { return null; }
    }

    public static void Save(Config c)
    {
        Directory.CreateDirectory(Dir);
        var tmp = FilePath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(c, Opts));
        File.Move(tmp, FilePath, overwrite: true);
    }

    /// <summary>Закрывает папку настроек от обычных пользователей.</summary>
    public static void ProtectDir()
    {
        var di = Directory.CreateDirectory(Dir);
        var sec = new DirectorySecurity();
        sec.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        foreach (var sid in new[] { WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid })
            sec.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid, null),
                FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None, AccessControlType.Allow));
        di.SetAccessControl(sec);
    }
}

public static class Codes
{
    public static bool IsAccessCode(string? s) => s != null && Regex.IsMatch(s, @"^\d{4}$");
    public static bool IsMasterCode(string? s) => s != null && Regex.IsMatch(s, @"^\d{4,8}$");

    public static (string hash, string salt) Hash(string code)
    {
        var salt = RandomNumberGenerator.GetBytes(16);
        var hash = Rfc2898DeriveBytes.Pbkdf2(code, salt, 200_000, HashAlgorithmName.SHA256, 32);
        return (Convert.ToBase64String(hash), Convert.ToBase64String(salt));
    }

    public static bool Verify(string? code, string hash, string salt)
    {
        if (string.IsNullOrEmpty(code) || string.IsNullOrEmpty(hash)) return false;
        try
        {
            var h = Rfc2898DeriveBytes.Pbkdf2(code, Convert.FromBase64String(salt), 200_000, HashAlgorithmName.SHA256, 32);
            return CryptographicOperations.FixedTimeEquals(h, Convert.FromBase64String(hash));
        }
        catch { return false; }
    }
}

/// <summary>Описание выбранного приложения.</summary>
public sealed record TargetInfo(string Exe, string Path, string OriginalFileName)
{
    static readonly string[] Forbidden =
    {
        "applimiter.exe", "explorer.exe", "svchost.exe", "csrss.exe", "winlogon.exe", "wininit.exe",
        "lsass.exe", "services.exe", "smss.exe", "dwm.exe", "system", "taskmgr.exe", "sihost.exe",
        "ctfmon.exe", "fontdrvhost.exe", "logonui.exe", "userinit.exe", "runtimebroker.exe",
    };

    /// <summary>Принимает полный путь к .exe или просто имя файла («chrome.exe»).</summary>
    public static TargetInfo? FromInput(string input, out string? error)
    {
        error = null;
        input = input.Trim().Trim('"');
        if (input.Length == 0) { error = "Укажите приложение (.exe)."; return null; }
        string exe = System.IO.Path.GetFileName(input);
        if (!exe.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) exe += ".exe";
        if (Forbidden.Contains(exe.ToLowerInvariant()))
        { error = $"Нельзя ограничивать системный процесс {exe}."; return null; }

        string path = "", orig = "";
        if (input.Contains('\\') && File.Exists(input))
        {
            path = System.IO.Path.GetFullPath(input);
            try
            {
                orig = FileVersionInfo.GetVersionInfo(path).OriginalFilename?.Trim() ?? "";
                if (orig.EndsWith(".mui", StringComparison.OrdinalIgnoreCase)) orig = orig[..^4];
                if (!orig.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ||
                    Forbidden.Contains(orig.ToLowerInvariant())) orig = "";
            }
            catch { orig = ""; }
        }
        return new TargetInfo(exe, path, orig);
    }
}
