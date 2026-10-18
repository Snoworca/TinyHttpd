package com.snoworca.tinyhttpd.session;

import java.io.IOException;

import com.snoworca.tinyhttpd.http.Cookie;
import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.FilterChain;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/**
 * Finds the request's session from its cookie ({@code TSESSIONID}, {@code HttpOnly}, {@code SameSite=Lax},
 * {@code Secure} over TLS). Sessions expire after {@code maxInactive}; expired ones are swept on access and every
 * {@code cleanupInterval} without a thread. When {@code maxSessions} is reached, expired sessions go first, then the
 * least recently used unauthenticated one; if every session is authenticated, creation fails with 503.
 */
public final class SessionFilter implements Filter {

    static final String ATTRIBUTE = "tinyhttpd.sessionFilter";

    private final SessionStore store;

    private String cookieName = "TSESSIONID";

    private long maxInactiveMillis = 30 * 60 * 1000L;

    private int maxSessions = 1000;

    private int maxSessionsPerAddress = 50;

    private long cleanupIntervalMillis = 60 * 1000L;

    private volatile long lastCleanup = System.currentTimeMillis();

    /** In-memory store with the defaults. */
    public SessionFilter() {
        this(SessionStore.memory());
    }

    /** @param store session store */
    public SessionFilter(SessionStore store) {
        this.store = store;
    }

    /** @param name cookie name @return this filter */
    public SessionFilter cookieName(String name) {
        this.cookieName = name;
        return this;
    }

    /** @param millis inactivity before expiry (default 30 min) @return this filter */
    public SessionFilter maxInactive(long millis) {
        this.maxInactiveMillis = millis;
        return this;
    }

    /** @param n most sessions (default 1000) @return this filter */
    public SessionFilter maxSessions(int n) {
        this.maxSessions = n;
        return this;
    }

    /** @param n most sessions per client address (default 50) @return this filter */
    public SessionFilter maxSessionsPerAddress(int n) {
        this.maxSessionsPerAddress = n;
        return this;
    }

    /** @param millis how often expired sessions are swept (default 60 s) @return this filter */
    public SessionFilter cleanupInterval(long millis) {
        this.cleanupIntervalMillis = millis;
        return this;
    }

    @Override
    public void doFilter(Request req, Response res, FilterChain chain) throws IOException, HttpException {
        long now = System.currentTimeMillis();
        if (now - lastCleanup > cleanupIntervalMillis) {
            lastCleanup = now;
            sweep(now);
        }
        req.setAttribute(ATTRIBUTE, this);
        for (Cookie c : req.getCookies()) {
            if (c.getName().equals(cookieName)) {
                Session s = store.find(c.getValue());
                if (s != null && now - s.lastAccess > maxInactiveMillis) {
                    store.remove(s.id);
                    s = null;
                }
                if (s != null) {
                    s.lastAccess = now;
                    req.setAttribute(Session.ATTRIBUTE, s);
                    break;
                }
            }
        }
        chain.doFilter(req, res);
    }

    private void sweep(long now) {
        for (Session s : store.all()) {
            if (now - s.lastAccess > maxInactiveMillis) {
                store.remove(s.id);
            }
        }
    }

    synchronized Session createSession(Request req, Response res) throws HttpException {
        long now = System.currentTimeMillis();
        String address = req.getRemoteAddr();
        int total = 0;
        int fromAddress = 0;
        Session lru = null;
        for (Session s : store.all()) {
            if (now - s.lastAccess > maxInactiveMillis) {
                store.remove(s.id);
                continue;
            }
            total++;
            if (s.address.equals(address)) {
                fromAddress++;
            }
            if (!s.authenticated && (lru == null || s.lastAccess < lru.lastAccess)) {
                lru = s;
            }
        }
        if (fromAddress >= maxSessionsPerAddress) {
            throw new HttpException(503, "too many sessions from this address");
        }
        if (total >= maxSessions) {
            if (lru == null) {
                throw new HttpException(503, "session store full");
            }
            store.remove(lru.id);
        }
        Session s = new Session(address);
        store.save(s);
        setCookie(s, req, res, -1);
        return s;
    }

    synchronized void rotate(Session s, Request req, Response res) {
        store.remove(s.id);
        s.id = Session.newId();
        store.save(s);
        setCookie(s, req, res, -1);
    }

    synchronized void invalidate(Session s, Request req, Response res) {
        store.remove(s.id);
        setCookie(s, req, res, 0);
    }

    private void setCookie(Session s, Request req, Response res, long maxAge) {
        Cookie c = new Cookie(cookieName, maxAge == 0 ? "" : s.id).setPath("/").setHttpOnly(true).setSameSite("Lax")
                .setSecure(req.isSecure());
        if (maxAge >= 0) {
            c.setMaxAge(maxAge);
        }
        res.addCookie(c);
    }
}
