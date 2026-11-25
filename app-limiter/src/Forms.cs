namespace AppLimiter;

/// <summary>Базовое окно-диалог с таблицей «подпись — поле».</summary>
public class DialogBase : Form
{
    protected readonly TableLayoutPanel Grid;
    protected readonly Label Info;
    protected readonly Button OkButton, CancelBtn;

    public DialogBase(string title, string info)
    {
        Text = title;
        Font = new Font("Segoe UI", 10f);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        TopMost = true;
        ShowInTaskbar = true;
        Padding = new Padding(12);

        var root = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, Dock = DockStyle.Fill, WrapContents = false };
        Info = new Label { Text = info, AutoSize = true, MaximumSize = new Size(460, 0), Margin = new Padding(0, 0, 0, 10) };
        Grid = new TableLayoutPanel { ColumnCount = 2, AutoSize = true };
        Grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        Grid.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

        var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, AutoSize = true, Anchor = AnchorStyles.Right, Margin = new Padding(0, 10, 0, 0) };
        CancelBtn = new Button { Text = "Отмена", AutoSize = true, DialogResult = DialogResult.Cancel };
        OkButton = new Button { Text = "OK", AutoSize = true };
        OkButton.Click += (_, _) => { if (OnOk()) { DialogResult = DialogResult.OK; Close(); } };
        buttons.Controls.Add(CancelBtn);
        buttons.Controls.Add(OkButton);
        AcceptButton = OkButton;
        CancelButton = CancelBtn;

        root.Controls.Add(Info);
        root.Controls.Add(Grid);
        root.Controls.Add(buttons);
        Controls.Add(root);
    }

    protected virtual bool OnOk() => true;

    protected T Row<T>(string label, T control) where T : Control
    {
        Grid.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 6, 10, 6) });
        control.Anchor = AnchorStyles.Left;
        Grid.Controls.Add(control);
        return control;
    }

    protected static TextBox Pin(int maxLen) => new()
    {
        UseSystemPasswordChar = true, MaxLength = maxLen, Width = 120,
    };

    protected static NumericUpDown Minutes(int min, int max, int value) => new()
    {
        Minimum = min, Maximum = max, Value = Math.Clamp(value, min, max), Width = 90,
    };

    protected void Error(string msg) =>
        MessageBox.Show(this, msg, Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
}

/// <summary>Запрос одного кода (мастер-кода).</summary>
public sealed class CodePromptForm : DialogBase
{
    readonly TextBox _code;
    readonly Func<string, string?> _check;
    public string Code => _code.Text;

    /// <param name="check">возвращает текст ошибки или null, если код верный</param>
    public CodePromptForm(string title, string info, Func<string, string?> check) : base(title, info)
    {
        _check = check;
        _code = Row("Мастер-код:", Pin(8));
    }

    protected override bool OnOk()
    {
        if (!Codes.IsMasterCode(_code.Text)) { Error("Мастер-код — от 4 до 8 цифр."); return false; }
        var err = _check(_code.Text);
        if (err != null) { Error(err); _code.Clear(); return false; }
        return true;
    }
}

/// <summary>Продление времени: текущий код + обязательная смена кода.</summary>
public sealed class UnlockForm : DialogBase
{
    readonly TextBox _code, _new1, _new2;

    public UnlockForm(Response? st) : base("AppLimiter — продление времени", Describe(st))
    {
        _code = Row("Текущий код:", Pin(4));
        _new1 = Row("Новый код:", Pin(4));
        _new2 = Row("Новый код ещё раз:", Pin(4));
    }

    static string Describe(Response? st) => st == null
        ? "Служба AppLimiter недоступна."
        : (st.RemainingSeconds <= 0
              ? $"Время работы {st.TargetExe} закончилось.\n"
              : $"Осталось {TrayApp.Fmt(st.RemainingSeconds)} для {st.TargetExe}.\n")
          + $"Введите текущий 4-значный код — будет добавлено {st.ExtensionMinutes} мин.\n" +
            "Код одноразовый: сразу задайте новый код, он понадобится в следующий раз.";

    protected override bool OnOk()
    {
        if (!Codes.IsAccessCode(_code.Text)) { Error("Текущий код — ровно 4 цифры."); return false; }
        if (!Codes.IsAccessCode(_new1.Text)) { Error("Новый код — ровно 4 цифры."); return false; }
        if (_new1.Text != _new2.Text) { Error("Новые коды не совпадают."); return false; }
        if (_new1.Text == _code.Text) { Error("Новый код должен отличаться от текущего."); return false; }
        var r = PipeClient.Send(new Request { Cmd = "unlock", Code = _code.Text, NewCode = _new1.Text });
        if (r == null) { Error("Служба AppLimiter недоступна."); return false; }
        if (!r.Ok) { Error(r.Message ?? "Ошибка."); _code.Clear(); return false; }
        MessageBox.Show(this, r.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
        return true;
    }
}

/// <summary>Первичная настройка (setup = true) или изменение настроек (по мастер-коду).</summary>
public sealed class ConfigForm : DialogBase
{
    readonly bool _setup;
    readonly TextBox _target, _m1, _m2, _c1, _c2;
    readonly NumericUpDown _daily, _ext;
    readonly NumericUpDown? _remain;

    public TargetInfo? Target { get; private set; }
    public int DailyFree => (int)_daily.Value;
    public int Extension => (int)_ext.Value;
    public int? RemainingMinutes => _remain == null ? null : (int)_remain.Value;
    public string MasterCode => _m1.Text;
    public string AccessCode => _c1.Text;

    public ConfigForm(bool setup, Response? current = null) : base(
        setup ? "AppLimiter — первая настройка" : "AppLimiter — настройки",
        setup
            ? "Выберите приложение, которое нужно ограничить, и задайте коды.\n" +
              "• Мастер-код (4–8 цифр) — для настроек и удаления программы. Запомните его!\n" +
              "• Код доступа (4 цифры) — для продления времени. После каждого ввода его нужно менять."
            : "Пустые поля кодов — оставить как есть.")
    {
        _setup = setup;
        var targetPanel = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = Padding.Empty };
        _target = new TextBox { Width = 260, Text = current?.TargetPath is { Length: > 0 } p ? p : current?.TargetExe ?? "" };
        var browse = new Button { Text = "Обзор…", AutoSize = true };
        browse.Click += (_, _) =>
        {
            using var dlg = new OpenFileDialog { Filter = "Программы (*.exe)|*.exe", Title = "Выберите приложение" };
            if (dlg.ShowDialog(this) == DialogResult.OK) _target.Text = dlg.FileName;
        };
        targetPanel.Controls.Add(_target);
        targetPanel.Controls.Add(browse);
        Row("Приложение (.exe):", targetPanel);

        _daily = Row("Бесплатных минут в день:", Minutes(0, 1440, current?.DailyFreeMinutes ?? 0));
        _ext = Row("Минут за один ввод кода:", Minutes(1, 1440, current?.ExtensionMinutes ?? 60));
        if (!setup)
            _remain = Row("Осталось сегодня (мин):", Minutes(0, 1440, (int)((current?.RemainingSeconds ?? 0) / 60)));

        _m1 = Row(setup ? "Мастер-код:" : "Новый мастер-код:", Pin(8));
        _m2 = Row("Мастер-код ещё раз:", Pin(8));
        _c1 = Row(setup ? "Код доступа:" : "Новый код доступа:", Pin(4));
        _c2 = Row("Код доступа ещё раз:", Pin(4));
    }

    protected override bool OnOk()
    {
        Target = TargetInfo.FromInput(_target.Text, out var err);
        if (Target == null) { Error(err!); return false; }
        if (_target.Text.Contains('\\') && Target.Path.Length == 0)
        { Error("Файл не найден. Укажите существующий .exe или только имя (например chrome.exe)."); return false; }

        bool needM = _setup || _m1.Text.Length > 0 || _m2.Text.Length > 0;
        bool needC = _setup || _c1.Text.Length > 0 || _c2.Text.Length > 0;
        if (needM)
        {
            if (!Codes.IsMasterCode(_m1.Text)) { Error("Мастер-код — от 4 до 8 цифр."); return false; }
            if (_m1.Text != _m2.Text) { Error("Мастер-коды не совпадают."); return false; }
        }
        if (needC)
        {
            if (!Codes.IsAccessCode(_c1.Text)) { Error("Код доступа — ровно 4 цифры."); return false; }
            if (_c1.Text != _c2.Text) { Error("Коды доступа не совпадают."); return false; }
        }
        if (needM && needC && _m1.Text == _c1.Text) { Error("Мастер-код и код доступа должны отличаться."); return false; }
        return true;
    }
}
