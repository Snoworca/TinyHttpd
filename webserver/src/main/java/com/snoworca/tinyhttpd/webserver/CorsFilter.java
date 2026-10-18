package com.snoworca.tinyhttpd.webserver;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.FilterChain;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/**
 * Cross-origin resource sharing. Allowed origins get {@code Access-Control-Allow-Origin} on normal responses and a
 * 204 answer to preflight requests; other origins get no CORS headers (the browser then blocks the response).
 * The {@code *} wildcard cannot be combined with credentials.
 */
public final class CorsFilter implements Filter {

    private final Set<String> origins;

    private final boolean any;

    private String methods = "GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS";

    private String headers = "Content-Type, Authorization, X-Requested-With, Range";

    private int maxAge = 600;

    private boolean credentials;

    /** @param allowedOrigins exact origins ({@code https://host:port}) or {@code *} */
    public CorsFilter(String... allowedOrigins) {
        this.origins = new LinkedHashSet<String>(Arrays.asList(allowedOrigins));
        this.any = origins.contains("*");
    }

    /** @param m allowed methods, comma separated @return this filter */
    public CorsFilter allowMethods(String m) {
        this.methods = m;
        return this;
    }

    /** @param h allowed request headers, comma separated @return this filter */
    public CorsFilter allowHeaders(String h) {
        this.headers = h;
        return this;
    }

    /** @param seconds preflight cache time @return this filter */
    public CorsFilter maxAge(int seconds) {
        this.maxAge = seconds;
        return this;
    }

    /**
     * @param allow send {@code Access-Control-Allow-Credentials: true}
     * @return this filter
     * @throws IllegalArgumentException with the {@code *} wildcard
     */
    public CorsFilter allowCredentials(boolean allow) {
        if (allow && any) {
            throw new IllegalArgumentException("credentials cannot be allowed for the * origin");
        }
        this.credentials = allow;
        return this;
    }

    @Override
    public void doFilter(Request req, Response res, FilterChain chain) throws IOException, HttpException {
        String origin = req.getHeader("Origin");
        boolean allowed = origin != null && (any || origins.contains(origin));
        if (origin != null) {
            res.addHeader("Vary", "Origin");
        }
        if ("OPTIONS".equals(req.getMethod()) && origin != null && req.getHeader("Access-Control-Request-Method") != null) {
            if (allowed) {
                allowOrigin(res, origin);
                res.setHeader("Access-Control-Allow-Methods", methods);
                res.setHeader("Access-Control-Allow-Headers", headers);
                res.setHeader("Access-Control-Max-Age", Integer.toString(maxAge));
            }
            res.setStatus(204);
            return;
        }
        if (allowed) {
            allowOrigin(res, origin);
        }
        chain.doFilter(req, res);
    }

    private void allowOrigin(Response res, String origin) {
        res.setHeader("Access-Control-Allow-Origin", any && !credentials ? "*" : origin);
        if (credentials) {
            res.setHeader("Access-Control-Allow-Credentials", "true");
        }
    }
}
