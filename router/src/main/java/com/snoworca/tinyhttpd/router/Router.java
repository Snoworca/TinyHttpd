package com.snoworca.tinyhttpd.router;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.RequestWrapper;
import com.snoworca.tinyhttpd.http.Response;

/**
 * Routes requests by method and path pattern. Patterns are literal segments, {@code {name}} (one segment) and
 * {@code {name*}} (the rest, last only), matched against the decoded {@link Request#getPath()}. A route with more
 * literal segments wins; ties go to the earlier registration. A known path without the method gets 405 with
 * {@code Allow}; HEAD uses GET routes; OPTIONS answers {@code Allow} automatically. Register routes before the server
 * starts; matching is then thread safe.
 */
public final class Router implements Handler {

    private static final class Route {

        final String method;

        final String[] segments;

        final int literals;

        final boolean rest;

        final Handler handler;

        final boolean mount;

        final int order;

        Route(String method, String pattern, Handler handler, boolean mount, int order) {
            this.method = method;
            this.handler = handler;
            this.mount = mount;
            this.order = order;
            String p = pattern.startsWith("/") ? pattern.substring(1) : pattern;
            this.segments = p.isEmpty() ? new String[0] : p.split("/", -1);
            int lit = 0;
            boolean r = false;
            for (int i = 0; i < segments.length; i++) {
                String s = segments[i];
                if (s.startsWith("{") && s.endsWith("*}")) {
                    if (i != segments.length - 1) {
                        throw new IllegalArgumentException("{name*} must be last: " + pattern);
                    }
                    r = true;
                } else if (!(s.startsWith("{") && s.endsWith("}"))) {
                    lit++;
                }
            }
            this.literals = lit;
            this.rest = r;
        }

        /** @return captured variables, or null if the path does not match */
        Map<String, String> match(String[] path) {
            Map<String, String> vars = new LinkedHashMap<String, String>();
            int n = segments.length;
            if (mount) {
                if (path.length < n) {
                    return null;
                }
            } else if (rest ? path.length < n - 1 : path.length != n) {
                return null;
            }
            for (int i = 0; i < n; i++) {
                String s = segments[i];
                if (rest && i == n - 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = i; j < path.length; j++) {
                        sb.append(j > i ? "/" : "").append(path[j]);
                    }
                    vars.put(s.substring(1, s.length() - 2), sb.toString());
                    return vars;
                }
                if (s.startsWith("{") && s.endsWith("}")) {
                    vars.put(s.substring(1, s.length() - 1), path[i]);
                } else if (!s.equals(path[i])) {
                    return null;
                }
            }
            return vars;
        }
    }

    private final List<Route> routes = new ArrayList<Route>();

    /**
     * @param method HTTP method
     * @param pattern path pattern
     * @param handler handler
     * @return this router
     */
    public synchronized Router route(String method, String pattern, Handler handler) {
        if (method == null || handler == null) {
            throw new IllegalArgumentException("method and handler are required");
        }
        routes.add(new Route(method, pattern, handler, false, routes.size()));
        return this;
    }

    /** @param pattern path pattern @param handler handler @return this router */
    public Router get(String pattern, Handler handler) {
        return route("GET", pattern, handler);
    }

    /** @param pattern path pattern @param handler handler @return this router */
    public Router post(String pattern, Handler handler) {
        return route("POST", pattern, handler);
    }

    /** @param pattern path pattern @param handler handler @return this router */
    public Router put(String pattern, Handler handler) {
        return route("PUT", pattern, handler);
    }

    /** @param pattern path pattern @param handler handler @return this router */
    public Router delete(String pattern, Handler handler) {
        return route("DELETE", pattern, handler);
    }

    /** @param pattern path pattern @param handler handler @return this router */
    public Router patch(String pattern, Handler handler) {
        return route("PATCH", pattern, handler);
    }

    /** @param pattern path pattern @param handler handler @return this router */
    public Router head(String pattern, Handler handler) {
        return route("HEAD", pattern, handler);
    }

    /** @param pattern path pattern @param handler handler @return this router */
    public Router options(String pattern, Handler handler) {
        return route("OPTIONS", pattern, handler);
    }

    /**
     * Sends every method under {@code prefix} to {@code handler}, which sees the rest of the path as
     * {@link Request#getPath()} (for example a nested router).
     *
     * @param prefix literal path prefix
     * @param handler handler
     * @return this router
     */
    public synchronized Router mount(String prefix, Handler handler) {
        routes.add(new Route("*", prefix, handler, true, routes.size()));
        return this;
    }

    @Override
    public void handle(Request req, Response res) throws IOException, HttpException {
        String path = req.getPath();
        String[] parts = path.equals("/") ? new String[0] : path.substring(1).split("/", -1);
        if (parts.length > 0 && parts[parts.length - 1].isEmpty()) {
            parts = java.util.Arrays.copyOf(parts, parts.length - 1);
        }
        String method = req.getMethod();
        Route best = null;
        Map<String, String> bestVars = null;
        Set<String> allowed = new LinkedHashSet<String>();
        List<Route> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<Route>(routes);
        }
        for (Route r : snapshot) {
            Map<String, String> vars = r.match(parts);
            if (vars == null) {
                continue;
            }
            if (!r.mount) {
                allowed.add(r.method);
            }
            boolean methodOk = r.mount || r.method.equals(method) || ("HEAD".equals(method) && "GET".equals(r.method));
            if (methodOk && (best == null || r.literals > best.literals
                    || r.literals == best.literals && exactBeats(r, best, method))) {
                best = r;
                bestVars = vars;
            }
        }
        if (best == null) {
            if (allowed.isEmpty()) {
                throw new HttpException(404, "not found");
            }
            String allow = allowHeader(allowed);
            if ("OPTIONS".equals(method)) {
                res.setHeader("Allow", allow);
                res.setStatus(204);
                return;
            }
            throw new HttpException(405, "method not allowed").withHeader("Allow", allow);
        }
        Map<String, String> all = new LinkedHashMap<String, String>(PathParams.all(req));
        all.putAll(bestVars);
        req.setAttribute(PathParams.ATTRIBUTE, all);
        if (best.mount) {
            StringBuilder rest = new StringBuilder();
            for (int i = best.segments.length; i < parts.length; i++) {
                rest.append('/').append(parts[i]);
            }
            final String sub = rest.length() == 0 ? "/" : rest.toString();
            best.handler.handle(new RequestWrapper(req) {

                @Override
                public String getPath() {
                    return sub;
                }
            }, res);
            return;
        }
        best.handler.handle(req, res);
    }

    /** Same literal count: an exact method beats HEAD-via-GET, otherwise registration order. */
    private static boolean exactBeats(Route r, Route best, String method) {
        return r.method.equals(method) && !best.method.equals(method);
    }

    private static String allowHeader(Set<String> methods) {
        Set<String> all = new LinkedHashSet<String>(methods);
        if (all.contains("GET")) {
            all.add("HEAD");
        }
        all.add("OPTIONS");
        StringBuilder sb = new StringBuilder();
        for (String m : all) {
            sb.append(sb.length() == 0 ? "" : ", ").append(m);
        }
        return sb.toString();
    }
}
