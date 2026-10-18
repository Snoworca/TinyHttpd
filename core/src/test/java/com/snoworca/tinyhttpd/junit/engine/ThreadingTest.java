package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;

import org.junit.Before;
import org.junit.Test;

import com.snoworca.tinyhttpd.ServerConfig;
import com.snoworca.tinyhttpd.ServerStats;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.testing.Clients;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.TestTls;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

import okhttp3.OkHttpClient;

/** P3 worker pool, timeouts and lifecycle (TH-nn, docs/next/p03-threading-lifecycle.md). */
public class ThreadingTest extends EngineTestBase {

    private final CountDownLatch release = new CountDownLatch(1);

    /** Counted down when a /block handler is running (busy also counts TLS handshakes). */
    private final CountDownLatch entered = new CountDownLatch(1);

    private final AtomicReference<Object> seen = new AtomicReference<Object>();

    @Before
    public void setUp() {
        routes.put("/hello", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, "hello");
            }
        });
        routes.put("/block", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    seen.set(e);
                    return;
                }
                text(res, "released");
            }
        });
        routes.put("/read", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                try {
                    InputStream in = req.getInputStream();
                    while (in.read() != -1) {
                        // read the body
                    }
                    seen.set("read all");
                } catch (IOException e) {
                    seen.set(e);
                    throw e;
                }
            }
        });
        routes.put("/big", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.setContentLengthLong(10L << 20);
                byte[] block = new byte[64 * 1024];
                try {
                    for (int i = 0; i < 160; i++) {
                        res.getOutputStream().write(block);
                    }
                    seen.set("wrote all");
                } catch (IOException e) {
                    seen.set(e);
                    throw e;
                }
            }
        });
    }

    private RawConnection request(int port, String path) throws IOException {
        RawConnection c = new RawConnection(port, 0, tls ? TestTls.socketFactory() : null);
        c.send("GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n");
        return c;
    }

    private static void closeAll(List<RawConnection> cs) throws IOException {
        for (RawConnection c : cs) {
            c.close();
        }
    }

    private void awaitBusy(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (server.getStats().busyWorkers < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(n, server.getStats().busyWorkers);
    }

    @Test
    public void th01_idleConnectionsAreBoundedByMaxWorkers() throws IOException {
        start();
        List<RawConnection> idle = new ArrayList<RawConnection>();
        try {
            for (int i = 0; i < 100; i++) {
                RawConnection c = request(port, "/hello");
                c.read();
                idle.add(c);
            }
            ServerStats s = server.getStats();
            assertTrue(s.toString(), s.workerThreads <= 64);
        } finally {
            closeAll(idle);
        }
    }

    @Test
    public void th02_overCapacityGets503() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(2).maxConnections(2));
        List<RawConnection> busy = new ArrayList<RawConnection>();
        try {
            busy.add(request(port, "/block"));
            busy.add(request(port, "/block"));
            awaitBusy(2);
            RawConnection c = request(port, "/hello");
            busy.add(c);
            RawResponse r = c.read();
            assertEquals(503, r.status());
            assertEquals("1", r.header("retry-after"));
            assertEquals(1, server.getStats().rejectedOverCapacity);
        } finally {
            release.countDown();
            closeAll(busy);
        }
    }

    @Test
    public void th03_evictedConnectionGetsCleanClose() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(1).maxConnections(2));
        RawConnection idle = connect();
        RawConnection next = null;
        try {
            idle.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(200, idle.read().status());
            Thread.sleep(400); // past the minimum idle age for eviction
            next = connect();
            next.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(200, next.read().status());
            assertEquals(1, server.getStats().evictions);
            // FIN (and close_notify over TLS), not a reset: the read ends normally.
            idle.setReadTimeout(3000);
            assertEquals(-1, idle.input().read());
        } finally {
            idle.close();
            if (next != null) {
                next.close();
            }
        }
    }

    @Test
    public void th03_evictedTlsConnectionSendsCloseNotify() throws Exception {
        org.junit.Assume.assumeTrue(tls);
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(1).maxConnections(2));
        // Counts the server's bytes on the wire: a close_notify is one more TLS record after the response.
        final java.util.concurrent.atomic.AtomicLong fromServer = new java.util.concurrent.atomic.AtomicLong();
        final java.net.ServerSocket proxy = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        Thread pump = new Thread(() -> {
            try (java.net.Socket down = proxy.accept();
                    java.net.Socket up = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
                Thread back = new Thread(() -> copy(up, down, fromServer));
                back.setDaemon(true);
                back.start();
                copy(down, up, new java.util.concurrent.atomic.AtomicLong());
                back.join(5000);
            } catch (Exception ignored) {
                // test ends
            }
        });
        pump.setDaemon(true);
        pump.start();
        RawConnection idle = new RawConnection(proxy.getLocalPort(), 0, TestTls.socketFactory());
        RawConnection next = null;
        try {
            idle.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(200, idle.read().status());
            Thread.sleep(400);
            long afterResponse = fromServer.get();
            next = connect();
            next.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(200, next.read().status());
            idle.setReadTimeout(3000);
            assertEquals(-1, idle.input().read());
            assertTrue("no close_notify after the response", fromServer.get() > afterResponse);
        } finally {
            idle.close();
            if (next != null) {
                next.close();
            }
            proxy.close();
        }
    }

    private static void copy(java.net.Socket from, java.net.Socket to, java.util.concurrent.atomic.AtomicLong count) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = from.getInputStream().read(buf)) > 0) {
                count.addAndGet(n);
                to.getOutputStream().write(buf, 0, n);
            }
            to.shutdownOutput();
        } catch (IOException ignored) {
            // closed
        }
    }

    @Test
    public void th10_queuedConnectionGets503OnStop() throws Exception {
        org.junit.Assume.assumeFalse(tls); // a queued TLS client blocks in its handshake constructor
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(1).maxConnections(2)
                .queueWaitTimeout(30000));
        RawConnection busy = connect();
        RawConnection queued = null;
        try {
            busy.send("GET /block HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            queued = connect();
            queued.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
            long deadline = System.currentTimeMillis() + 2000;
            while (server.getStats().queuedConnections < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            Thread stopper = new Thread(() -> server.stop(300));
            stopper.start();
            assertEquals(503, queued.read().status()); // not a bare close
            release.countDown();
            stopper.join(5000);
        } finally {
            release.countDown();
            busy.close();
            if (queued != null) {
                queued.close();
            }
        }
    }

    @Test
    public void th03_freshConnectionIsNotEvicted() throws Exception {
        org.junit.Assume.assumeFalse(tls); // a queued TLS client blocks in its handshake constructor
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(1).maxConnections(2));
        RawConnection fresh = connect(); // holds the only worker, no request yet
        RawConnection queued = null;
        try {
            long deadline = System.currentTimeMillis() + 2000;
            while (server.getStats().busyWorkers + server.getStats().workerThreads < 1
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            queued = connect();
            queued.send("GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            Thread.sleep(700); // past a watchdog tick
            fresh.send("GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            assertEquals(200, fresh.read().status());
            assertEquals(200, queued.read().status());
            assertEquals(0, server.getStats().evictions);
        } finally {
            fresh.close();
            if (queued != null) {
                queued.close();
            }
        }
    }

    @Test
    public void th03_overloadAnswersEveryRequest() throws Exception {
        org.junit.Assume.assumeFalse(tls); // a queued TLS client blocks in its handshake constructor
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(2).maxConnections(4)
                .queueWaitTimeout(2000));
        final List<String> bad = new CopyOnWriteArrayList<String>();
        final java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> clients = new ArrayList<Thread>();
        for (int t = 0; t < 6; t++) {
            Thread client = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    try {
                        RawConnection c = connect();
                        try {
                            c.setReadTimeout(5000);
                            // A short burst with a pause, as a browser does on one keep-alive connection.
                            for (int j = 0; j < 4; j++) {
                                c.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
                                int st = c.read().status();
                                if (st == 503) {
                                    break;
                                }
                                if (st != 200) {
                                    bad.add("status " + st);
                                    break;
                                }
                                ok.incrementAndGet();
                                Thread.sleep(j == 1 ? 50 : 0);
                            }
                        } finally {
                            c.close();
                        }
                    } catch (IOException | InterruptedException e) {
                        bad.add(e.toString());
                    }
                }
            });
            client.start();
            clients.add(client);
        }
        for (Thread client : clients) {
            client.join(30000);
            assertFalse("client hung", client.isAlive());
        }
        assertTrue(bad.toString(), bad.isEmpty());
        assertTrue("served " + ok.get(), ok.get() > 0);
    }

    @Test
    public void th03_idleConnectionIsEvictedForANewOne() throws IOException {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(2).maxConnections(4));
        List<RawConnection> idle = new ArrayList<RawConnection>();
        try {
            for (int i = 0; i < 2; i++) {
                RawConnection c = request(port, "/hello");
                c.read();
                idle.add(c);
            }
            long start = System.currentTimeMillis();
            RawConnection c = request(port, "/hello");
            idle.add(c);
            assertEquals("hello", c.read().body);
            assertTrue(System.currentTimeMillis() - start < 1000);
            assertEquals(1, server.getStats().evictions);
        } finally {
            closeAll(idle);
        }
    }

    @Test
    public void runtimeSettersValidateAndApply() throws Exception {
        start();
        String[] positive = {"HeaderTimeout", "BodyReadTimeout", "WriteTimeout", "IdleTimeout", "IdleTimeoutUnderLoad",
            "QueueWaitTimeout", "WorkerKeepAlive"};
        String[] nonNegative = {"LingerTimeout", "ShutdownGrace", "MaxEventStreams", "MaxUpgradedConnections"};
        for (String name : positive) {
            assertRejected(name, 0);
            assertRejected(name, -1);
            assertApplied(name, 1234);
        }
        for (String name : nonNegative) {
            assertRejected(name, -1);
            assertApplied(name, 0);
            assertApplied(name, 7);
        }
        for (int[] bad : new int[][] {{0, 1}, {-1, 4}, {5, 4}}) {
            try {
                server.setWorkerLimits(bad[0], bad[1]);
                fail("accepted " + bad[0] + "," + bad[1]);
            } catch (IllegalArgumentException expected) {
                // unchanged below
            }
        }
        assertEquals(64, server.getConfig().getMaxWorkers());
        assertEquals(128, server.getConfig().getMaxConnections());
        server.setWorkerLimits(3, 3);
        assertEquals(3, server.getConfig().getMaxWorkers());
        assertEquals(3, server.getConfig().getMaxConnections());
    }

    private void assertRejected(String name, int value) throws Exception {
        int before = (Integer) ServerConfig.class.getMethod("get" + name).invoke(server.getConfig());
        try {
            TinyHttpd.class.getMethod("set" + name, int.class).invoke(server, value);
            fail(name + " accepted " + value);
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(name, e.getCause() instanceof IllegalArgumentException);
        }
        assertEquals(name, before, ServerConfig.class.getMethod("get" + name).invoke(server.getConfig()));
    }

    private void assertApplied(String name, int value) throws Exception {
        TinyHttpd.class.getMethod("set" + name, int.class).invoke(server, value);
        assertEquals(name, value, ServerConfig.class.getMethod("get" + name).invoke(server.getConfig()));
    }

    @Test
    public void th04_idleTimeoutChangedWhileRunning() throws Exception {
        start();
        server.setIdleTimeout(1000);
        RawConnection c = connect();
        try {
            long start = System.currentTimeMillis();
            // HTTP/1.0 keep-alive advertises the idle timeout, so the header shows the new value too.
            c.send("GET /hello HTTP/1.0\r\nHost: x\r\nConnection: keep-alive\r\n\r\n");
            RawResponse r = c.read();
            assertEquals(200, r.status());
            assertEquals("timeout=1", r.header("keep-alive"));
            String state = c.awaitClose(4000);
            assertTrue(state, !"open".equals(state));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("elapsed " + elapsed, elapsed >= 900 && elapsed < 4000);
        } finally {
            c.close();
        }
    }

    @Test
    public void th05_bodyReadTimeoutChangedWhileRunning() throws Exception {
        start();
        server.setBodyReadTimeout(500);
        RawConnection c = connect();
        try {
            c.send("POST /read HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\nhello");
            String state = c.awaitClose(3000);
            assertTrue(state, !"open".equals(state));
        } finally {
            c.close();
        }
        assertTrue(String.valueOf(seen.get()), seen.get() instanceof SocketTimeoutException);
    }

    @Test
    public void th06_writeTimeoutChangedWhileRunning() throws Exception {
        start();
        server.setWriteTimeout(500);
        RawConnection c = connect(4096);
        try {
            c.send("GET /big HTTP/1.1\r\nHost: x\r\n\r\n");
            long deadline = System.currentTimeMillis() + 3000;
            while (server.getStats().writeTimeouts == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, server.getStats().writeTimeouts);
        } finally {
            c.close();
        }
    }

    @Test
    public void th09_queueWaitTimeoutChangedWhileRunning() throws Exception {
        org.junit.Assume.assumeFalse(tls); // a queued TLS client blocks in its handshake constructor
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(1).maxConnections(4)
                .queueWaitTimeout(30000));
        RawConnection a = connect();
        RawConnection b = null;
        try {
            a.send("GET /block HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            server.setQueueWaitTimeout(300);
            long start = System.currentTimeMillis();
            b = connect();
            b.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(503, b.read().status());
            assertTrue(System.currentTimeMillis() - start < 2500);
            release.countDown();
            assertEquals(200, a.read().status());
        } finally {
            release.countDown();
            a.close();
            if (b != null) {
                b.close();
            }
        }
    }

    @Test
    public void th01_idleWorkersRetireWhenLimitLowered() throws Exception {
        start();
        List<RawConnection> conns = new ArrayList<RawConnection>();
        try {
            for (int i = 0; i < 4; i++) {
                RawConnection c = connect();
                c.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
                assertEquals(200, c.read().status());
                conns.add(c);
            }
        } finally {
            closeAll(conns);
        }
        long deadline = System.currentTimeMillis() + 2000;
        while (server.getStats().openConnections > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(4, server.getStats().workerThreads); // idle, kept for workerKeepAlive (60 s)
        server.setWorkerLimits(1, 128);
        deadline = System.currentTimeMillis() + 2000;
        while (server.getStats().workerThreads > 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, server.getStats().workerThreads);
        RawConnection c = connect();
        try {
            c.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(200, c.read().status());
        } finally {
            c.close();
        }
    }

    @Test
    public void th01_limitsFlippedUnderTraffic() throws Exception {
        org.junit.Assume.assumeFalse(tls); // a TLS client blocks in its handshake while queued
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(2).maxConnections(4)
                .queueWaitTimeout(2000));
        final AtomicBoolean stop = new AtomicBoolean();
        final List<String> bad = new CopyOnWriteArrayList<String>();
        final java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        final int[][] limits = {{1, 2}, {2, 4}, {8, 16}, {1, 1}, {64, 128}};
        Thread flipper = new Thread(() -> {
            for (int i = 0; !stop.get(); i++) {
                server.setWorkerLimits(limits[i % limits.length][0], limits[i % limits.length][1]);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        flipper.start();
        List<Thread> clients = new ArrayList<Thread>();
        for (int t = 0; t < 6; t++) {
            Thread client = new Thread(() -> {
                for (int i = 0; i < 40; i++) {
                    try {
                        RawConnection c = connect();
                        try {
                            c.setReadTimeout(5000);
                            c.send("GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
                            int st = c.read().status();
                            if (st == 200) {
                                ok.incrementAndGet();
                            } else if (st != 503) {
                                bad.add("status " + st);
                            }
                        } finally {
                            c.close();
                        }
                    } catch (IOException e) {
                        bad.add(e.toString()); // every request gets 200 or 503, never a dropped connection
                    }
                }
            });
            client.start();
            clients.add(client);
        }
        for (Thread client : clients) {
            client.join(30000);
            assertFalse("client hung", client.isAlive());
        }
        stop.set(true);
        flipper.join(2000);
        assertTrue(bad.toString(), bad.isEmpty());
        assertTrue("served " + ok.get(), ok.get() > 0);
        server.setWorkerLimits(2, 4);
        long deadline = System.currentTimeMillis() + 3000;
        while ((server.getStats().workerThreads > 2 || server.getStats().queuedConnections > 0)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("threads " + server.getStats().workerThreads, server.getStats().workerThreads <= 2);
        assertEquals(0, server.getStats().queuedConnections);
    }

    @Test
    public void th04_headerTimeoutChangedWhileRunning() throws Exception {
        start();
        server.setHeaderTimeout(500);
        RawConnection c = connect();
        try {
            long start = System.currentTimeMillis();
            c.send("GET /hello HTTP/1.1\r\n");
            assertEquals(408, c.read().status());
            assertTrue(System.currentTimeMillis() - start < 2000);
        } finally {
            c.close();
        }
    }

    @Test
    public void th01_workerLimitsChangedWhileRunning() throws Exception {
        // A queued TLS client blocks in its handshake constructor; the limit logic does not depend on the transport.
        org.junit.Assume.assumeFalse(tls);
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch in = new CountDownLatch(2);
        routes.put("/block", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                in.countDown();
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                text(res, "done");
            }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(1).maxConnections(4)
                .queueWaitTimeout(10000));
        String block = "GET /block HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n";
        RawConnection a = connect();
        RawConnection b = null;
        RawConnection c = null;
        try {
            a.send(block);
            long deadline = System.currentTimeMillis() + 2000;
            // b must arrive while a is busy, or queueing b evicts a as idle.
            while (in.getCount() > 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            b = connect();
            b.send(block);
            while (server.getStats().queuedConnections < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, server.getStats().queuedConnections);
            server.setWorkerLimits(2, 4);
            assertTrue("queued request did not start", in.await(2, TimeUnit.SECONDS));
            assertEquals(0, server.getStats().queuedConnections);
            // Lower the limit while both workers are busy: one of them retires when its connection ends.
            server.setWorkerLimits(1, 4);
            c = connect();
            c.send(block);
            gate.countDown();
            assertEquals(200, a.read().status());
            assertEquals(200, b.read().status());
            assertEquals(200, c.read().status());
            deadline = System.currentTimeMillis() + 2000;
            while (server.getStats().workerThreads > 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, server.getStats().workerThreads);
        } finally {
            a.close();
            if (b != null) {
                b.close();
            }
            if (c != null) {
                c.close();
            }
        }
        try {
            server.setWorkerLimits(2, 1);
            fail("maxConnections below maxWorkers accepted");
        } catch (IllegalArgumentException expected) {
            // keeps the invariant
        }
        assertEquals(1, server.getConfig().getMaxWorkers());
    }

    @Test
    public void th04_slowHeaderGets408() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().headerTimeout(500));
        final RawConnection c = connect();
        try {
            long start = System.currentTimeMillis();
            Thread sender = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        c.sendSlowly("GET /hello HTTP/1.1\r\nHost: x\r\nX-Pad: aaaaaaaaaaaaaaa\r\n\r\n".getBytes("US-ASCII"),
                                1, 100);
                    } catch (IOException closedByServer) {
                        // the server answered and closed while we were still sending
                    }
                }
            });
            sender.setDaemon(true);
            sender.start();
            RawResponse r = c.read();
            long elapsed = System.currentTimeMillis() - start;
            assertEquals(408, r.status());
            assertTrue("elapsed " + elapsed, elapsed >= 400 && elapsed < 2000);
            assertEquals(1, server.getStats().headerTimeouts);
        } finally {
            c.close();
        }
    }

    @Test
    public void th05_stalledBodyTimesOut() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().bodyReadTimeout(500));
        RawConnection c = connect();
        try {
            c.send("POST /read HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\nhello");
            String state = c.awaitClose(3000);
            assertTrue(state, !"open".equals(state));
        } finally {
            c.close();
        }
        assertTrue(String.valueOf(seen.get()), seen.get() instanceof SocketTimeoutException);
    }

    @Test
    public void th06_stalledWriteIsClosedByWatchdog() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().writeTimeout(500));
        RawConnection c = connect(4096);
        try {
            c.send("GET /big HTTP/1.1\r\nHost: x\r\n\r\n");
            long deadline = System.currentTimeMillis() + 3000;
            while (seen.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(String.valueOf(seen.get()), seen.get() instanceof IOException);
            assertEquals(1, server.getStats().writeTimeouts);
            long idleDeadline = System.currentTimeMillis() + 1500;
            while (server.getStats().busyWorkers > 0 && System.currentTimeMillis() < idleDeadline) {
                Thread.sleep(20);
            }
            assertEquals(0, server.getStats().busyWorkers);
        } finally {
            c.close();
        }
    }

    /** AUD-01: draining an unread body has a total deadline, so trickling bytes cannot hold the worker. */
    @Test
    public void aud01_slowDrainIsBounded() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().bodyReadTimeout(500));
        final RawConnection c = new RawConnection(port, 0, tls ? TestTls.socketFactory() : null);
        try {
            c.send("POST /hello HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\n");
            Thread drip = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        c.sendSlowly(new byte[1000], 1, 100);
                    } catch (IOException closed) {
                        // the server gave up
                    }
                }
            });
            drip.setDaemon(true);
            drip.start();
            assertEquals("hello", c.read().body);
            long start = System.currentTimeMillis();
            String state = c.awaitClose(4000);
            assertTrue(state + " after " + (System.currentTimeMillis() - start) + " ms", !"open".equals(state));
        } finally {
            c.close();
        }
    }

    @Test
    public void th07_idleTimeout() throws IOException {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().idleTimeout(500));
        RawConnection c = request(port, "/hello");
        try {
            c.read();
            long start = System.currentTimeMillis();
            assertEquals("closed", c.awaitClose(3000));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("elapsed " + elapsed, elapsed >= 400 && elapsed < 1500);
        } finally {
            c.close();
        }
    }

    @Test
    public void th08_adaptiveIdleTimeoutUnderLoad() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(10).maxConnections(20).idleTimeout(10000).idleTimeoutUnderLoad(300));
        List<RawConnection> cs = new ArrayList<RawConnection>();
        try {
            RawConnection idle = request(port, "/hello");
            idle.read();
            cs.add(idle);
            for (int i = 0; i < 8; i++) {
                cs.add(request(port, "/block"));
            }
            awaitBusy(8);
            assertEquals("closed", idle.awaitClose(2500));
            release.countDown();
            for (int i = 1; i < cs.size(); i++) {
                cs.get(i).read();
            }
            Thread.sleep(1200); // two watchdog periods: load is gone
            RawConnection fresh = request(port, "/hello");
            cs.add(fresh);
            fresh.read();
            assertEquals("open", fresh.awaitClose(1000));
        } finally {
            release.countDown();
            closeAll(cs);
        }
    }

    @Test
    public void th09_queuedConnectionTimesOutWith503() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(1).maxConnections(2).queueWaitTimeout(500));
        RawConnection busy = request(port, "/block");
        try {
            awaitBusy(1);
            long start = System.currentTimeMillis();
            RawConnection queued = request(port, "/hello");
            try {
                RawResponse r = queued.read();
                long elapsed = System.currentTimeMillis() - start;
                assertEquals(503, r.status());
                assertTrue("elapsed " + elapsed, elapsed >= 450 && elapsed < 1600);
            } finally {
                queued.close();
            }
        } finally {
            release.countDown();
            busy.close();
        }
    }

    @Test
    public void th10_gracefulStop() throws Exception {
        start();
        List<RawConnection> idle = new ArrayList<RawConnection>();
        for (int i = 0; i < 3; i++) {
            RawConnection c = request(port, "/hello");
            c.read();
            idle.add(c);
        }
        final RawConnection inFlight = request(port, "/block");
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        Thread stopper = new Thread(new Runnable() {

            @Override
            public void run() {
                server.stop(2000);
            }
        });
        stopper.start();
        for (RawConnection c : idle) {
            assertEquals("closed", c.awaitClose(1000));
        }
        release.countDown();
        RawResponse r = inFlight.read();
        assertEquals("released", r.body);
        assertEquals("close", r.header("connection"));
        stopper.join(3000);
        closeAll(idle);
        inFlight.close();
    }

    @Test
    public void th11_stopInterruptsStuckHandlers() throws Exception {
        start();
        RawConnection reading = connect();
        reading.send("POST /read HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\n");
        RawConnection sleeping = request(port, "/block");
        awaitBusy(2);
        long start = System.currentTimeMillis();
        server.stop(500);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("stop took " + elapsed, elapsed >= 450 && elapsed < 2000);
        ServerStats s = server.getStats();
        assertEquals(s.toString(), 0, s.workersLeftAfterStop);
        reading.close();
        sleeping.close();
    }

    @Test
    public void th12_bindFailureIsThrownByStart() throws IOException {
        start();
        TinyHttpd second = com.snoworca.tinyhttpd.testing.TestServers.builder().port(port).handler(router).build();
        try {
            second.start();
            second.stop();
            fail("second server bound a used port");
        } catch (BindException expected) {
            // start() reports it directly
        }
    }

    @Test
    public void th14_startStopDoesNotLeak() throws Exception {
        int before = tinyThreads();
        for (int i = 0; i < 100; i++) {
            TinyHttpd.Builder b = com.snoworca.tinyhttpd.testing.TestServers.builder().port(0).hostCheck(false).handler(router);
            TinyHttpd s = (tls ? b.tls(TestTls.server().build()) : b).build();
            s.start();
            RawConnection c = request(s.getPort(), "/hello");
            c.read();
            s.stop(200);
            c.close();
            assertEquals(0, s.getStats().openConnections);
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (tinyThreads() > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(before, tinyThreads());
    }

    private static int tinyThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("tinyhttpd-") && t.isAlive()) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void th15_okHttpAroundIdleClose() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().idleTimeout(200));
        for (boolean retry : new boolean[] {false, true}) {
            OkHttpClient client = Clients.okHttp(retry, null);
            int failures = 0;
            try {
                for (int i = 0; i < 30; i++) {
                    try {
                        Clients.get(client, url("/hello"));
                    } catch (IOException e) {
                        failures++;
                    }
                    Thread.sleep(195 + (i % 10));
                }
            } finally {
                Clients.shutdown(client);
            }
            System.out.println("TH-15 retryOnConnectionFailure=" + retry + " failures=" + failures);
            if (retry) {
                assertEquals(0, failures);
            }
        }
    }

    @Test
    public void th16_perAddressLimit() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxConnectionsPerAddress(32));
        List<RawConnection> cs = new ArrayList<RawConnection>();
        try {
            for (int i = 0; i < 32; i++) {
                RawConnection c = request(port, "/hello");
                c.read();
                cs.add(c);
            }
            RawConnection extra = request(port, "/hello");
            cs.add(extra);
            assertEquals(503, extra.read().status());
            assertEquals(1, server.getStats().rejectedPerAddress);
        } finally {
            closeAll(cs);
        }
    }

    @Test
    public void th17_httpUrlConnectionReuseAndRecovery() throws Exception {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().idleTimeout(300));
        for (int i = 0; i < 5; i++) {
            assertEquals("hello", jdkGet("/hello"));
        }
        assertEquals(1, server.getStats().acceptedConnections);
        Thread.sleep(800);
        assertEquals("hello", jdkGet("/hello"));
        assertEquals(2, server.getStats().acceptedConnections);
    }

    private String jdkGet(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url(path)).openConnection();
        if (c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(TestTls.socketFactory());
        }
        InputStream in = c.getInputStream();
        byte[] b = new byte[64];
        int n = in.read(b);
        in.close();
        return new String(b, 0, n, "UTF-8");
    }
}
