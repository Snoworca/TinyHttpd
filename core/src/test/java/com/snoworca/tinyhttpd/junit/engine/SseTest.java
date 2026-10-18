package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.EventBroadcaster;
import com.snoworca.tinyhttpd.http.EventStream;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.filter.GzipFilter;
import com.snoworca.tinyhttpd.testing.Clients;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

import okhttp3.OkHttpClient;
import okio.BufferedSource;

/** P6 Server-Sent Events (SSE-nn, docs/next/p06-sse.md), TH-18 and IO-26. */
public class SseTest extends EngineTestBase {

    private final AtomicReference<Object> seen = new AtomicReference<Object>();

    private final EventBroadcaster hub = new EventBroadcaster();

    private final CountDownLatch opened = new CountDownLatch(1);

    private void streamRoute(final long heartbeat) {
        routes.put("/events", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                EventStream es = res.startEventStream();
                seen.set(es.getLastEventId());
                hub.subscribe(es);
                opened.countDown();
                try {
                    es.awaitClosed(heartbeat);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        routes.put("/hello", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, "hello");
            }
        });
    }

    private RawConnection openStream(String extraHeaders) throws IOException {
        RawConnection c = connect();
        c.send("GET /events HTTP/1.1\r\nHost: x\r\n" + extraHeaders + "\r\n");
        return c;
    }

    /** Reads the response head of a streaming response. */
    private static RawResponse head(RawConnection c) throws IOException {
        return c.read(true);
    }

    /** Reads one chunk's payload. */
    private static String chunk(RawConnection c) throws IOException {
        int size = Integer.parseInt(c.readLine().trim(), 16);
        String payload = new String(c.readExactly(size), "UTF-8");
        c.readLine();
        return payload;
    }

    @Test
    public void sse01_sse10_headersArriveImmediately() throws Exception {
        streamRoute(10000);
        start();
        RawConnection c = openStream("Last-Event-ID: 42\r\n");
        try {
            RawResponse r = head(c);
            assertEquals("text/event-stream; charset=UTF-8", r.header("content-type"));
            assertEquals("chunked", r.header("transfer-encoding"));
            assertEquals("no-cache", r.header("cache-control"));
            assertNull(r.header("content-encoding"));
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            assertEquals("42", seen.get());
        } finally {
            c.close();
        }
    }

    @Test
    public void sse02_sse03_serialization() throws Exception {
        routes.put("/one", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                EventStream es = res.startEventStream();
                es.send("update", "a\nb\r\nc", "7");
                try {
                    es.send("bad\nevent", "x", null);
                } catch (IllegalArgumentException e) {
                    seen.set(e);
                }
            }
        });
        start();
        RawConnection c = connect();
        try {
            c.send("GET /one HTTP/1.1\r\nHost: x\r\n\r\n");
            RawResponse r = c.read();
            assertEquals("event: update\nid: 7\ndata: a\ndata: b\ndata: c\n\n", r.body);
        } finally {
            c.close();
        }
        assertTrue(seen.get() instanceof IllegalArgumentException);
    }

    @Test
    public void sse04_heartbeat() throws Exception {
        streamRoute(300);
        start();
        RawConnection c = openStream("");
        try {
            head(c);
            long start = System.currentTimeMillis();
            assertEquals(": ping\n\n", chunk(c));
            assertEquals(": ping\n\n", chunk(c));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("elapsed " + elapsed, elapsed >= 450 && elapsed < 1500);
        } finally {
            c.close();
        }
    }

    @Test
    public void sse05_disconnectedClientEndsStream() throws Exception {
        streamRoute(200);
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().writeTimeout(500));
        RawConnection c = openStream("");
        head(c);
        c.socket().setSoLinger(true, 0);
        c.close();
        long deadline = System.currentTimeMillis() + 2000;
        while (server.getStats().openConnections > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, server.getStats().openConnections);
        assertEquals(0, server.getStats().eventStreams);
    }

    @Test
    public void sse06_slowSubscriberDoesNotDelayOthers() throws Exception {
        streamRoute(60000);
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().eventQueueEvents(8).writeTimeout(60000));
        List<RawConnection> fast = new ArrayList<RawConnection>();
        RawConnection slow = new RawConnection(port, 2048);
        slow.send("GET /events HTTP/1.1\r\nHost: x\r\n\r\n");
        for (int i = 0; i < 2; i++) {
            fast.add(openStream(""));
        }
        long deadline = System.currentTimeMillis() + 2000;
        while (hub.size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        for (RawConnection f : fast) {
            head(f);
        }
        String payload = repeat('d', 2000);
        // Linux autotunes socket buffers to megabytes, so keep sending until the slow queue overflows.
        for (int i = 0; i < 20000 && server.getStats().slowConsumerCloses == 0; i++) {
            hub.broadcast("e", payload, Integer.toString(i));
            for (RawConnection f : fast) {
                assertTrue(chunk(f).contains("id: " + i + "\n"));
            }
        }
        assertEquals(1, server.getStats().slowConsumerCloses);
        assertEquals(2, hub.size());
        slow.close();
        for (RawConnection f : fast) {
            f.close();
        }
    }

    @Test
    public void sse07_awaitClosedOnAnotherThread() throws Exception {
        routes.put("/x", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                final EventStream es = res.startEventStream();
                Thread t = new Thread(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            es.awaitClosed(100);
                        } catch (IllegalStateException e) {
                            seen.set(e);
                        } catch (InterruptedException e) {
                            seen.set(e);
                        }
                    }
                });
                t.start();
                try {
                    t.join();
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        start();
        RawConnection c = connect();
        try {
            c.send("GET /x HTTP/1.1\r\nHost: x\r\n\r\n");
            c.read();
        } finally {
            c.close();
        }
        assertTrue(String.valueOf(seen.get()), seen.get() instanceof IllegalStateException);
    }

    @Test
    public void sse08_sse09_limitAndHead() throws Exception {
        streamRoute(60000);
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxEventStreams(1));
        RawConnection first = openStream("");
        try {
            head(first);
            RawConnection second = openStream("");
            try {
                RawResponse r = second.read();
                assertEquals(503, r.status());
                assertEquals("5", r.header("retry-after"));
                second.send("HEAD /events HTTP/1.1\r\nHost: x\r\n\r\n");
                RawResponse h = second.read(true);
                assertEquals(200, h.status());
                assertEquals("text/event-stream; charset=UTF-8", h.header("content-type"));
                second.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
                assertEquals("hello", second.read().body);
            } finally {
                second.close();
            }
        } finally {
            first.close();
        }
    }

    @Test
    public void sse11_stopEndsStreamsCleanly() throws Exception {
        streamRoute(60000);
        start();
        RawConnection a = openStream("");
        RawConnection b = openStream("");
        head(a);
        head(b);
        long start = System.currentTimeMillis();
        server.stop(1000);
        assertTrue(System.currentTimeMillis() - start < 1500);
        for (RawConnection c : new RawConnection[] {a, b}) {
            assertEquals("0", c.readLine());
            c.close();
        }
    }

    @Test
    public void sse12_okHttpReceivesEventsInOrder() throws Exception {
        routes.put("/hundred", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                EventStream es = res.startEventStream();
                for (int i = 0; i < 100; i++) {
                    es.send("n", "value " + i, Integer.toString(i));
                }
            }
        });
        start();
        OkHttpClient client = Clients.okHttp();
        try {
            okhttp3.Response r = client.newCall(new okhttp3.Request.Builder()
                    .url("http://127.0.0.1:" + port + "/hundred").build()).execute();
            BufferedSource src = r.body().source();
            for (int i = 0; i < 100; i++) {
                assertEquals("event: n", src.readUtf8Line());
                assertEquals("id: " + i, src.readUtf8Line());
                assertEquals("data: value " + i, src.readUtf8Line());
                assertEquals("", src.readUtf8Line());
            }
            r.close();
        } finally {
            Clients.shutdown(client);
        }
    }

    @Test
    public void sse13_ownerSendKeepsQueueOrder() throws Exception {
        routes.put("/order", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                final EventStream es = res.startEventStream();
                Thread other = new Thread(new Runnable() {

                    @Override
                    public void run() {
                        for (int i = 0; i < 5; i++) {
                            try {
                                es.send(null, "q" + i, null);
                            } catch (IOException e) {
                                seen.set(e);
                            }
                        }
                    }
                });
                other.start();
                try {
                    other.join();
                } catch (InterruptedException e) {
                    return;
                }
                es.send(null, "owner", null);
            }
        });
        start();
        RawConnection c = connect();
        try {
            c.send("GET /order HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("data: q0\n\ndata: q1\n\ndata: q2\n\ndata: q3\n\ndata: q4\n\ndata: owner\n\n", c.read().body);
        } finally {
            c.close();
        }
    }

    @Test
    public void th18_streamsDoNotUseTheWorkerBudget() throws Exception {
        streamRoute(60000);
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(4).maxConnections(4).maxEventStreams(8));
        List<RawConnection> streams = new ArrayList<RawConnection>();
        try {
            for (int i = 0; i < 4; i++) {
                RawConnection s = openStream("");
                head(s);
                streams.add(s);
            }
            RawConnection c = connect();
            try {
                c.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
                assertEquals("hello", c.read().body);
            } finally {
                c.close();
            }
            assertEquals(4, server.getStats().eventStreams);
        } finally {
            for (RawConnection s : streams) {
                s.close();
            }
        }
    }

    @Test
    public void io26_gzipLeavesEventStreamsAlone() throws Exception {
        streamRoute(60000);
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().filter(new GzipFilter()));
        RawConnection c = openStream("Accept-Encoding: gzip\r\n");
        try {
            RawResponse r = head(c);
            assertNull(r.header("content-encoding"));
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            hub.broadcast(null, "now", null);
            assertEquals("data: now\n\n", chunk(c));
        } finally {
            c.close();
        }
    }
}
