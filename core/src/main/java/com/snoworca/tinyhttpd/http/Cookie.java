package com.snoworca.tinyhttpd.http;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** A cookie for {@link Response#addCookie} or from {@link Request#getCookies()}. */
public final class Cookie {

    private final String name;

    private final String value;

    private long maxAge = -1;

    private long expires = -1;

    private String path;

    private String domain;

    private boolean secure;

    private boolean httpOnly;

    private String sameSite;

    /**
     * @param name cookie name (token characters)
     * @param value cookie value (RFC 6265 cookie-octets)
     * @throws IllegalArgumentException for forbidden characters
     */
    public Cookie(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("cookie name is empty");
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isToken(name.charAt(i))) {
                throw new IllegalArgumentException("invalid cookie name: " + name);
            }
        }
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x21 || c > 0x7e || c == '"' || c == ',' || c == ';' || c == '\\') {
                throw new IllegalArgumentException("invalid character in cookie value");
            }
        }
        this.name = name;
        this.value = v;
    }

    /** @return the name */
    public String getName() {
        return name;
    }

    /** @return the value */
    public String getValue() {
        return value;
    }

    /**
     * @param seconds {@code Max-Age}; negative omits it
     * @return this
     */
    public Cookie setMaxAge(long seconds) {
        this.maxAge = seconds;
        return this;
    }

    /**
     * @param millis {@code Expires}; negative omits it
     * @return this
     */
    public Cookie setExpires(long millis) {
        this.expires = millis;
        return this;
    }

    /**
     * @param path {@code Path}
     * @return this
     */
    public Cookie setPath(String path) {
        this.path = checkAttribute(path);
        return this;
    }

    /**
     * @param domain {@code Domain}
     * @return this
     */
    public Cookie setDomain(String domain) {
        this.domain = checkAttribute(domain);
        return this;
    }

    /**
     * @param secure {@code Secure}
     * @return this
     */
    public Cookie setSecure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * @param httpOnly {@code HttpOnly}
     * @return this
     */
    public Cookie setHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
        return this;
    }

    /**
     * @param sameSite {@code Strict}, {@code Lax} or {@code None}
     * @return this
     */
    public Cookie setSameSite(String sameSite) {
        this.sameSite = checkAttribute(sameSite);
        return this;
    }

    /** @return the {@code Set-Cookie} value; attribute order is fixed */
    public String toSetCookie() {
        StringBuilder sb = new StringBuilder(64).append(name).append('=').append(value);
        if (maxAge >= 0) {
            sb.append("; Max-Age=").append(maxAge);
        }
        if (expires >= 0) {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            sb.append("; Expires=").append(f.format(new Date(expires)));
        }
        if (path != null) {
            sb.append("; Path=").append(path);
        }
        if (domain != null) {
            sb.append("; Domain=").append(domain);
        }
        if (secure) {
            sb.append("; Secure");
        }
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        if (sameSite != null) {
            sb.append("; SameSite=").append(sameSite);
        }
        return sb.toString();
    }

    /**
     * Parses {@code Cookie} header values leniently (RFC 6265 §5.2): pairs split on {@code ;}, trimmed, split at the
     * first {@code =}, surrounding quotes removed; malformed pairs are skipped.
     *
     * @param headerValues values of the {@code Cookie} headers
     * @return the cookies in order
     */
    public static List<Cookie> parse(List<String> headerValues) {
        List<Cookie> out = new ArrayList<Cookie>();
        for (String header : headerValues) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String n = pair.substring(0, eq).trim();
                String v = pair.substring(eq + 1).trim();
                if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
                    v = v.substring(1, v.length() - 1);
                }
                try {
                    out.add(new Cookie(n, v));
                } catch (IllegalArgumentException skipped) {
                    // lenient: a malformed pair is not an error
                }
            }
        }
        return out;
    }

    private static String checkAttribute(String s) {
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 || c == 0x7f || c == ';') {
                    throw new IllegalArgumentException("invalid character in cookie attribute");
                }
            }
        }
        return s;
    }

    static boolean isToken(char c) {
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
            return true;
        }
        return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }
}
