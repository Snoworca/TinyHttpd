package com.snoworca.tinyhttpd.server;

import java.nio.charset.Charset;
import java.util.List;

/** ASCII helpers for HTTP syntax. */
final class Ascii {

    static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    static final Charset UTF8 = Charset.forName("UTF-8");

    private static final boolean[] TCHAR = new boolean[128];

    static {
        for (char c = '0'; c <= '9'; c++) {
            TCHAR[c] = true;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            TCHAR[c] = true;
            TCHAR[c - 32] = true;
        }
        for (char c : "!#$%&'*+-.^_`|~".toCharArray()) {
            TCHAR[c] = true;
        }
    }

    private Ascii() {
    }

    static boolean isHexDigit(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    static boolean isAlphaNum(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    static boolean isTchar(int c) {
        return c >= 0 && c < 128 && TCHAR[c];
    }

    static boolean isToken(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isTchar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** @return true if any comma-separated element of the values equals {@code token}, ignoring case */
    static boolean hasToken(List<String> values, String token) {
        for (String v : values) {
            int start = 0;
            while (start <= v.length()) {
                int comma = v.indexOf(',', start);
                int end = comma < 0 ? v.length() : comma;
                if (v.substring(start, end).trim().equalsIgnoreCase(token)) {
                    return true;
                }
                if (comma < 0) {
                    break;
                }
                start = comma + 1;
            }
        }
        return false;
    }

    /** @return the value of a {@code ;name=value} parameter of a header value (quotes removed), or null */
    static String parameter(String headerValue, String name) {
        if (headerValue == null) {
            return null;
        }
        String[] parts = headerValue.split(";");
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equalsIgnoreCase(name)) {
                String v = p.substring(eq + 1).trim();
                if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    /** @return the media type of a {@code Content-Type} value, lower case, without parameters */
    static String mediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semi = contentType.indexOf(';');
        return (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase(java.util.Locale.US);
    }
}
