package com.snoworca.tinyhttpd.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.KeyManagementException;
import java.security.SecureRandom;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.snoworca.tinyhttpd.ServerConfig;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.TlsConfig;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.TestServers;
import com.snoworca.tinyhttpd.testing.TestTls;

/** Server-initiated closes end with FIN (and close_notify) rather than a reset (RFC 9112 9.6, 9.8). */
public class GracefulCloseTest {

    @Rule
    public Timeout timeout = Timeout.seconds(30);

    private HttpServer server;

    private static final Handler HANDLER = new Handler() {

        @Override
        public void handle(Request req, Response res) throws IOException {
            int size = "/big".equals(req.getPath()) ? 100 * 1024 : 5;
            OutputStream out = res.getOutputStream();
            for (int i = 0; i < size; i++) {
                out.write('x');
            }
        }
    };

    @After
    public void stop() {
        if (server != null) {
            server.stop(200);
        }
    }

    private int start(TinyHttpd.Builder b) throws IOException {
        ServerConfig config = b.handler(HANDLER).hostCheck(false).port(0).build().getConfig();
        server = new HttpServer(config);
        server.start();
        return server.getPort(0);
    }

    /** A request racing an idle close is not processed; the worker reads it away, so the client sees EOF, no reset. */
    @Test
    public void requestRacingIdleCloseIsReadAwayWithoutReset() throws Exception {
        int port = start(TestServers.builder());
        RawConnection c = new RawConnection(port);
        try {
            c.send("GET /x HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(200, c.read().status());
            Connection conn = awaitIdle();
            // What closeIdle() leaves behind when input is pending: CLOSING, nothing closed, the worker to finish.
            assertTrue(conn.phase.compareAndSet(Connection.IDLE, Connection.CLOSING));
            conn.closeRequestedAt = System.currentTimeMillis();
            // A body larger than one read leaves bytes in the kernel; closing over them would send a reset.
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < 32 * 1024; i++) {
                body.append('b');
            }
            c.send("POST /x HTTP/1.1\r\nHost: x\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
            Thread.sleep(300); // let the server act first: a read already blocked may see EOF even before a reset
            c.setReadTimeout(3000);
            assertEquals(-1, c.input().read());
            assertEquals(1, server.getStats().requests);
        } finally {
            c.close();
        }
    }

    /**
     * A TLS connection rejected after waiting in the queue gets no 503, but FIN and a drain: its ClientHello has
     * arrived and is unread, and a bare close would answer it with a reset.
     */
    @Test
    public void tlsRejectIsNotAReset() throws Exception {
        int port = start(TestServers.builder().tls(TestTls.server().build()).maxWorkers(1).maxConnections(2)
                .queueWaitTimeout(300));
        RawConnection holder = new RawConnection(port, 0, TestTls.socketFactory()); // fresh: never evicted
        try {
            awaitOpen(1);
            // Plain TCP to the TLS port, so the client side cannot hide a reset: bytes like a ClientHello's start.
            RawConnection hello = new RawConnection(port);
            try {
                hello.send(new byte[] {0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00});
                long deadline = System.currentTimeMillis() + 3000;
                while (server.getStats().rejectedOverCapacity < 1 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(1, server.getStats().rejectedOverCapacity);
                Thread.sleep(300); // a read already blocked may see EOF even before a reset
                hello.setReadTimeout(3000);
                assertEquals(-1, hello.input().read());
            } finally {
                hello.close();
            }
        } finally {
            holder.close();
        }
    }

    /** The lingering list drains once more at its deadline, so request bytes that arrived meanwhile cause no reset. */
    @Test
    public void lingeringRejectDrainsBeforeDeadlineClose() throws Exception {
        int port = start(TestServers.builder().maxWorkers(1).maxConnections(1).lingerTimeout(0));
        RawConnection holder = new RawConnection(port); // fresh: holds the only worker, never evicted
        RawConnection rejected = null;
        try {
            awaitOpen(1);
            rejected = new RawConnection(port);
            rejected.send("GET /x HTTP/1.1\r\nHost: x\r\n\r\n");
            Thread.sleep(800); // past a watchdog tick, after the zero linger deadline
            assertEquals(503, rejected.read().status());
            rejected.setReadTimeout(3000);
            assertEquals(-1, rejected.input().read()); // FIN; a close over the unread request would reset
        } finally {
            holder.close();
            if (rejected != null) {
                rejected.close();
            }
        }
    }

    /**
     * Without TLS half-close (Android's Conscrypt) the client of a close-delimited response waits for our close_notify
     * on close(); draining first would add the whole lingerTimeout.
     */
    @Test
    public void closeDelimitedTlsResponseWithoutHalfCloseEndsPromptly() throws Exception {
        TlsConfig tls = TestTls.server().build();
        Field context = TlsConfig.class.getDeclaredField("context");
        context.setAccessible(true);
        context.set(tls, noHalfClose(tls.getSslContext()));
        int port = start(TestServers.builder().tls(tls).lingerTimeout(3000));
        RawConnection c = new RawConnection(port, 0, TestTls.socketFactory());
        try {
            long start = System.currentTimeMillis();
            c.send("GET /big HTTP/1.0\r\n\r\n");
            RawConnection.RawResponse r = c.read();
            assertEquals(200, r.status());
            assertEquals(100 * 1024, r.bodyBytes.length);
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("elapsed " + elapsed, elapsed < 1500);
        } finally {
            c.close();
        }
    }

    private Connection awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            for (Connection c : server.registry) {
                if (c.phase.get() == Connection.IDLE) {
                    return c;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no idle connection");
    }

    private void awaitOpen(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (server.registry.size() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(n, server.registry.size());
    }

    // ---------------------------------------------------------------- an SSLContext whose sockets cannot half-close

    private static SSLContext noHalfClose(final SSLContext real) {
        final SSLSocketFactory factory = new SSLSocketFactory() {

            @Override
            public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
                return new NoHalfCloseSocket((SSLSocket) real.getSocketFactory().createSocket(s, host, port, autoClose));
            }

            @Override
            public String[] getDefaultCipherSuites() {
                return real.getSocketFactory().getDefaultCipherSuites();
            }

            @Override
            public String[] getSupportedCipherSuites() {
                return real.getSocketFactory().getSupportedCipherSuites();
            }

            @Override
            public Socket createSocket(String host, int port) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Socket createSocket(String host, int port, InetAddress local, int localPort) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Socket createSocket(InetAddress host, int port) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) {
                throw new UnsupportedOperationException();
            }
        };
        return new SSLContext(new SSLContextSpi() {

            @Override
            protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom sr) throws KeyManagementException {
                real.init(km, tm, sr);
            }

            @Override
            protected SSLSocketFactory engineGetSocketFactory() {
                return factory;
            }

            @Override
            protected SSLServerSocketFactory engineGetServerSocketFactory() {
                return real.getServerSocketFactory();
            }

            @Override
            protected SSLEngine engineCreateSSLEngine() {
                return real.createSSLEngine();
            }

            @Override
            protected SSLEngine engineCreateSSLEngine(String host, int port) {
                return real.createSSLEngine(host, port);
            }

            @Override
            protected SSLSessionContext engineGetServerSessionContext() {
                return real.getServerSessionContext();
            }

            @Override
            protected SSLSessionContext engineGetClientSessionContext() {
                return real.getClientSessionContext();
            }
        }, real.getProvider(), real.getProtocol()) {
        };
    }

    /** Delegates to a JDK SSLSocket but, like Conscrypt, refuses shutdownOutput. */
    private static final class NoHalfCloseSocket extends SSLSocket {

        private final SSLSocket d;

        NoHalfCloseSocket(SSLSocket d) {
            this.d = d;
        }

        @Override
        public void shutdownOutput() {
            throw new UnsupportedOperationException("no half-close");
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return d.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return d.getOutputStream();
        }

        @Override
        public synchronized void close() throws IOException {
            d.close();
        }

        @Override
        public synchronized void setSoTimeout(int timeout) throws SocketException {
            d.setSoTimeout(timeout);
        }

        @Override
        public InetAddress getInetAddress() {
            return d.getInetAddress();
        }

        @Override
        public int getPort() {
            return d.getPort();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return d.getEnabledCipherSuites();
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            d.setEnabledCipherSuites(suites);
        }

        @Override
        public String[] getSupportedProtocols() {
            return d.getSupportedProtocols();
        }

        @Override
        public String[] getEnabledProtocols() {
            return d.getEnabledProtocols();
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            d.setEnabledProtocols(protocols);
        }

        @Override
        public SSLSession getSession() {
            return d.getSession();
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener l) {
            d.addHandshakeCompletedListener(l);
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener l) {
            d.removeHandshakeCompletedListener(l);
        }

        @Override
        public void startHandshake() throws IOException {
            d.startHandshake();
        }

        @Override
        public void setUseClientMode(boolean mode) {
            d.setUseClientMode(mode);
        }

        @Override
        public boolean getUseClientMode() {
            return d.getUseClientMode();
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            d.setNeedClientAuth(need);
        }

        @Override
        public boolean getNeedClientAuth() {
            return d.getNeedClientAuth();
        }

        @Override
        public void setWantClientAuth(boolean want) {
            d.setWantClientAuth(want);
        }

        @Override
        public boolean getWantClientAuth() {
            return d.getWantClientAuth();
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
            d.setEnableSessionCreation(flag);
        }

        @Override
        public boolean getEnableSessionCreation() {
            return d.getEnableSessionCreation();
        }
    }
}
