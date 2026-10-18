package com.snoworca.tinyhttpd.websocket;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.UpgradedConnection;
import com.snoworca.tinyhttpd.http.util.Base64;

/**
 * Accepts WebSocket handshakes (RFC 6455 §4.2) and runs each connection's read loop on the handler thread. By default
 * only same-origin browsers (or clients without {@code Origin}) are accepted, which blocks cross-site WebSocket
 * hijacking; DNS rebinding is stopped only by the host check ({@code HostAllowlistFilter}).
 */
public class WebSocketHandler implements Handler {

    /** Creates a listener per connection. */
    public interface Factory {

        /**
         * @param handshake the handshake request
         * @return the listener, or null to refuse with 403
         */
        WebSocketListener create(Request handshake);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Factory factory;

    private final List<String> allowedOrigins = new ArrayList<String>();

    private boolean anyOrigin;

    private final List<String> subprotocols = new ArrayList<String>();

    int maxFramePayloadBytes = 1 << 20;

    int maxMessageBytes = 1 << 20;

    int pingIntervalMillis = 30000;

    /** @param factory creates a listener for each accepted connection */
    public WebSocketHandler(Factory factory) {
        this.factory = factory;
    }

    /**
     * @param origins accepted {@code Origin} values (scheme://host[:port]) besides the same origin
     * @return this handler
     */
    public WebSocketHandler allowedOrigins(String... origins) {
        allowedOrigins.addAll(Arrays.asList(origins));
        return this;
    }

    /** @return this handler, accepting any origin */
    public WebSocketHandler allowAnyOrigin() {
        anyOrigin = true;
        return this;
    }

    /**
     * @param protocols supported subprotocols in preference order
     * @return this handler
     */
    public WebSocketHandler subprotocols(String... protocols) {
        subprotocols.addAll(Arrays.asList(protocols));
        return this;
    }

    /**
     * @param bytes largest frame payload, also the largest unfragmented message (default 1 MB)
     * @return this handler
     */
    public WebSocketHandler maxFramePayloadBytes(int bytes) {
        maxFramePayloadBytes = bytes;
        return this;
    }

    /**
     * @param bytes largest message after joining fragments (default 1 MB)
     * @return this handler
     */
    public WebSocketHandler maxMessageBytes(int bytes) {
        maxMessageBytes = bytes;
        return this;
    }

    /**
     * @param millis idle time before a ping; a second silent period closes with 1001 (default 30 s)
     * @return this handler
     */
    public WebSocketHandler pingInterval(int millis) {
        pingIntervalMillis = millis;
        return this;
    }

    @Override
    public void handle(Request req, Response res) throws IOException, HttpException {
        if (!"GET".equals(req.getMethod()) || !"HTTP/1.1".equals(req.getProtocol())
                || !hasToken(req.getHeaders("Upgrade"), "websocket")
                || !hasToken(req.getHeaders("Connection"), "upgrade")) {
            throw new HttpException(400, "not a websocket handshake");
        }
        if (!"13".equals(req.getHeader("Sec-WebSocket-Version"))) {
            throw new HttpException(426, "unsupported websocket version").withHeader("Sec-WebSocket-Version", "13");
        }
        String key = req.getHeader("Sec-WebSocket-Key");
        if (key == null || !validKey(key.trim())) {
            throw new HttpException(400, "invalid Sec-WebSocket-Key");
        }
        if (!originAllowed(req)) {
            throw new HttpException(403, "origin not allowed");
        }
        WebSocketListener listener = factory.create(req);
        if (listener == null) {
            throw new HttpException(403, "refused");
        }
        String protocol = negotiate(req.getHeaders("Sec-WebSocket-Protocol"));
        res.setHeader("Sec-WebSocket-Accept", accept(key.trim()));
        if (protocol != null) {
            res.setHeader("Sec-WebSocket-Protocol", protocol);
        }
        UpgradedConnection conn = res.upgrade("websocket");
        new WebSocket(conn, req, protocol, listener, this).run();
    }

    private static boolean validKey(String key) {
        try {
            return Base64.decode(key).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** @return the {@code Sec-WebSocket-Accept} value for a key */
    static String accept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.encode(sha1.digest((key + GUID).getBytes("US-ASCII")));
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean originAllowed(Request req) {
        String origin = req.getHeader("Origin");
        if (origin == null || anyOrigin) {
            return true;
        }
        for (String o : allowedOrigins) {
            if (o.equalsIgnoreCase(origin.trim())) {
                return true;
            }
        }
        // Same origin: Origin's host and port equal the request's.
        String o = origin.trim().toLowerCase(Locale.US);
        int scheme = o.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        String authority = o.substring(scheme + 3);
        int defaultPort = o.startsWith("https") ? 443 : 80;
        String host = authority;
        int port = defaultPort;
        int colon = authority.lastIndexOf(':');
        if (colon > authority.lastIndexOf(']')) {
            host = authority.substring(0, colon);
            try {
                port = Integer.parseInt(authority.substring(colon + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        String serverName = req.getServerName();
        return serverName != null && host.equalsIgnoreCase(serverName) && port == req.getServerPort();
    }

    private String negotiate(List<String> offered) {
        for (String mine : subprotocols) {
            for (String line : offered) {
                for (String p : line.split(",")) {
                    if (p.trim().equals(mine)) {
                        return mine;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasToken(List<String> values, String token) {
        for (String v : values) {
            for (String t : v.split(",")) {
                if (t.trim().equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
        return false;
    }
}
