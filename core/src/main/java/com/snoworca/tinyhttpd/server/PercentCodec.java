package com.snoworca.tinyhttpd.server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal API, may change without notice. Percent decoding without {@code URLDecoder}: a strict path mode that
 * rejects anything doubtful, and a lenient form mode for queries and form bodies.
 */
public final class PercentCodec {

    private PercentCodec() {
    }

    /**
     * Decodes and normalizes a raw path: split on {@code /} before decoding, strict UTF-8, empty segments merged.
     *
     * @param raw raw path starting with {@code /}
     * @return the decoded path, or null if it must be rejected (bad escape, bad UTF-8, encoded slash, NUL, dot
     *         segment)
     */
    public static String decodePath(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        int start = 1;
        boolean trailingSlash = raw.length() > 1 && raw.endsWith("/");
        while (start <= raw.length()) {
            int slash = raw.indexOf('/', start);
            int end = slash < 0 ? raw.length() : slash;
            if (end > start) {
                String seg = decodeStrict(raw.substring(start, end));
                if (seg == null || seg.indexOf('/') >= 0 || seg.indexOf('\0') >= 0 || seg.equals(".") || seg.equals("..")) {
                    return null;
                }
                out.append('/').append(seg);
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }
        if (out.length() == 0 || trailingSlash) {
            out.append('/');
        }
        return out.toString();
    }

    private static String decodeStrict(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    return null;
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                bytes.write(hi << 4 | lo);
                i += 2;
            } else if (c < 0x80) {
                bytes.write(c);
            } else {
                return null;
            }
        }
        CharsetDecoder d = Ascii.UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return d.decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Form decoding: {@code +} is a space, escapes are decoded in {@code charset}; a bad escape stays literal and bad
     * byte sequences become U+FFFD. Never throws.
     *
     * @param s encoded text
     * @param charset charset of the escaped bytes
     * @return decoded text
     */
    public static String decodeForm(String s, Charset charset) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        ByteArrayOutputStream pending = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int hi = -1;
            int lo = -1;
            if (c == '%' && i + 2 < s.length()) {
                hi = Character.digit(s.charAt(i + 1), 16);
                lo = Character.digit(s.charAt(i + 2), 16);
            }
            if (hi >= 0 && lo >= 0) {
                if (pending == null) {
                    pending = new ByteArrayOutputStream();
                }
                pending.write(hi << 4 | lo);
                i += 2;
                continue;
            }
            flush(pending, out, charset);
            out.append(c == '+' ? ' ' : c);
        }
        flush(pending, out, charset);
        return out.toString();
    }

    private static void flush(ByteArrayOutputStream pending, StringBuilder out, Charset charset) {
        if (pending != null && pending.size() > 0) {
            CharsetDecoder d = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try {
                CharBuffer cb = d.decode(ByteBuffer.wrap(pending.toByteArray()));
                out.append(cb);
            } catch (CharacterCodingException impossible) {
                throw new IllegalStateException(impossible);
            }
            pending.reset();
        }
    }

    /**
     * Parses {@code a=1&b=2} with {@link #decodeForm}.
     *
     * @param s encoded pairs, may be null
     * @param charset charset of escaped bytes
     * @return names to values in order of first appearance
     */
    public static Map<String, List<String>> parseForm(String s, Charset charset) {
        Map<String, List<String>> map = new LinkedHashMap<String, List<String>>();
        if (s == null || s.isEmpty()) {
            return map;
        }
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decodeForm(eq < 0 ? pair : pair.substring(0, eq), charset);
            String value = eq < 0 ? "" : decodeForm(pair.substring(eq + 1), charset);
            List<String> values = map.get(name);
            if (values == null) {
                values = new ArrayList<String>(1);
                map.put(name, values);
            }
            values.add(value);
        }
        return map;
    }

    /**
     * Percent-encodes UTF-8 bytes except RFC 3986 unreserved characters.
     *
     * @param s text
     * @return encoded text
     */
    public static String encode(String s) {
        StringBuilder out = new StringBuilder(s.length() * 3);
        for (byte b : s.getBytes(Ascii.UTF8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_'
                    || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return out.toString();
    }
}
