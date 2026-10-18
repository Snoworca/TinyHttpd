package com.snoworca.tinyhttpd.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.UpgradedConnection;

/**
 * A connection after {@code 101 Switching Protocols}. Reads continue from the connection's input buffer (bytes the
 * client sent right after the handshake are kept); writes go straight to the socket under the watchdog's write
 * timeout. The worker leaves the worker budget for the {@code maxUpgradedConnections} budget.
 */
final class Upgraded implements UpgradedConnection {

    private final Connection conn;

    volatile int readTimeout;

    volatile boolean serverStopping;

    private Runnable stopCallback;

    private boolean stopFired;

    private boolean counted;

    private final InputStream input = new InputStream() {

        @Override
        public int read() throws IOException {
            return conn.in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return conn.in.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            return conn.in.available();
        }
    };

    private final OutputStream output = new OutputStream() {

        @Override
        public void write(int b) throws IOException {
            conn.write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            conn.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            conn.flushSocket();
        }
    };

    private Upgraded(Connection conn) {
        this.conn = conn;
        this.readTimeout = conn.cfg.idleTimeout;
    }

    static UpgradedConnection start(Connection c, ExchangeResponse res, String protocol)
            throws IOException, HttpException {
        if (res.isCommitted()) {
            throw new IllegalStateException("response already committed");
        }
        ExchangeResponse.checkHeader("Upgrade", protocol);
        HttpServer server = c.server;
        if (server.upgraded.incrementAndGet() > server.config.maxUpgradedConnections) {
            server.upgraded.decrementAndGet();
            throw new HttpException(503, "too many upgraded connections").withHeader("Retry-After", "5");
        }
        Upgraded u = new Upgraded(c);
        u.counted = true;
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 101 Switching Protocols\r\nUpgrade: ").append(protocol).append("\r\nConnection: Upgrade\r\n");
        for (int i = 0; i < res.names.size(); i++) {
            sb.append(res.names.get(i)).append(": ").append(res.values.get(i)).append("\r\n");
        }
        byte[] head = sb.append("\r\n").toString().getBytes(Ascii.UTF8);
        res.committed = true;
        res.finished = true;
        res.keepAlive = false;
        c.upgraded = u;
        c.setPhase(Connection.UPGRADED);
        server.pool.detach(c.worker);
        try {
            c.write(head, 0, head.length);
        } catch (IOException e) {
            u.ended();
            throw e;
        }
        if (server.isStopping()) {
            u.fireStop();
        }
        return u;
    }

    @Override
    public InputStream getInputStream() {
        return input;
    }

    @Override
    public OutputStream getOutputStream() {
        return output;
    }

    @Override
    public void setReadTimeout(int millis) {
        this.readTimeout = millis;
    }

    @Override
    public boolean isServerStopping() {
        return serverStopping || conn.server.isStopping();
    }

    @Override
    public void onServerStop(Runnable callback) {
        boolean fireNow;
        synchronized (this) {
            stopCallback = callback;
            fireNow = stopFired;
        }
        if (fireNow && callback != null) {
            callback.run();
        }
    }

    /** Called by the server when it starts to stop. */
    void fireStop() {
        Runnable cb;
        synchronized (this) {
            serverStopping = true;
            if (stopFired) {
                return;
            }
            stopFired = true;
            cb = stopCallback;
        }
        if (cb != null) {
            try {
                cb.run();
            } catch (RuntimeException e) {
                conn.server.log.log(java.util.logging.Level.WARNING, "upgrade stop callback failed", e);
            }
        }
    }

    @Override
    public void close() {
        conn.abortSocket();
    }

    /** The handler returned: release the budget slot. */
    void ended() {
        synchronized (this) {
            if (!counted) {
                return;
            }
            counted = false;
        }
        conn.server.upgraded.decrementAndGet();
    }
}
