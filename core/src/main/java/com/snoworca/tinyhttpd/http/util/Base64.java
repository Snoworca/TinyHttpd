package com.snoworca.tinyhttpd.http.util;

/** Base64 (RFC 4648) with the standard and URL-safe alphabets, written for API 21 which lacks java.util.Base64. */
public final class Base64 {

    private static final char[] STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static final char[] URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private static final int[] DECODE = new int[128];

    static {
        java.util.Arrays.fill(DECODE, -1);
        for (int i = 0; i < 64; i++) {
            DECODE[STD[i]] = i;
            DECODE[URL[i]] = i;
        }
    }

    private Base64() {
    }

    /**
     * @param data bytes
     * @return standard alphabet with padding
     */
    public static String encode(byte[] data) {
        return encode(data, false, true);
    }

    /**
     * @param data bytes
     * @return URL-safe alphabet without padding (for tokens in URLs and cookies)
     */
    public static String encodeUrlSafe(byte[] data) {
        return encode(data, true, false);
    }

    /**
     * @param data bytes
     * @param urlSafe use {@code -} and {@code _}
     * @param padding append {@code =}
     * @return the encoding
     */
    public static String encode(byte[] data, boolean urlSafe, boolean padding) {
        char[] a = urlSafe ? URL : STD;
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        for (; i + 2 < data.length; i += 3) {
            int n = (data[i] & 0xff) << 16 | (data[i + 1] & 0xff) << 8 | data[i + 2] & 0xff;
            sb.append(a[n >> 18]).append(a[n >> 12 & 63]).append(a[n >> 6 & 63]).append(a[n & 63]);
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xff) << 16;
            sb.append(a[n >> 18]).append(a[n >> 12 & 63]);
            if (padding) {
                sb.append("==");
            }
        } else if (rest == 2) {
            int n = (data[i] & 0xff) << 16 | (data[i + 1] & 0xff) << 8;
            sb.append(a[n >> 18]).append(a[n >> 12 & 63]).append(a[n >> 6 & 63]);
            if (padding) {
                sb.append('=');
            }
        }
        return sb.toString();
    }

    /**
     * Decodes either alphabet, with or without padding.
     *
     * @param s encoded text
     * @return the bytes
     * @throws IllegalArgumentException for invalid input
     */
    public static byte[] decode(String s) {
        int len = s.length();
        while (len > 0 && s.charAt(len - 1) == '=') {
            len--;
        }
        if (s.length() - len > 2 || len % 4 == 1) {
            throw new IllegalArgumentException("invalid base64 length");
        }
        byte[] out = new byte[len * 3 / 4];
        int o = 0;
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            int v = c < 128 ? DECODE[c] : -1;
            if (v < 0) {
                throw new IllegalArgumentException("invalid base64 character: " + c);
            }
            acc = acc << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) (acc >> bits);
            }
        }
        return out;
    }
}
