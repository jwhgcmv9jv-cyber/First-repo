package ru.irbis.irremote;

/**
 * Протокол NEC (38 кГц) — тот, что используют пульты досок Irbis/Promethean.
 *
 * Код записывается как 32‑битное число в «MSB‑first» виде, как в базах кодов
 * и в дампах Arduino IRremote: 0xAAAACCcc, где AAAA — адрес устройства,
 * CC — команда, cc — её инверсия. Биты передаются от старшего к младшему.
 *
 * Все длительности — в микросекундах (формат ConsumerIrManager.transmit
 * начиная с Android 4.4.3).
 */
final class Nec {
    static final int CARRIER = 38000;

    private static final int T = 562;
    private static final int LEADER_MARK = 16 * T;   // 9 мс
    private static final int LEADER_SPACE = 8 * T;   // 4.5 мс
    private static final int ZERO_SPACE = T;
    private static final int ONE_SPACE = 3 * T;

    private Nec() {}

    /** Полный 32‑битный код из адреса (16 бит) и команды (8 бит). */
    static long code(int address, int command) {
        int c = command & 0xFF;
        return ((long) (address & 0xFFFF) << 16) | (c << 8) | (~c & 0xFF);
    }

    /** Стандартный NEC-адрес: байт + его инверсия (например 0x80 → 0x807F). */
    static int standardAddress(int addrByte) {
        int a = addrByte & 0xFF;
        return (a << 8) | (~a & 0xFF);
    }

    /** Последовательность импульс/пауза для одного кадра. */
    static int[] frame(long code) {
        int[] p = new int[2 + 32 * 2 + 1];
        int i = 0;
        p[i++] = LEADER_MARK;
        p[i++] = LEADER_SPACE;
        for (int bit = 31; bit >= 0; bit--) {
            p[i++] = T;
            p[i++] = ((code >> bit) & 1) == 1 ? ONE_SPACE : ZERO_SPACE;
        }
        p[i] = T; // завершающий импульс
        return p;
    }

    static String hex(long v, int digits) {
        String s = Long.toHexString(v).toUpperCase();
        while (s.length() < digits) s = "0" + s;
        return "0x" + s;
    }
}
