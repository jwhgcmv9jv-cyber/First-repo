package ru.irbis.irremote;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * USB‑C ИК‑адаптер Tiqiaa Tview (VID:PID 10C4:8468; его же продают как
 * «ZaZa», «IR remote Type‑C»).
 *
 * Протокол — по открытым драйверам, полученным обратной разработкой:
 *   rabits/ir-usb (TiqiaaUsb.cpp) и cclairmont/tiqiaa_lirc (tiqiaa.c),
 *   оба основаны на работе XenRE.
 *
 * Транспорт: bulk OUT 0x01 / bulk IN 0x81. Логический пакет режется на
 * фрагменты: [ReportId=2, len+3, PacketIdx 1..15, FragmCount, FragmIdx 1..] + ≤56 байт.
 * Ответы приходят так же, но с ReportId=1.
 *
 * Логические пакеты:
 *   команда:  'S''T' <cmdId 1..127> <тип> 'E''N'
 *             типы: V — версия, S — режим передачи, L — ожидание, R — приём
 *   данные:   'S''T' <cmdId> 'D' <freqId> <тайминги…> 'E''N'
 *             freqId 0 = 38 кГц; тайминг = 1 байт: бит7 = импульс(1)/пауза(0),
 *             биты 0‑6 = длительность в тиках по 16 мкс (≤127, длинные дробятся).
 *             Последовательность начинается импульсом и заканчивается паузой.
 *   ответ:    'S''T' <cmdId> <тип> <состояние> … 'E''N'; состояние 9 = режим передачи.
 *             На пакет 'D' адаптер отвечает типом 'O'.
 */
final class UsbIr {
    static final int[][] KNOWN_IDS = {{0x10C4, 0x8468}, {0x045E, 0x8468}};

    private static final int STATE_SEND = 9;
    private static final int FREQ_38K = 0;
    private static final int TICK_US = 16;
    private static final int TAIL_GAP_US = 40500; // пауза после кадра NEC, как в ir-usb

    private final UsbManager usb;
    private final UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface itf;
    private UsbEndpoint out, in;
    private int packetIdx = 0, cmdId = 0, state = -1;

    /** Журнал обмена (последние строки) — для диагностики. */
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    UsbIr(UsbManager usb, UsbDevice device) {
        this.usb = usb;
        this.device = device;
    }

    UsbDevice device() { return device; }

    static boolean isKnown(UsbDevice d) {
        for (int[] id : KNOWN_IDS) if (d.getVendorId() == id[0] && d.getProductId() == id[1]) return true;
        return false;
    }

    static boolean canTry(UsbDevice d) {
        if (d.getInterfaceCount() == 0) return false;
        UsbInterface i = d.getInterface(0);
        for (int e = 0; e < i.getEndpointCount(); e++) {
            UsbEndpoint ep = i.getEndpoint(e);
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) return true;
        }
        return false;
    }

    static UsbDevice find(UsbManager usb) {
        if (usb == null) return null;
        UsbDevice fallback = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (isKnown(d)) return d;
            if (fallback == null && canTry(d)) fallback = d;
        }
        return fallback;
    }

    // ------------------------------------------------------------ журнал
    static synchronized void log(String s) {
        LOG.addLast(String.format(Locale.US, "%6d %s", SystemClock.uptimeMillis() % 1000000, s));
        while (LOG.size() > 40) LOG.removeFirst();
    }

    static synchronized String logText() {
        if (LOG.isEmpty()) return "(пусто — нажмите кнопку на пульте или «Тест»)";
        StringBuilder b = new StringBuilder();
        for (String s : LOG) b.append(s).append('\n');
        return b.toString().trim();
    }

    static String hex(byte[] a, int off, int len) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i == 24) { b.append("… (").append(len).append(" байт)"); break; }
            b.append(String.format("%02X ", a[off + i] & 0xFF));
        }
        return b.toString().trim();
    }

    // ------------------------------------------------------------ USB
    synchronized void open() throws Exception {
        if (conn != null) return;
        itf = device.getInterface(0);
        out = null; in = null;
        for (int e = 0; e < itf.getEndpointCount(); e++) {
            UsbEndpoint ep = itf.getEndpoint(e);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep; else in = ep;
        }
        if (out == null || in == null) throw new Exception("У адаптера нет bulk‑точек IN/OUT");
        conn = usb.openDevice(device);
        if (conn == null) throw new Exception("Не удалось открыть USB‑адаптер (нет разрешения?)");
        if (!conn.claimInterface(itf, true)) {
            conn.close();
            conn = null;
            throw new Exception("Не удалось захватить интерфейс адаптера");
        }
        log(String.format("открыт %04X:%04X, OUT 0x%02X, IN 0x%02X", device.getVendorId(), device.getProductId(),
                out.getAddress(), in.getAddress()));
        drain();
        state = -1;
        // Как в ir-usb: запрос версии, затем режим передачи
        command('V', 500);
        enterSendMode();
    }

    synchronized void close() {
        if (conn != null) {
            try { command('L', 200); } catch (Exception ignored) {}
            try { conn.releaseInterface(itf); } catch (Exception ignored) {}
            conn.close();
            conn = null;
            log("закрыт");
        }
    }

    private int nextCmdId() {
        cmdId = cmdId % 0x7F + 1;
        return cmdId;
    }

    private void enterSendMode() throws Exception {
        byte[] r = command('S', 500);
        if (r != null && r.length >= 3) state = r[2] & 0xFF;
        if (state != STATE_SEND) log("внимание: состояние после 'S' = " + state + " (ожидалось 9)");
    }

    /** Отправить команду и дождаться ответа с тем же cmdId. Возвращает тело ответа (без ST/EN) или null. */
    private byte[] command(char type, int waitMs) throws Exception {
        int id = nextCmdId();
        writePacket(new byte[]{'S', 'T', (byte) id, (byte) type, 'E', 'N'});
        return waitReply(id, -1, waitMs);
    }

    private void writePacket(byte[] pack) throws Exception {
        packetIdx = packetIdx % 15 + 1;
        int count = Math.max(1, (pack.length + 55) / 56);
        for (int i = 0; i < count; i++) {
            int from = i * 56, len = Math.min(56, pack.length - from);
            byte[] f = new byte[5 + len];
            f[0] = 2;                 // ReportId записи
            f[1] = (byte) (len + 3);  // FragmSize
            f[2] = (byte) packetIdx;
            f[3] = (byte) count;
            f[4] = (byte) (i + 1);
            System.arraycopy(pack, from, f, 5, len);
            int n = conn.bulkTransfer(out, f, f.length, 1000);
            if (i == 0) log("→ " + hex(pack, 0, pack.length) + (n < 0 ? "  [ОШИБКА записи]" : ""));
            if (n < 0) throw new Exception("USB‑адаптер не принял данные");
        }
    }

    /** Прочитать один логический пакет (собрав фрагменты). null — тайм‑аут. */
    private byte[] readPacket(long deadline) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buf = new byte[64];
        boolean started = false;
        while (true) {
            int left = (int) (deadline - SystemClock.uptimeMillis());
            if (left <= 0) return null;
            int n = conn.bulkTransfer(in, buf, buf.length, Math.min(left, 200));
            if (n < 5) continue;
            int size = (buf[1] & 0xFF) - 3;
            int count = buf[3] & 0xFF, idx = buf[4] & 0xFF;
            if (size < 0 || 5 + size > n) { log("← битый фрагмент " + hex(buf, 0, n)); continue; }
            if (idx == 1) { body.reset(); started = true; }
            if (!started) continue;
            body.write(buf, 5, size);
            if (idx >= count) {
                byte[] p = body.toByteArray();
                log("← " + hex(p, 0, p.length));
                return p;
            }
        }
    }

    /** Ждать ответ с данным cmdId (и типом, если wantType ≥ 0). Возвращает [cmdId, type, state, …]. */
    private byte[] waitReply(int id, int wantType, int waitMs) {
        long deadline = SystemClock.uptimeMillis() + waitMs;
        while (SystemClock.uptimeMillis() < deadline) {
            byte[] p = readPacket(deadline);
            if (p == null) break;
            if (p.length < 6 || p[0] != 'S' || p[1] != 'T' || p[p.length - 2] != 'E' || p[p.length - 1] != 'N') continue;
            byte[] r = new byte[p.length - 4];
            System.arraycopy(p, 2, r, 0, r.length);
            if ((r[0] & 0xFF) != id) continue;
            if (wantType >= 0 && (r[1] & 0xFF) != wantType) continue;
            return r;
        }
        log("ответа нет (cmdId " + id + ")");
        return null;
    }

    private void drain() {
        byte[] buf = new byte[64];
        for (int k = 0; k < 16 && conn.bulkTransfer(in, buf, buf.length, 20) > 0; k++) { /* сброс старых ответов */ }
    }

    /** Кодирование таймингов: накапливаем время в 1/16 мкс, чтобы не было дрейфа округления. */
    static byte[] encodeTimings(int[] pattern) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n = pattern.length;
        boolean endsWithMark = n % 2 == 1;
        long t = 0, sent = 0; // в мкс*16 / тиках
        for (int i = 0; i < n + (endsWithMark ? 1 : 0); i++) {
            int us = i < n ? pattern[i] : TAIL_GAP_US;
            boolean mark = i % 2 == 0;
            t += us;
            long ticks = Math.round(t / (double) TICK_US) - sent;
            if (ticks < 1) ticks = 1;
            sent += ticks;
            while (ticks > 0) {
                int piece = (int) Math.min(127, ticks);
                b.write(mark ? (0x80 | piece) : piece);
                ticks -= piece;
            }
        }
        return b.toByteArray();
    }

    /** pattern — импульс/пауза в мкс, как для ConsumerIrManager. */
    synchronized void transmit(int[] pattern) throws Exception {
        try {
            open();
            if (state != STATE_SEND) enterSendMode();
            byte[] timings = encodeTimings(pattern);
            int id = nextCmdId();
            ByteArrayOutputStream p = new ByteArrayOutputStream();
            p.write('S'); p.write('T'); p.write(id); p.write('D'); p.write(FREQ_38K);
            p.write(timings, 0, timings.length);
            p.write('E'); p.write('N');
            writePacket(p.toByteArray());
            byte[] r = waitReply(id, 'O', 2000);
            if (r == null) log("нет подтверждения 'O' на передачу");
        } catch (Exception e) {
            log("ошибка: " + e.getMessage());
            close();
            throw e;
        }
    }

    /** Текст для диагностики: все USB‑устройства, интерфейсы и точки. */
    static String describeAll(UsbManager usb) {
        if (usb == null) return "USB недоступен";
        StringBuilder s = new StringBuilder();
        if (usb.getDeviceList().isEmpty()) return "USB‑устройства не найдены. Вставьте адаптер и откройте снова.";
        for (UsbDevice d : usb.getDeviceList().values()) {
            s.append(String.format("VID:PID = %04X:%04X", d.getVendorId(), d.getProductId()));
            if (isKnown(d)) s.append("  (известный ИК‑адаптер)");
            s.append('\n');
            s.append("Доступ: ").append(usb.hasPermission(d) ? "разрешён" : "НЕ разрешён").append('\n');
            try {
                if (d.getManufacturerName() != null) s.append("Производитель: ").append(d.getManufacturerName()).append('\n');
                if (d.getProductName() != null) s.append("Название: ").append(d.getProductName()).append('\n');
            } catch (SecurityException ignored) {}
            s.append("Класс: ").append(d.getDeviceClass()).append('\n');
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface itf = d.getInterface(i);
                s.append(" Интерфейс ").append(i).append(": класс ").append(itf.getInterfaceClass())
                        .append('/').append(itf.getInterfaceSubclass()).append('/').append(itf.getInterfaceProtocol()).append('\n');
                for (int e = 0; e < itf.getEndpointCount(); e++) {
                    UsbEndpoint ep = itf.getEndpoint(e);
                    String type = ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK ? "bulk"
                            : ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT ? "interrupt"
                            : ep.getType() == UsbConstants.USB_ENDPOINT_XFER_CONTROL ? "control" : "isoc";
                    s.append(String.format("  EP 0x%02X %s %s, %d байт%n", ep.getAddress(),
                            ep.getDirection() == UsbConstants.USB_DIR_OUT ? "OUT" : "IN", type, ep.getMaxPacketSize()));
                }
            }
            s.append('\n');
        }
        s.append("Журнал обмена:\n").append(logText());
        return s.toString().trim();
    }
}
