package com.snoworca.tinyhttpd.http.filter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.FilterChain;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.RequestWrapper;
import com.snoworca.tinyhttpd.http.Response;

/**
 * Applies {@code X-Forwarded-For}, {@code X-Forwarded-Proto}, {@code X-Forwarded-Host} and {@code X-Forwarded-Port}
 * (server name and port, used for example by {@code CsrfFilter}'s origin check) only when the direct peer
 * is a trusted proxy. The client address is the rightmost {@code X-Forwarded-For} entry that is not a trusted proxy.
 * Headers from untrusted peers are ignored, so clients cannot spoof their address.
 */
public final class ForwardedHeadersFilter implements Filter {

    private final Set<String> trusted;

    /** @param trustedProxies proxy addresses as {@link Request#getRemoteAddr()} reports them */
    public ForwardedHeadersFilter(String... trustedProxies) {
        this.trusted = new HashSet<String>(Arrays.asList(trustedProxies));
    }

    @Override
    public void doFilter(Request request, Response response, FilterChain chain) throws IOException, HttpException {
        if (!trusted.contains(request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }
        String client = null;
        List<String> xff = request.getHeaders("X-Forwarded-For");
        outer:
        for (int i = xff.size() - 1; i >= 0; i--) {
            String[] hops = xff.get(i).split(",");
            for (int j = hops.length - 1; j >= 0; j--) {
                String hop = hops[j].trim();
                if (!hop.isEmpty() && !trusted.contains(hop)) {
                    client = hop;
                    break outer;
                }
            }
        }
        final String remote = client;
        String proto = request.getHeader("X-Forwarded-Proto");
        final String scheme = proto == null ? null : proto.trim().equalsIgnoreCase("https") ? "https"
                : proto.trim().equalsIgnoreCase("http") ? "http" : null;
        final String host = request.getHeader("X-Forwarded-Host");
        final String port = request.getHeader("X-Forwarded-Port");
        chain.doFilter(new RequestWrapper(request) {

            @Override
            public String getRemoteAddr() {
                return remote != null ? remote : super.getRemoteAddr();
            }

            @Override
            public String getScheme() {
                return scheme != null ? scheme : super.getScheme();
            }

            @Override
            public boolean isSecure() {
                return scheme != null ? "https".equals(scheme) : super.isSecure();
            }

            @Override
            public String getServerName() {
                if (host == null) {
                    return super.getServerName();
                }
                String h = firstHost(host);
                int colon = h.lastIndexOf(':');
                return colon > h.lastIndexOf(']') ? h.substring(0, colon) : h;
            }

            /** From the forwarded (else the own) Host, else X-Forwarded-Port, else the forwarded scheme's default. */
            @Override
            public int getServerPort() {
                if (host == null && port == null && scheme == null) {
                    return super.getServerPort();
                }
                String authority = host != null ? firstHost(host) : getHeader("Host");
                if (authority != null) {
                    String h = authority;
                    int colon = h.lastIndexOf(':');
                    if (colon > h.lastIndexOf(']')) {
                        int p = parsePort(h.substring(colon + 1));
                        if (p > 0) {
                            return p;
                        }
                    }
                }
                int p = port != null ? parsePort(port.split(",")[0].trim()) : -1;
                if (p > 0) {
                    return p;
                }
                return "https".equals(getScheme()) ? 443 : 80;
            }
        }, response);
    }

    private static String firstHost(String header) {
        return header.split(",")[0].trim();
    }

    /** @return the port, or -1 unless 1-65535 in digits */
    private static int parsePort(String s) {
        if (s.isEmpty() || s.length() > 5) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return -1;
            }
        }
        int p = Integer.parseInt(s);
        return p >= 1 && p <= 65535 ? p : -1;
    }
}
