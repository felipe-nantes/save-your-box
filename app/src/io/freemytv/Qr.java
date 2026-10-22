package io.freemytv;

import java.nio.charset.StandardCharsets;

/**
 * QR codes for a short text (the phone remote's address): byte mode, error correction M, versions 1 to 9, the mask
 * with the lowest penalty. Follows the spec (ISO 18004) as laid out in Project Nayuki's reference (MIT); no library.
 */
final class Qr {
    private Qr() {}

    // Per version (index 0 unused), error correction M.
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int FORMAT_M = 0;   // M's two format bits

    /** The modules (true = dark), size × size, without the quiet zone; null if the text is too long. */
    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int version = 1; version <= 9; version++) {   // from 10 on, the count takes 16 bits
            int capacity = dataCodewords(version) * 8;
            int needed = 4 + 8 + data.length * 8;
            if (needed <= capacity) return build(version, data);
        }
        return null;
    }

    private static boolean[][] build(int version, byte[] data) {
        int capacity = dataCodewords(version);
        Bits bits = new Bits();
        bits.add(0x4, 4);                 // byte mode
        bits.add(data.length, 8);         // count (8 bits up to version 9)
        for (byte b : data) bits.add(b & 0xFF, 8);
        bits.add(0, Math.min(4, capacity * 8 - bits.size));
        bits.add(0, (8 - bits.size % 8) % 8);
        for (int pad = 0xEC; bits.size < capacity * 8; pad ^= 0xEC ^ 0x11) bits.add(pad, 8);
        byte[] codewords = new byte[capacity];
        for (int i = 0; i < bits.size; i++) if (bits.get(i)) codewords[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
        byte[] all = withEcc(version, codewords);

        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] function = new boolean[size][size];
        drawFunctions(version, modules, function);
        place(all, modules, function);

        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mask, modules, function);
            drawFormat(mask, modules, function);
            long p = penalty(modules);
            if (p < bestPenalty) {
                best = mask;
                bestPenalty = p;
            }
            applyMask(mask, modules, function);   // XOR again: undone
        }
        applyMask(best, modules, function);
        drawFormat(best, modules, function);
        return modules;
    }

    private static int rawModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int align = version / 7 + 2;
            result -= (25 * align - 10) * align - 55;
            if (version >= 7) result -= 36;
        }
        return result;
    }

    private static int dataCodewords(int version) {
        return rawModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version];
    }

    // MARK: - Error correction (Reed-Solomon over GF(256), polynomial 0x11D)

    private static byte[] withEcc(int version, byte[] data) {
        int blocks = BLOCKS[version], eccLen = ECC_PER_BLOCK[version];
        int raw = rawModules(version) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] divisor = rsDivisor(eccLen);
        byte[][] out = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - eccLen + (i < shortBlocks ? 0 : 1);
            byte[] dat = new byte[len];
            System.arraycopy(data, k, dat, 0, len);
            k += len;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, len);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);   // short blocks keep a gap before the ECC
            out[i] = block;
        }
        byte[] result = new byte[raw];
        int n = 0;
        for (int i = 0; i < out[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - eccLen || j >= shortBlocks) result[n++] = out[j][i];
            }
        }
        return result;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMultiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMultiply(root, 0x02);
        }
        return result;
    }

    static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMultiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    static byte[] rsEcc(byte[] data, int degree) {
        return rsRemainder(data, rsDivisor(degree));
    }

    private static int gfMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // MARK: - Function patterns

    private static void drawFunctions(int version, boolean[][] m, boolean[][] f) {
        int size = m.length;
        for (int i = 0; i < size; i++) {
            set(m, f, 6, i, i % 2 == 0);   // timing
            set(m, f, i, 6, i % 2 == 0);
        }
        finder(m, f, 3, 3);
        finder(m, f, size - 4, 3);
        finder(m, f, 3, size - 4);
        int[] pos = alignmentPositions(version);
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0);
                if (!corner) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) set(m, f, pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                    }
                }
            }
        }
        drawFormat(0, m, f);   // reserves the format areas (the real bits come after the mask)
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            long bits = (long) version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(m, f, a, b, bit);
                set(m, f, b, a, bit);
            }
        }
    }

    private static void finder(boolean[][] m, boolean[][] f, int x, int y) {
        int size = m.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(m, f, xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private static int[] alignmentPositions(int version) {
        if (version == 1) return new int[0];
        int count = version / 7 + 2;
        int step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2;
        int[] result = new int[count];
        result[0] = 6;
        for (int i = result.length - 1, p = version * 4 + 17 - 7; i >= 1; i--, p -= step) result[i] = p;
        return result;
    }

    private static void drawFormat(int mask, boolean[][] m, boolean[][] f) {
        int data = FORMAT_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        int size = m.length;
        for (int i = 0; i <= 5; i++) set(m, f, 8, i, bit(bits, i));
        set(m, f, 8, 7, bit(bits, 6));
        set(m, f, 8, 8, bit(bits, 7));
        set(m, f, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(m, f, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(m, f, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(m, f, 8, size - 15 + i, bit(bits, i));
        set(m, f, 8, size - 8, true);   // the dark module
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    /** x = column, y = row. */
    private static void set(boolean[][] m, boolean[][] f, int x, int y, boolean dark) {
        m[y][x] = dark;
        f[y][x] = true;
    }

    // MARK: - Data and masks

    private static void place(byte[] data, boolean[][] m, boolean[][] f) {
        int size = m.length, i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!f[y][x] && i < data.length * 8) {
                        m[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask(int mask, boolean[][] m, boolean[][] f) {
        int size = m.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (!f[y][x] && invert) m[y][x] = !m[y][x];
            }
        }
    }

    /** The spec's four penalties: runs, 2×2 blocks, finder-like patterns, dark/light balance. */
    private static long penalty(boolean[][] m) {
        int size = m.length;
        long result = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 0;
                boolean color = false;
                for (int b = 0; b < size; b++) {
                    boolean v = pass == 0 ? m[a][b] : m[b][a];
                    if (b > 0 && v == color) {
                        run++;
                        if (run == 5) result += 3; else if (run > 5) result++;
                    } else {
                        color = v;
                        run = 1;
                    }
                }
                for (int b = 0; b + 10 < size + 1; b++) {
                    if (b + 7 > size) break;
                    boolean[] line = new boolean[size];
                    for (int i = 0; i < size; i++) line[i] = pass == 0 ? m[a][i] : m[i][a];
                    if (line[b] && !line[b + 1] && line[b + 2] && line[b + 3] && line[b + 4] && !line[b + 5] && line[b + 6]) {
                        boolean before = b >= 4 && !line[b - 1] && !line[b - 2] && !line[b - 3] && !line[b - 4];
                        boolean after = b + 10 < size && !line[b + 7] && !line[b + 8] && !line[b + 9] && !line[b + 10];
                        if (before || after) result += 40;
                    }
                }
            }
        }
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (m[y][x]) dark++;
                if (y + 1 < size && x + 1 < size && m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) result += 3;
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += Math.max(0, k) * 10L;
        return result;
    }

    private static final class Bits {
        private final boolean[] bits = new boolean[8 * 300];
        int size;

        void add(int value, int length) {
            for (int i = length - 1; i >= 0; i--) bits[size++] = ((value >>> i) & 1) != 0;
        }

        boolean get(int i) {
            return bits[i];
        }
    }
}
