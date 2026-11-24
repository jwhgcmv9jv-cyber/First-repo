package ru.irbis.irremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    // ---------------------------------------------------------------------
    // Кнопки и коды по умолчанию.
    // Источник: база IrCode Finder, пульт #500202 «IRBIS Promethean Interactive
    // Board», NEC 38 кГц, адрес 0x807F. На конкретной модели коды могут
    // отличаться — их можно изменить в «Настройках» или найти «Подбором».
    // ---------------------------------------------------------------------
    static final int DEFAULT_ADDRESS = 0x807F;

    static final class Key {
        final String id, label;
        final int defCmd;        // -1 = код неизвестен
        final boolean repeat;    // повторять при удержании
        Key(String id, String label, int defCmd, boolean repeat) {
            this.id = id; this.label = label; this.defCmd = defCmd; this.repeat = repeat;
        }
    }

    static final Map<String, Key> KEYS = new LinkedHashMap<>();
    static {
        add(new Key("power",  "⏻ Питание",      0x18, false));
        add(new Key("input",  "⇄ Источник",     0xB0, false));
        add(new Key("menu",   "☰ Меню",         0x02, false));
        add(new Key("up",     "▲",              0x62, true));
        add(new Key("down",   "▼",              0x68, true));
        add(new Key("left",   "◀",              0xE2, true));
        add(new Key("right",  "▶",              0xA8, true));
        add(new Key("ok",     "OK",             0xAA, false));
        add(new Key("vol_dn", "🔉 Громк. −",     0x08, true));
        add(new Key("vol_up", "🔊 Громк. +",     0x28, true));
        add(new Key("freeze", "❄ Стоп‑кадр",    0x48, false));
        add(new Key("blank",  "⬛ Пустой экран", 0x20, false));
        add(new Key("home",   "⌂ Домой",        -1,   false));
        add(new Key("back",   "↩ Назад",        -1,   false));
        add(new Key("extra1", "★ Доп. 1",       -1,   false));
        add(new Key("extra2", "★ Доп. 2",       -1,   false));
    }
    private static void add(Key k) { KEYS.put(k.id, k); }

    // Цвета
    static final int BG = 0xFF12151B, PANEL = 0xFF1C212A, BTN = 0xFF2A313D, BTN_DOWN = 0xFF3D4757,
            TEXT = 0xFFE8ECF2, MUTED = 0xFF8B95A5, ACCENT = 0xFFE0612B, RED = 0xFF8A2A2A, OK_GREEN = 0xFF2FA36B;

    private ConsumerIrManager ir;
    private SharedPreferences prefs;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        prefs = getSharedPreferences("irbis", MODE_PRIVATE);
        getWindow().getDecorView().setBackgroundColor(BG);
        setContentView(buildUi());
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sender.shutdownNow();
    }

    // ------------------------------------------------------------ ИК
    boolean hasIr() {
        try { return ir != null && ir.hasIrEmitter(); } catch (Exception e) { return false; }
    }

    int address() { return prefs.getInt("addr", DEFAULT_ADDRESS); }

    int command(Key k) { return prefs.getInt("cmd_" + k.id, k.defCmd); }

    /** Отправить один кадр. Если предыдущий ещё передаётся — пропустить (не копим очередь). */
    void transmit(final long code) {
        if (!hasIr()) {
            toast("На этом телефоне нет ИК‑передатчика");
            return;
        }
        if (!busy.compareAndSet(false, true)) return;
        sender.execute(new Runnable() {
            @Override public void run() {
                try {
                    ir.transmit(Nec.CARRIER, Nec.frame(code));
                } catch (final Exception e) {
                    ui.post(new Runnable() { @Override public void run() { toast("Ошибка ИК: " + e.getMessage()); } });
                } finally {
                    busy.set(false);
                }
            }
        });
    }

    boolean sendKey(Key k) {
        int cmd = command(k);
        if (cmd < 0) {
            toast("Для «" + k.label + "» код не задан. Найдите его: «Подбор команд».");
            return false;
        }
        transmit(Nec.code(address(), cmd));
        return true;
    }

    // ------------------------------------------------------------ Интерфейс
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable bg(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Irbis ИК‑пульт");
        title.setTextColor(TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(0, dp(4), 0, dp(12));
        root.addView(status);

        root.addView(row(keyBtn("power", RED, 56), keyBtn("input", BTN, 56), keyBtn("menu", BTN, 56)));

        // Крестовина
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setBackground(bg(PANEL, 20));
        pad.setPadding(dp(10), dp(10), dp(10), dp(10));
        pad.addView(row(space(), keyBtn("up", BTN, 76), space()));
        pad.addView(row(keyBtn("left", BTN, 76), keyBtn("ok", ACCENT, 76), keyBtn("right", BTN, 76)));
        pad.addView(row(space(), keyBtn("down", BTN, 76), space()));
        addWithMargin(root, pad, 6);

        root.addView(row(keyBtn("vol_dn", BTN, 64), keyBtn("vol_up", BTN, 64)));
        root.addView(row(keyBtn("home", BTN, 56), keyBtn("back", BTN, 56)));
        root.addView(row(keyBtn("freeze", BTN, 56), keyBtn("blank", BTN, 56)));
        root.addView(row(keyBtn("extra1", BTN, 56), keyBtn("extra2", BTN, 56)));

        TextView sect = new TextView(this);
        sect.setText("НАСТРОЙКА");
        sect.setTextColor(MUTED);
        sect.setTextSize(12);
        sect.setPadding(0, dp(18), 0, dp(4));
        root.addView(sect);

        root.addView(row(
                actionBtn("Подбор адреса", new View.OnClickListener() { @Override public void onClick(View v) { startAddressScan(); } }),
                actionBtn("Подбор команд", new View.OnClickListener() { @Override public void onClick(View v) { startCommandScan(); } })));
        root.addView(row(
                actionBtn("Коды кнопок", new View.OnClickListener() { @Override public void onClick(View v) { showSettings(); } }),
                actionBtn("Справка", new View.OnClickListener() { @Override public void onClick(View v) { showHelp(); } })));
        return scroll;
    }

    void addWithMargin(LinearLayout parent, View v, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(marginDp), 0, dp(marginDp));
        parent.addView(v, lp);
    }

    LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            r.addView(v, lp);
        }
        return r;
    }

    View space() { return new View(this); }

    Button baseBtn(String text, int color, int heightDp) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color == ACCENT || color == RED ? Color.WHITE : TEXT);
        b.setTextSize(text.length() <= 2 ? 24 : 16);
        b.setBackground(bg(color, 16));
        b.setMinHeight(dp(heightDp));
        b.setMinimumHeight(dp(heightDp));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    Button actionBtn(String text, View.OnClickListener l) {
        Button b = baseBtn(text, PANEL, 52);
        b.setTextSize(15);
        b.setOnClickListener(l);
        return b;
    }

    Button keyBtn(String id, final int color, int heightDp) {
        final Key k = KEYS.get(id);
        final Button b = baseBtn(k.label, color, heightDp);
        final Runnable[] repeater = new Runnable[1];
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        b.setBackground(bg(color == BTN ? BTN_DOWN : darker(color), 16));
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        if (sendKey(k) && k.repeat) {
                            repeater[0] = new Runnable() {
                                @Override public void run() { sendKey(k); ui.postDelayed(this, 150); }
                            };
                            ui.postDelayed(repeater[0], 450);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        b.setBackground(bg(color, 16));
                        if (repeater[0] != null) ui.removeCallbacks(repeater[0]);
                        repeater[0] = null;
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
                        return true;
                }
                return false;
            }
        });
        return b;
    }

    static int darker(int c) {
        return Color.argb(255, Color.red(c) * 3 / 4, Color.green(c) * 3 / 4, Color.blue(c) * 3 / 4);
    }

    void refreshStatus() {
        if (hasIr()) {
            status.setTextColor(OK_GREEN);
            status.setText("● ИК‑передатчик есть · адрес " + Nec.hex(address(), 4) + " · направьте телефон на доску");
        } else {
            status.setTextColor(0xFFD64545);
            status.setText("● На этом телефоне нет ИК‑передатчика — приложение не сможет управлять доской");
        }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    static Integer parseHex(String s, int max) {
        s = s.trim().toLowerCase();
        if (s.startsWith("0x")) s = s.substring(2);
        if (s.isEmpty()) return null;
        try {
            int v = Integer.parseInt(s, 16);
            return (v >= 0 && v <= max) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ Коды кнопок
    void showSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(box);

        final EditText addr = field(box, "Адрес устройства (4 hex‑цифры)", Nec.hex(address(), 4));
        final Map<Key, EditText> fields = new LinkedHashMap<>();
        for (Key k : KEYS.values()) {
            int c = command(k);
            fields.put(k, field(box, k.label + " — команда (hex, пусто = нет)", c < 0 ? "" : Nec.hex(c, 2)));
        }

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Коды кнопок (NEC)")
                .setView(sv)
                .setPositiveButton("Сохранить", null)
                .setNeutralButton("Сбросить", null)
                .setNegativeButton("Отмена", null)
                .show();

        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Integer a = parseHex(addr.getText().toString(), 0xFFFF);
                if (a == null) { toast("Адрес: 1–4 hex‑цифры, например 807F"); return; }
                SharedPreferences.Editor ed = prefs.edit().putInt("addr", a);
                for (Map.Entry<Key, EditText> f : fields.entrySet()) {
                    String t = f.getValue().getText().toString().trim();
                    if (t.isEmpty()) { ed.putInt("cmd_" + f.getKey().id, -1); continue; }
                    Integer c = parseHex(t, 0xFF);
                    if (c == null) { toast("«" + f.getKey().label + "»: команда 00–FF"); return; }
                    ed.putInt("cmd_" + f.getKey().id, c);
                }
                ed.apply();
                refreshStatus();
                d.dismiss();
                toast("Сохранено");
            }
        });
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Вернуть коды Irbis по умолчанию (адрес 0x807F)?")
                        .setPositiveButton("Сбросить", (dd, w) -> {
                            prefs.edit().clear().apply();
                            refreshStatus();
                            d.dismiss();
                            toast("Коды сброшены");
                        })
                        .setNegativeButton("Нет", null)
                        .show();
            }
        });
    }

    EditText field(LinearLayout box, String label, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setPadding(0, dp(10), 0, 0);
        box.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(e);
        return e;
    }

    // ------------------------------------------------------------ Подбор
    /** Общий «сканер»: перебирает список кодов, отправляя по одному с паузой. */
    abstract class Scanner {
        final List<Integer> items;
        int pos = 0;
        boolean paused = false;
        TextView big, small;
        Button pauseBtn;
        AlertDialog dialog;
        final Runnable advance = new Runnable() {
            @Override public void run() { if (!paused) { pos++; tick.run(); } }
        };
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (paused) return;
                fire();
                if (pos < items.size() - 1) {
                    ui.postDelayed(advance, intervalMs());
                } else {
                    setPaused(true);
                    small.setText("Перебор окончен. Листайте ◀ ▶ или закройте.");
                }
            }
        };

        Scanner(List<Integer> items) { this.items = items; }

        abstract String title();
        abstract String label(int value);
        abstract long codeOf(int value);
        abstract String saveText();
        abstract void save(int value);
        int intervalMs() { return 1200; }

        void fire() {
            int v = items.get(pos);
            big.setText(label(v));
            small.setText((pos + 1) + " из " + items.size() + " · код " + Nec.hex(codeOf(v), 8));
            transmit(codeOf(v));
        }

        void setPaused(boolean p) {
            paused = p;
            ui.removeCallbacks(tick);
            ui.removeCallbacks(advance);
            pauseBtn.setText(p ? "▶ Дальше" : "⏸ Пауза");
            if (!p) { if (pos < items.size() - 1) ui.post(advance); else setPaused(true); }
        }

        void step(int delta) {
            if (!paused) setPaused(true);
            pos = Math.max(0, Math.min(items.size() - 1, pos + delta));
            fire();
        }

        void show() {
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(12), dp(20), dp(4));
            big = new TextView(MainActivity.this);
            big.setTextSize(26);
            big.setGravity(Gravity.CENTER);
            big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            small = new TextView(MainActivity.this);
            small.setGravity(Gravity.CENTER);
            small.setPadding(0, dp(4), 0, dp(10));
            box.addView(big);
            box.addView(small);
            Button prev = baseBtn("◀", BTN, 52), next = baseBtn("▶", BTN, 52), again = baseBtn("↻", BTN, 52);
            pauseBtn = baseBtn("⏸ Пауза", ACCENT, 52);
            pauseBtn.setTextSize(15);
            prev.setOnClickListener(v -> step(-1));
            next.setOnClickListener(v -> step(+1));
            again.setOnClickListener(v -> { if (!paused) setPaused(true); fire(); });
            pauseBtn.setOnClickListener(v -> setPaused(!paused));
            box.addView(row(prev, pauseBtn, next, again));

            dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle(title())
                    .setView(box)
                    .setPositiveButton(saveText(), null)
                    .setNegativeButton("Закрыть", null)
                    .setCancelable(false)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                setPaused(true);
                save(items.get(pos));
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                paused = true;
                ui.removeCallbacks(tick);
                ui.removeCallbacks(advance);
                dialog.dismiss();
            });
            ui.post(tick);
        }
    }

    /** Подбор адреса: одна и та же команда на всех 256 стандартных NEC‑адресах. */
    void startAddressScan() {
        if (!hasIr()) { toast("На этом телефоне нет ИК‑передатчика"); return; }
        final List<Key> withCode = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Key k : KEYS.values()) if (command(k) >= 0) { withCode.add(k); names.add(k.label + "  (" + Nec.hex(command(k), 2) + ")"); }
        if (withCode.isEmpty()) { toast("Сначала задайте код хотя бы одной кнопки"); return; }
        new AlertDialog.Builder(this)
                .setTitle("Какую команду посылать?")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    final Key k = withCode.get(which);
                    final int cmd = command(k);
                    List<Integer> addrs = new ArrayList<>();
                    int cur = address();
                    addrs.add(cur);
                    for (int a = 0; a < 256; a++) {
                        int full = Nec.standardAddress(a);
                        if (full != cur) addrs.add(full);
                    }
                    new Scanner(addrs) {
                        String title() { return "Подбор адреса · " + k.label; }
                        String label(int v) { return "Адрес " + Nec.hex(v, 4); }
                        long codeOf(int v) { return Nec.code(v, cmd); }
                        String saveText() { return "Сработало!"; }
                        void save(int v) {
                            prefs.edit().putInt("addr", v).apply();
                            refreshStatus();
                            toast("Адрес " + Nec.hex(v, 4) + " сохранён");
                        }
                    }.show();
                })
                .show();
        toast("Совет: громкость (+) удобнее всего — на доске появится шкала");
    }

    /** Подбор команд: все 256 команд на текущем адресе, найденную можно назначить кнопке. */
    void startCommandScan() {
        if (!hasIr()) { toast("На этом телефоне нет ИК‑передатчика"); return; }
        List<Integer> cmds = new ArrayList<>();
        for (int c = 0; c < 256; c++) cmds.add(c);
        final int addr = address();
        new Scanner(cmds) {
            String title() { return "Подбор команд · адрес " + Nec.hex(addr, 4); }
            String label(int v) { return "Команда " + Nec.hex(v, 2); }
            long codeOf(int v) { return Nec.code(addr, v); }
            int intervalMs() { return 1500; }
            String saveText() { return "Назначить кнопке…"; }
            void save(final int v) {
                final List<Key> keys = new ArrayList<>(KEYS.values());
                String[] names = new String[keys.size()];
                for (int i = 0; i < names.length; i++) names[i] = keys.get(i).label;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Команда " + Nec.hex(v, 2) + " → кнопка")
                        .setItems(names, (d, which) -> {
                            prefs.edit().putInt("cmd_" + keys.get(which).id, v).apply();
                            toast("«" + keys.get(which).label + "» = " + Nec.hex(v, 2));
                        })
                        .show();
            }
        }.show();
    }

    void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Справка")
                .setMessage(
                        "1. Нужен телефон с ИК‑передатчиком (Xiaomi/Redmi/POCO, некоторые Honor/Huawei/Realme). " +
                        "Направьте верх телефона на приёмник доски (обычно внизу рамки), расстояние до 5–7 м.\n\n" +
                        "2. Коды по умолчанию — Irbis/Promethean (NEC, адрес 0x807F) из базы IrCode Finder. " +
                        "На вашей модели они могут отличаться.\n\n" +
                        "3. Если доска не реагирует — «Подбор адреса»: выберите «Громк. +», смотрите на доску; " +
                        "когда появится шкала громкости, нажмите «Сработало!» (если реакция была чуть раньше — " +
                        "листайте ◀ и проверьте ↻).\n\n" +
                        "4. «Подбор команд» перебирает все 256 команд для адреса. Заметили реакцию — пауза, " +
                        "◀ ▶ ↻ для уточнения, затем «Назначить кнопке…». Так находятся «Домой», «Назад» и др.\n\n" +
                        "Осторожно: при подборе может сработать «Питание» и доска выключится/включится.")
                .setPositiveButton("Понятно", null)
                .show();
    }
}
