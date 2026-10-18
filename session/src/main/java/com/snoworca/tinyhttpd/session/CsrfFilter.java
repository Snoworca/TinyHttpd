package com.snoworca.tinyhttpd.session;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.FilterChain;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/**
 * Refuses cross-site state changes: POST, PUT, PATCH and DELETE need an {@code Origin} (else {@code Referer}) of this
 * server or an allowed origin; with neither header, the session's token in {@code X-CSRF-Token}. Fails with 403.
 * DNS rebinding passes the same-origin check; the host check ({@code HostAllowlistFilter}) stops it. Install after
 * {@link SessionFilter}.
 */
public final class CsrfFilter implements Filter {

    private final Set<String> allowed = new HashSet<String>();

    /** @param allowedOrigins extra origins ({@code https://host:port}) besides this server */
    public CsrfFilter(String... allowedOrigins) {
        for (String o : allowedOrigins) {
            allowed.add(o.toLowerCase(Locale.US));
        }
    }

    @Override
    public void doFilter(Request req, Response res, FilterChain chain) throws IOException, HttpException {
        String m = req.getMethod();
        if (m.equals("POST") || m.equals("PUT") || m.equals("PATCH") || m.equals("DELETE")) {
            String source = req.getHeader("Origin");
            if (source == null || source.equals("null")) {
                source = origin(req.getHeader("Referer"));
            }
            if (source != null) {
                if (!sameOrigin(source, req) && !allowed.contains(source.toLowerCase(Locale.US))) {
                    throw new HttpException(403, "cross-site request refused");
                }
            } else {
                Session s = Session.get(req);
                String token = req.getHeader("X-CSRF-Token");
                if (s == null || token == null || !constantTimeEquals(token, s.getCsrfToken())) {
                    throw new HttpException(403, "CSRF token required");
                }
            }
        }
        chain.doFilter(req, res);
    }

    /** @return scheme://authority of a URL, or null */
    static String origin(String url) {
        if (url == null) {
            return null;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url : url.substring(0, slash);
    }

    private static boolean sameOrigin(String origin, Request req) {
        String o = origin.toLowerCase(Locale.US);
        String expectedScheme = req.isSecure() ? "https://" : "http://";
        if (!o.startsWith(expectedScheme) || req.getServerName() == null) {
            return false;
        }
        String authority = o.substring(expectedScheme.length());
        int defaultPort = req.isSecure() ? 443 : 80;
        int port = req.getServerPort();
        String expected = req.getServerName().toLowerCase(Locale.US) + (port == defaultPort ? "" : ":" + port);
        return authority.equals(expected) || authority.equals(expected + ":" + defaultPort);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes();
        byte[] y = b.getBytes();
        return java.security.MessageDigest.isEqual(x, y) && Arrays.equals(x, y);
    }
}
