package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;

import org.junit.Before;
import org.junit.Test;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.TlsConfig;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.log.TinyLog;
import com.snoworca.tinyhttpd.testing.Clients;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.TestTls;
import com.snoworca.tinyhttpd.testing.ThreadCpu;

import okhttp3.OkHttpClient;

/** P7 TLS (TLS-nn, docs/next/p07-tls.md). The test JVM re-enables TLS 1.0/1.1 in the JDK (tls-test.security). */
public class TlsTest extends EngineTestBase {

    /** Counts log calls by level. */
    private final List<Level> logged = Collections.synchronizedList(new ArrayList<Level>());

    private final TinyLog countingLog = new TinyLog() {

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, String message, Throwable thrown) {
            logged.add(level);
        }
    };

    private final CountDownLatch release = new CountDownLatch(1);

    @Before
    public void setUp() {
        routes.put("/info", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                X509Certificate[] peer = (X509Certificate[]) req.getAttribute("tinyhttpd.tls.peerCertificates");
                text(res, req.getScheme() + " " + req.getAttribute("tinyhttpd.tls.protocol") + " "
                        + req.getAttribute("tinyhttpd.tls.cipherSuite") + " "
                        + (peer == null ? "none" : peer[0].getSubjectX500Principal().getName()));
            }
        });
        routes.put("/block", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                text(res, "released");
            }
        });
    }

    private void startTls(TlsConfig tls) throws IOException {
        start(TinyHttpd.builder().tls(tls).logger(countingLog));
    }

    private static String get(RawConnection c, String path) throws IOException {
        c.send("GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        return c.read().body;
    }

    private static SSLSocket clientSocket(SSLContext ctx, int port, String[] protocols, String[] suites)
            throws IOException {
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", port);
        s.setSoTimeout(3000);
        if (protocols != null) {
            s.setEnabledProtocols(protocols);
        }
        if (suites != null) {
            s.setEnabledCipherSuites(suites);
        }
        return s;
    }

    /** Handshake and one request; TLS 1.3 reports some failures only on the first read. */
    private static String exchange(SSLSocket s) throws IOException {
        try {
            s.startHandshake();
            OutputStream out = s.getOutputStream();
            out.write("GET /info HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
            out.flush();
            InputStream in = s.getInputStream();
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                sb.append((char) b);
            }
            return sb.toString();
        } finally {
            s.close();
        }
    }

    private int fineLogs() {
        int n = 0;
        synchronized (logged) {
            for (Level l : logged) {
                if (l == Level.FINE) {
                    n++;
                }
            }
        }
        return n;
    }

    /** TLS-01: defaults with OkHttp negotiate TLS 1.2 or later; request attributes and scheme reflect TLS. */
    @Test
    public void tls01_defaultsNegotiateModernTls() throws IOException {
        startTls(TestTls.server().build());
        OkHttpClient client = Clients.okHttp();
        try {
            okhttp3.Response r = client.newCall(
                    new okhttp3.Request.Builder().url("https://127.0.0.1:" + port + "/info").build()).execute();
            String version = r.handshake().tlsVersion().javaName();
            String body = r.body().string();
            r.close();
            assertTrue(version, version.equals("TLSv1.3") || version.equals("TLSv1.2"));
            assertTrue(body, body.startsWith("https " + version + " TLS_"));
            assertTrue(body, body.endsWith(" none"));
        } finally {
            Clients.shutdown(client);
        }
    }

    /**
     * TLS-02: a TLS 1.0-only client is refused by TinyHttpd's default protocols. The control server enables TLS 1.0 in
     * TinyHttpd and must succeed, proving the JDK itself would allow it (the test JVM relaxes
     * jdk.tls.disabledAlgorithms; without that the control assertion fails instead of passing vacuously).
     */
    @Test
    public void tls02_tls10ClientIsRefused() throws IOException {
        String[] cbc = {"TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"};
        String[] tls10 = {"TLSv1"};
        TinyHttpd control = TinyHttpd.builder().port(0).hostCheck(false).handler(router).logger(countingLog)
                .tls(TestTls.server().protocols("TLSv1.2", "TLSv1").cipherSuites(cbc).build()).build();
        control.start();
        try {
            String ok = exchange(clientSocket(TestTls.client(), control.getPort(), tls10, cbc));
            assertTrue(ok, ok.contains("https TLSv1 "));
        } finally {
            control.stop(500);
        }
        assertEquals("obsolete protocol warned", 1, Collections.frequency(logged, Level.WARNING));

        startTls(TestTls.server().cipherSuites(cbc).build());
        int before = fineLogs();
        try {
            exchange(clientSocket(TestTls.client(), port, tls10, cbc));
            fail("TLS 1.0 handshake succeeded");
        } catch (IOException expected) {
            // refused
        }
        awaitFineLogs(before + 1);
    }

    private void awaitFineLogs(int n) {
        long deadline = System.currentTimeMillis() + 2000;
        while (fineLogs() < n && System.currentTimeMillis() < deadline) {
            sleep(10);
        }
        assertTrue("FINE log for the failed handshake", fineLogs() >= n);
    }

    /** TLS-03: no common cipher suite fails the handshake. */
    @Test
    public void tls03_noCommonCipherFails() throws IOException {
        startTls(TestTls.server().protocols("TLSv1.2").cipherSuites("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384").build());
        try {
            exchange(clientSocket(TestTls.client(), port, new String[] {"TLSv1.2"},
                    new String[] {"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"}));
            fail("handshake succeeded without a common cipher suite");
        } catch (IOException expected) {
            // handshake_failure
        }
    }

    /** TLS-04: settings no provider supports make start() fail. */
    @Test
    public void tls04_unsupportedSettingsFailStart() {
        TlsConfig[] bad = {TestTls.server().protocols("TLSv0.9", "SSLv2").build(),
                TestTls.server().cipherSuites("TLS_NOT_A_SUITE").build()};
        for (TlsConfig tls : bad) {
            TinyHttpd s = TinyHttpd.builder().port(0).handler(router).tls(tls).build();
            try {
                s.start();
                s.stop(0);
                fail("start() accepted unsupported TLS settings");
            } catch (IllegalArgumentException | IOException expected) {
                assertFalse(s.isAlive());
            }
        }
    }

    /** TLS-05: a client that never handshakes is closed after the handshake timeout. */
    @Test
    public void tls05_silentClientClosedAfterHandshakeTimeout() throws IOException {
        startTls(TestTls.server().handshakeTimeout(500).build());
        RawConnection c = new RawConnection(port);
        try {
            long t0 = System.nanoTime();
            String result = c.awaitClose(5000);
            long ms = (System.nanoTime() - t0) / 1000000;
            assertTrue(result, result.equals("closed") || result.equals("reset"));
            assertTrue("closed after " + ms + " ms", ms >= 400 && ms < 2000);
        } finally {
            c.close();
        }
    }

    /**
     * TLS-06: plain HTTP on the TLS port is closed without a plain-text response. Detecting it and answering 400
     * needs createSocket(Socket, InputStream, boolean), which Android has only from API 24 (docs/research/tls.md).
     */
    @Test
    public void tls06_plainRequestOnTlsPortIsClosed() throws IOException {
        startTls(TestTls.server().build());
        RawConnection c = new RawConnection(port);
        try {
            c.send("GET / HTTP/1.1\r\nHost: x\r\n\r\n");
            byte[] reply;
            try {
                reply = c.readToEof();
            } catch (IOException reset) {
                reply = new byte[0];
            }
            String text = new String(reply, RawConnection.ASCII);
            assertFalse(text, text.startsWith("HTTP/"));
        } finally {
            c.close();
        }
    }

    /** TLS-07: NEED client authentication without a client certificate fails. */
    @Test
    public void tls07_needClientAuthWithoutCertificateFails() throws IOException {
        startTls(TestTls.server().clientAuth(TlsConfig.ClientAuth.NEED).trustManagers(TestTls.trusting("client.p12"))
                .build());
        try {
            String r = exchange(clientSocket(TestTls.client(false), port, null, null));
            fail("request without a client certificate succeeded: " + r);
        } catch (IOException expected) {
            // bad_certificate / certificate_required
        }
        assertTrue(exchange(clientSocket(TestTls.client(true), port, null, null)).contains("CN=tinyhttpd-test-client"));
    }

    /** TLS-08: WANT client authentication exposes the client certificate as a request attribute. */
    @Test
    public void tls08_wantClientAuthExposesCertificate() throws IOException {
        startTls(TestTls.server().clientAuth(TlsConfig.ClientAuth.WANT).trustManagers(TestTls.trusting("client.p12"))
                .build());
        assertTrue(exchange(clientSocket(TestTls.client(true), port, null, null)).endsWith(" CN=tinyhttpd-test-client"));
        assertTrue(exchange(clientSocket(TestTls.client(false), port, null, null)).endsWith(" none"));
    }

    /** TLS-09: a PKCS12 file loads (File variant; the InputStream variant is used by every TLS test). */
    @Test
    public void tls09_pkcs12FileLoads() throws IOException {
        File f = File.createTempFile("tinyhttpd", ".p12");
        try {
            copy(TestTls.open("server.p12"), f);
            TlsConfig tls = TlsConfig.builder().keyStore(f, null, TestTls.PASSWORD.toCharArray()).build();
            assertNotNull(tls.getSslContext());
            startTls(tls);
            RawConnection c = RawConnection.tls(port);
            try {
                assertTrue(get(c, "/info").startsWith("https "));
            } finally {
                c.close();
            }
        } finally {
            f.delete();
        }
    }

    /** TLS-10: a wrong password fails with the key store's own exception as the cause (#516). */
    @Test
    public void tls10_wrongPasswordKeepsCause() throws Exception {
        char[] wrong = "wrong".toCharArray();
        Exception original = null;
        try {
            KeyStore.getInstance("PKCS12").load(TestTls.open("server.p12"), wrong);
        } catch (IOException e) {
            original = e;
        }
        assertNotNull(original);
        try {
            TlsConfig.builder().keyStore(TestTls.open("server.p12"), "PKCS12", wrong);
            fail("wrong password accepted");
        } catch (IOException e) {
            assertNotNull(e.getCause());
            assertSame(original.getClass(), e.getCause().getClass());
            assertEquals(original.getMessage(), e.getCause().getMessage());
        }
    }

    /** TLS-11: KA-14 over TLS: unread request bytes do not destroy the response (P7-05 lingering close). */
    @Test
    public void tls11_closeWithUnreadInputKeepsResponse() throws IOException {
        routes.put("/hello", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, "hello");
            }
        });
        startTls(TestTls.server().build());
        RawConnection c = RawConnection.tls(port);
        try {
            c.send("GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            try {
                c.send(new byte[256 * 1024]);
            } catch (IOException ignored) {
                // the server may have closed; what matters is the response
            }
            sleep(300);
            assertEquals("hello", c.read().body);
        } finally {
            c.close();
        }
    }

    /** TLS-12: 1,000 junk or cut-off handshakes: at most one log line each, no spinning worker. */
    @Test
    public void tls12_junkAndCutHandshakesStayQuiet() throws Exception {
        final List<Thread> workers = Collections.synchronizedList(new ArrayList<Thread>());
        start(TinyHttpd.builder().tls(TestTls.server().build()).logger(countingLog).threadFactory(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tls12-worker");
                t.setDaemon(true);
                workers.add(t);
                return t;
            }
        }));
        SSLEngine engine = TestTls.client().createSSLEngine("localhost", port);
        engine.setUseClientMode(true);
        ByteBuffer hello = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        engine.wrap(ByteBuffer.allocate(0), hello);
        byte[] clientHello = new byte[hello.position()];
        hello.flip();
        hello.get(clientHello);
        Random random = new Random(12);
        int connections = 1000;
        for (int i = 0; i < connections; i++) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                byte[] junk;
                if (i % 2 == 0) {
                    junk = new byte[1 + random.nextInt(300)];
                    random.nextBytes(junk);
                } else {
                    junk = java.util.Arrays.copyOf(clientHello, 1 + random.nextInt(clientHello.length));
                }
                s.getOutputStream().write(junk);
            } catch (IOException ignored) {
                // the server may reset first
            } finally {
                s.close();
            }
        }
        long deadline = System.currentTimeMillis() + 10000;
        while (server.getStats().openConnections > 0 && System.currentTimeMillis() < deadline) {
            sleep(20);
        }
        assertEquals(0, server.getStats().openConnections);
        assertTrue("log lines " + logged.size(), logged.size() <= connections);
        long cpu0 = ThreadCpu.totalMillis(workers);
        sleep(500);
        long spent = ThreadCpu.totalMillis(workers) - cpu0;
        assertTrue("idle workers used " + spent + " ms CPU", spent < 100);
    }

    /** TLS-13: plain and TLS listeners on one server share the worker pool. */
    @Test
    public void tls13_plainAndTlsListenersShareWorkers() throws IOException {
        final AtomicInteger threads = new AtomicInteger();
        start(TinyHttpd.builder().listen(0).listen(0, TestTls.server().build()).maxWorkers(1).maxConnections(4)
                .threadFactory(new ThreadFactory() {

                    @Override
                    public Thread newThread(Runnable r) {
                        threads.incrementAndGet();
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        return t;
                    }
                }));
        for (int i = 0; i < 2; i++) {
            RawConnection plain = new RawConnection(server.getPort(0));
            try {
                assertTrue(get(plain, "/info").startsWith("http null"));
            } finally {
                plain.close();
            }
            RawConnection tls = RawConnection.tls(server.getPort(1));
            try {
                assertTrue(get(tls, "/info").startsWith("https TLSv1."));
            } finally {
                tls.close();
            }
        }
        assertEquals("one shared worker", 1, threads.get());
    }

    /** TLS-14: over capacity on a TLS listener closes without a response and the acceptor keeps accepting. */
    @Test
    public void tls14_overCapacityTlsClosesWithoutResponse() throws IOException {
        start(TinyHttpd.builder().listen(0, TestTls.server().build()).listen(0).maxWorkers(1).maxConnections(1));
        RawConnection busy = RawConnection.tls(server.getPort(0));
        try {
            busy.send("GET /block HTTP/1.1\r\nHost: x\r\n\r\n");
            sleep(200);
            for (int i = 0; i < 3; i++) {
                RawConnection extra = new RawConnection(server.getPort(0));
                try {
                    long t0 = System.nanoTime();
                    String result = extra.awaitClose(3000);
                    long ms = (System.nanoTime() - t0) / 1000000;
                    assertTrue(result, result.equals("closed") || result.equals("reset"));
                    assertTrue("closed after " + ms + " ms", ms < 1000);
                } finally {
                    extra.close();
                }
            }
            RawConnection plain = new RawConnection(server.getPort(1));
            try {
                plain.send("GET /info HTTP/1.1\r\nHost: x\r\n\r\n");
                assertEquals(503, plain.read().status());
            } finally {
                plain.close();
            }
            release.countDown();
            assertEquals("released", busy.read().body);
        } finally {
            busy.close();
        }
        assertTrue(server.getStats().rejectedOverCapacity >= 4);
    }

    private static void copy(InputStream in, File f) throws IOException {
        try (InputStream src = in; OutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[4096];
            int n;
            while ((n = src.read(b)) != -1) {
                out.write(b, 0, n);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
