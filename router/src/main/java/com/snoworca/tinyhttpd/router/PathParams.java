package com.snoworca.tinyhttpd.router;

import java.util.Collections;
import java.util.Map;

import com.snoworca.tinyhttpd.http.Request;

/** Path variables captured by {@link Router}, kept in the request attribute {@value #ATTRIBUTE}. */
public final class PathParams {

    /** Request attribute holding a {@code Map<String, String>}. */
    public static final String ATTRIBUTE = "tinyhttpd.router.pathParams";

    private PathParams() {
    }

    /**
     * @param req the request
     * @param name variable name from the route pattern
     * @return the decoded value, or null
     */
    public static String get(Request req, String name) {
        return all(req).get(name);
    }

    /**
     * @param req the request
     * @return all variables of the matched route
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> all(Request req) {
        Object v = req.getAttribute(ATTRIBUTE);
        return v instanceof Map ? (Map<String, String>) v : Collections.<String, String> emptyMap();
    }
}
