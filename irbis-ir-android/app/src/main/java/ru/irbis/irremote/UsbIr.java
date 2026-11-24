package ru.irbis.irremote;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.ByteArrayOutputStream;

/**
 * Передатчик через USB‑C ИК‑адаптер («IR remote Type‑C», вставляется в телефон
 * без встроенного ИК, например Samsung Galaxy).
 *
 * Протокол официально не опубликован. Реализация повторяет неофициальную
 * (проект vsluh, Transmitters.kt): сообщения "ST" n 'D' 0 <длительности> "EN",
 * длительности в единицах 16 мкс, по 1 байту ≤127, у импульсов старший бит = 1;
 * сообщение режется на пакеты [2, len+3, seq, total, idx] + ≤56 байт данных
 * и отправляется bulk/interrupt‑передачей. Несущая у адаптера фиксирована (38 кГц).
 * На реальном адаптере НЕ проверено.
 */
final class UsbIr {
    static final int[][] KNOWN_IDS = {{0x045E, 0x8468}, {0x10C4, 0x8468}};

    private final UsbManager usb;
    private final UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface itf;
    private UsbEndpoint out, in;
    private int seq = 0, cmd = 0;

    UsbIr(UsbManager usb, UsbDevice device) {
        this.usb = usb;
        this.device = device;
    }

    UsbDevice device() { return device; }

    static boolean isKnown(UsbDevice d) {
        for (int[] id : KNOWN_IDS) if (d.getVendorId() == id[0] && d.getProductId() == id[1]) return true;
        return false;
    }

    /** Есть ли у интерфейса 0 OUT‑точка bulk/interrupt — тогда устройство можно попробовать. */
    static boolean canTry(UsbDevice d) {
        if (d.getInterfaceCount() == 0) return false;
        UsbInterface i = d.getInterface(0);
        for (int e = 0; e < i.getEndpointCount(); e++) {
            UsbEndpoint ep = i.getEndpoint(e);
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT && isDataEp(ep)) return true;
        }
        return false;
    }

    /** Найти подходящий адаптер: сначала известные VID/PID, потом любой «пробуемый». */
    static UsbDevice find(UsbManager usb) {
        if (usb == null) return null;
        UsbDevice fallback = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (isKnown(d)) return d;
            if (fallback == null && canTry(d)) fallback = d;
        }
        return fallback;
    }

    private static boolean isDataEp(UsbEndpoint ep) {
        return ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK || ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT;
    }

    synchronized void open() throws Exception {
        if (conn != null) return;
        itf = device.getInterface(0);
        for (int e = 0; e < itf.getEndpointCount(); e++) {
            UsbEndpoint ep = itf.getEndpoint(e);
            if (!isDataEp(ep)) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep; else in = ep;
        }
        if (out == null) throw new Exception("У адаптера нет OUT‑точки");
        conn = usb.openDevice(device);
        if (conn == null) throw new Exception("Не удалось открыть USB‑адаптер");
        if (!conn.claimInterface(itf, true)) {
            conn.close();
            conn = null;
            throw new Exception("Не удалось захватить интерфейс адаптера");
        }
        send(new byte[]{'S', 'T', (byte) nextCmd(), 'S', 'E', 'N'});
    }

    synchronized void close() {
        if (conn != null) {
            try { conn.releaseInterface(itf); } catch (Exception ignored) {}
            conn.close();
            conn = null;
        }
    }

    private int nextCmd() {
        cmd = cmd % 127 + 1;
        return cmd;
    }

    private void send(byte[] msg) throws Exception {
        seq = seq % 15 + 1;
        int total = Math.max(1, (msg.length + 55) / 56);
        for (int i = 0; i < total; i++) {
            int from = i * 56, len = Math.min(56, msg.length - from);
            byte[] pkt = new byte[5 + len];
            pkt[0] = 2;
            pkt[1] = (byte) (len + 3);
            pkt[2] = (byte) seq;
            pkt[3] = (byte) total;
            pkt[4] = (byte) (i + 1);
            System.arraycopy(msg, from, pkt, 5, len);
            if (conn.bulkTransfer(out, pkt, pkt.length, 250) < 0) throw new Exception("USB‑адаптер не принял данные");
        }
        // Адаптер отвечает подтверждением — вычитываем, чтобы не переполнить буфер
        if (in != null) {
            byte[] buf = new byte[64];
            for (int k = 0; k < 8 && conn.bulkTransfer(in, buf, buf.length, 15) > 0; k++) { /* drain */ }
        }
    }

    /** pattern — импульс/пауза в мкс, как для ConsumerIrManager. */
    synchronized void transmit(int[] pattern) throws Exception {
        open();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write('S'); b.write('T'); b.write(nextCmd()); b.write('D'); b.write(0);
        long totalUs = 0;
        for (int i = 0; i < pattern.length; i++) {
            int units = Math.max(1, pattern[i] / 16);
            totalUs += pattern[i];
            boolean mark = (i % 2) == 0;
            while (units > 0) {
                int piece = Math.min(127, units);
                b.write(mark ? (0x80 | piece) : piece);
                units -= piece;
            }
        }
        b.write('E'); b.write('N');
        send(b.toByteArray());
        Thread.sleep(totalUs / 1000 + 2);
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
        return s.toString().trim();
    }
}
