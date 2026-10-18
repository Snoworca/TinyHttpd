package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertEquals;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import com.snoworca.tinyhttpd.ServerStats;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.testing.Clients;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

import okhttp3.OkHttpClient;

/** P3 worker pool, timeouts and lifecycle (TH-nn, docs/next/p03-threading-lifecycle.md). */
public class ThreadingTest extends EngineTestBase {

    private final CountDownLatch release = new CountDownLatch(1);

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

    private static RawConnection request(int port, String path) throws IOException {
        RawConnection c = new RawConnection(port);
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
        start(TinyHttpd.builder().maxWorkers(2).maxConnections(2));
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
    public void th03_idleConnectionIsEvictedForANewOne() throws IOException {
        start(TinyHttpd.builder().maxWorkers(2).maxConnections(4));
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
    public void th04_slowHeaderGets408() throws Exception {
        start(TinyHttpd.builder().headerTimeout(500));
        final RawConnection c = new RawConnection(port);
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
        start(TinyHttpd.builder().bodyReadTimeout(500));
        RawConnection c = new RawConnection(port);
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
        start(TinyHttpd.builder().writeTimeout(500));
        RawConnection c = new RawConnection(port, 4096);
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

    @Test
    public void th07_idleTimeout() throws IOException {
        start(TinyHttpd.builder().idleTimeout(500));
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
        start(TinyHttpd.builder().maxWorkers(10).maxConnections(20).idleTimeout(10000).idleTimeoutUnderLoad(300));
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
        start(TinyHttpd.builder().maxWorkers(1).maxConnections(2).queueWaitTimeout(500));
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
        awaitBusy(1);
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
        RawConnection reading = new RawConnection(port);
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
        TinyHttpd second = TinyHttpd.builder().port(port).handler(router).build();
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
            TinyHttpd s = TinyHttpd.builder().port(0).hostCheck(false).handler(router).build();
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
        start(TinyHttpd.builder().idleTimeout(200));
        for (boolean retry : new boolean[] {false, true}) {
            OkHttpClient client = Clients.okHttp(retry, null);
            int failures = 0;
            try {
                for (int i = 0; i < 30; i++) {
                    try {
                        Clients.get(client, "http://127.0.0.1:" + port + "/hello");
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
        start(TinyHttpd.builder().maxConnectionsPerAddress(32));
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
        start(TinyHttpd.builder().idleTimeout(300));
        for (int i = 0; i < 5; i++) {
            assertEquals("hello", jdkGet("/hello"));
        }
        assertEquals(1, server.getStats().acceptedConnections);
        Thread.sleep(800);
        assertEquals("hello", jdkGet("/hello"));
        assertEquals(2, server.getStats().acceptedConnections);
    }

    private String jdkGet(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        InputStream in = c.getInputStream();
        byte[] b = new byte[64];
        int n = in.read(b);
        in.close();
        return new String(b, 0, n, "UTF-8");
    }
}
