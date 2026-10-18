package com.snoworca.tinyhttpd.http.filter;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.FilterChain;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/**
 * DNS rebinding defense: answers 421 unless the request's host is an IP literal, {@code localhost} or one of the
 * allowed names. A rebinding attack always uses the attacker's domain name, so IP literals are safe to allow and LAN
 * use needs no configuration even when DHCP changes the device address. Names compare case-insensitively without a
 * trailing dot; ports are ignored.
 */
public final class HostAllowlistFilter implements Filter {

    private final Set<String> names = new HashSet<String>();

    /** @param allowedNames extra host names, for example {@code nas.local} */
    public HostAllowlistFilter(String... allowedNames) {
        names.add("localhost");
        for (String n : allowedNames) {
            names.add(normalize(n));
        }
    }

    @Override
    public void doFilter(Request request, Response response, FilterChain chain) throws IOException, HttpException {
        String host = request.getServerName();
        if (host != null && !isAllowed(host)) {
            throw new HttpException(421, "host not allowed");
        }
        chain.doFilter(request, response);
    }

    /**
     * @param host a host from {@code Host} or the request target, without port
     * @return true if allowed
     */
    public boolean isAllowed(String host) {
        String h = normalize(host);
        return isIpLiteral(h) || names.contains(h);
    }

    private static String normalize(String host) {
        String h = host.trim().toLowerCase(Locale.US);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    static boolean isIpLiteral(String h) {
        if (h.startsWith("[") && h.endsWith("]")) {
            String v6 = h.substring(1, h.length() - 1);
            if (v6.isEmpty()) {
                return false;
            }
            for (int i = 0; i < v6.length(); i++) {
                char c = v6.charAt(i);
                if (!(Character.digit(c, 16) >= 0 || c == ':' || c == '.' || c == '%')) {
                    return false;
                }
            }
            return v6.indexOf(':') >= 0;
        }
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (p.charAt(i) < '0' || p.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }
}
