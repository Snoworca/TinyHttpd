package com.snoworca.tinyhttpd.session;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.util.Base64;

/**
 * A server-side session found by {@link SessionFilter}. Sessions exist only when the application creates them, so
 * requests without a cookie cost nothing and cannot fill the store. Attributes are thread safe.
 */
public final class Session {

    static final String ATTRIBUTE = "tinyhttpd.session";

    private static final SecureRandom RANDOM = new SecureRandom();

    volatile String id;

    final String address;

    final long created = System.currentTimeMillis();

    volatile long lastAccess = created;

    volatile boolean authenticated;

    private final String csrfToken = newId();

    private final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();

    Session(String address) {
        this.id = newId();
        this.address = address;
    }

    /** @return 128 random bits, URL-safe base64 */
    static String newId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.encodeUrlSafe(b);
    }

    /**
     * @param req a request that passed {@link SessionFilter}
     * @return its session, or null (none is created)
     */
    public static Session get(Request req) {
        Object s = req.getAttribute(ATTRIBUTE);
        return s instanceof Session ? (Session) s : null;
    }

    /**
     * Returns the request's session, creating one and setting its cookie if needed.
     *
     * @param req the request
     * @param res the response
     * @return the session
     * @throws HttpException 503 when the store is full of authenticated sessions or the client has too many
     */
    public static Session create(Request req, Response res) throws HttpException {
        Session s = get(req);
        if (s != null) {
            return s;
        }
        s = filter(req).createSession(req, res);
        req.setAttribute(ATTRIBUTE, s);
        return s;
    }

    /**
     * Issues a new id for the request's session (call after login to stop session fixation). Attributes stay; the old
     * id stops working.
     *
     * @param req the request
     * @param res the response
     * @return the session
     * @throws IllegalStateException if the request has no session
     */
    public static Session rotate(Request req, Response res) {
        Session s = get(req);
        if (s == null) {
            throw new IllegalStateException("no session");
        }
        filter(req).rotate(s, req, res);
        return s;
    }

    /**
     * Removes the session and expires its cookie.
     *
     * @param req the request
     * @param res the response
     */
    public static void invalidate(Request req, Response res) {
        Session s = get(req);
        if (s != null) {
            filter(req).invalidate(s, req, res);
            req.removeAttribute(ATTRIBUTE);
        }
    }

    private static SessionFilter filter(Request req) {
        Object f = req.getAttribute(SessionFilter.ATTRIBUTE);
        if (!(f instanceof SessionFilter)) {
            throw new IllegalStateException("SessionFilter is not installed");
        }
        return (SessionFilter) f;
    }

    /** @return the current id */
    public String getId() {
        return id;
    }

    /** @return creation time in milliseconds */
    public long getCreationTime() {
        return created;
    }

    /** @return last request time in milliseconds */
    public long getLastAccessedTime() {
        return lastAccess;
    }

    /** Marks the session as logged in: it is never evicted to make room, only expired or invalidated. */
    public void markAuthenticated() {
        authenticated = true;
    }

    /** @return true after {@link #markAuthenticated()} */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /** @return the token {@link CsrfFilter} accepts in {@code X-CSRF-Token} */
    public String getCsrfToken() {
        return csrfToken;
    }

    /**
     * @param name attribute name
     * @return the value, or null
     */
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    /**
     * @param name attribute name
     * @param value the value; null removes it
     */
    public void setAttribute(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }
}
