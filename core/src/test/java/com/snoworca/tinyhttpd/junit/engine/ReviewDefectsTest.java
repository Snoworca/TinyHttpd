package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.Test;

import com.snoworca.tinyhttpd.http.FileResponses;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

/** Defects found by the second independent review (2026-10-19). */
public class ReviewDefectsTest extends EngineTestBase {

    private RawResponse get(String raw) throws IOException {
        RawConnection c = connect();
        try {
            c.send(raw);
            return c.read();
        } finally {
            c.close();
        }
    }

    /** An Error from a handler is answered with 500 and does not leak the worker slot. */
    @Test
    public void handlerErrorReturnsWorkerSlot() throws Exception {
        routes.put("/boom", new Handler() {

            @Override
            public void handle(Request req, Response res) {
                throw new AssertionError("boom");
            }
        });
        routes.put("/ok", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, "ok");
            }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(2).maxConnections(4)
                .queueWaitTimeout(1000));
        for (int i = 0; i < 3; i++) {
            assertEquals(500, get("GET /boom HTTP/1.1\r\nHost: x\r\n\r\n").status());
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(200, get("GET /ok HTTP/1.1\r\nHost: x\r\n\r\n").status());
        }
    }

    /** sendFile after body bytes were written would announce the file length but send both. */
    @Test
    public void sendFileAfterWriteDoesNotBreakFraming() throws Exception {
        final File f = File.createTempFile("review", ".txt");
        f.deleteOnExit();
        FileOutputStream out = new FileOutputStream(f);
        out.write("hello".getBytes("US-ASCII"));
        out.close();
        routes.put("/mixed", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.getOutputStream().write('X');
                res.sendFile(f, 0, f.length());
            }
        });
        routes.put("/ok", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, "ok");
            }
        });
        start();
        RawConnection c = connect();
        try {
            c.send("GET /mixed HTTP/1.1\r\nHost: x\r\n\r\nGET /ok HTTP/1.1\r\nHost: x\r\n\r\n");
            RawResponse first = c.read();
            assertEquals(500, first.status());
            RawResponse second = c.read();
            assertEquals(200, second.status());
            assertEquals("ok", second.body);
        } finally {
            c.close();
            f.delete();
        }
    }

    /** RFC 9112 3.2: one Host in every version, with uri-host [":" port] syntax. */
    @Test
    public void hostHeaderIsValidated() throws Exception {
        start();
        assertEquals(400, get("GET /x HTTP/1.1\r\nHost: a b/c@d\r\n\r\n").status());
        assertEquals(400, get("GET /x HTTP/1.1\r\nHost: a:8x\r\n\r\n").status());
        assertEquals(400, get("GET /x HTTP/1.1\r\nHost: [::1\r\n\r\n").status());
        assertEquals(400, get("GET /x HTTP/1.0\r\nHost: a\r\nHost: b\r\n\r\n").status());
        for (String ok : new String[] {"example.com", "example.com:8080", "127.0.0.1:80", "[::1]:8080", "[::1]",
            "xn--bcher-kva.example", "a_b~c.d", "%41.example", ""}) {
            assertEquals(ok, 404, get("GET /x HTTP/1.1\r\nHost: " + ok + "\r\n\r\n").status());
        }
        assertEquals(404, get("GET /x HTTP/1.0\r\n\r\n").status()); // HTTP/1.0 may omit Host
    }

    /** RFC 9112 7.1: chunked framing lines end in CRLF; a bare LF is a smuggling vector behind lenient proxies. */
    @Test
    public void chunkedBareLfIsRejected() throws Exception {
        routes.put("/c", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                java.io.InputStream in = req.getInputStream();
                while (in.read() >= 0) {
                    // drain
                }
                text(res, "read");
            }
        });
        start();
        String head = "POST /c HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n";
        assertEquals(400, get(head + "5;a\nhello\r\n0\r\n\r\n").status());
        assertEquals(400, get(head + "5\r\nhello\n0\r\n\r\n").status());
        assertEquals(400, get(head + "5\r\nhello\r\n0\n\r\n").status());
        assertEquals(200, get(head + "5;a=b\r\nhello\r\n0\r\nX-T: 1\r\n\r\n").status());
    }

    /** RFC 9110 14.1.1: range positions are DIGITs; anything else makes the header invalid, so it is ignored. */
    @Test
    public void rangeAcceptsDigitsOnly() {
        assertNull(FileResponses.parseRange("bytes=--5", 100));
        assertNull(FileResponses.parseRange("bytes=+1-2", 100));
        assertNull(FileResponses.parseRange("bytes=1-+2", 100));
        assertNull(FileResponses.parseRange("bytes=-+5", 100));
        assertNull(FileResponses.parseRange("bytes=1 0-20", 100));
        assertArrayEquals(new long[] {0, 1}, FileResponses.parseRange("bytes=0-1", 100));
        assertArrayEquals(new long[] {95, 99}, FileResponses.parseRange("bytes=-5", 100));
        assertArrayEquals(new long[] {3, 99}, FileResponses.parseRange("bytes=3-", 100));
        assertArrayEquals(new long[] {3, 4}, FileResponses.parseRange("bytes= 3 - 4 ", 100));
    }

    /** A body trickled below the minimum rate ends with 408 once the grace has passed, despite each read being quick. */
    @Test
    public void slowBodyBelowMinimumRateGets408() throws Exception {
        routes.put("/up", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                java.io.InputStream in = req.getInputStream();
                while (in.read() >= 0) {
                    // drain
                }
                text(res, "done");
            }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().minRequestBodyRate(1000));
        RawConnection c = connect();
        try {
            c.setReadTimeout(15000);
            c.send("POST /up HTTP/1.1\r\nHost: x\r\nContent-Length: 100000\r\n\r\n");
            long start = System.currentTimeMillis();
            try {
                for (int i = 0; i < 40; i++) { // 2 bytes per second, each read well inside bodyReadTimeout
                    c.send("b");
                    Thread.sleep(500);
                }
            } catch (IOException closedByServer) {
                // the server answered and closed
            }
            assertEquals(408, c.read().status());
            long elapsed = System.currentTimeMillis() - start;
            org.junit.Assert.assertTrue("elapsed " + elapsed, elapsed < 9000);
        } finally {
            c.close();
        }
    }

    /** Only time spent waiting on the client counts: a handler that reads slowly is not the client's fault. */
    @Test
    public void slowHandlerIsNotASlowClient() throws Exception {
        routes.put("/up", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                java.io.InputStream in = req.getInputStream();
                byte[] b = new byte[8192];
                int total = 0;
                for (int n; (n = in.read(b)) >= 0;) {
                    total += n;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                text(res, Integer.toString(total));
            }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().minRequestBodyRate(1000000).inputBufferBytes(8192));
        RawConnection c = connect();
        try {
            c.setReadTimeout(20000); // the handler takes about 6 s, past the 5 s grace
            c.send("POST /up HTTP/1.1\r\nHost: x\r\nContent-Length: 49152\r\n\r\n" + repeat('u', 49152));
            RawResponse r = c.read();
            assertEquals(200, r.status());
            assertEquals("49152", r.body);
        } finally {
            c.close();
        }
    }

    /** Behind a trusted proxy the port comes from X-Forwarded-Host, else X-Forwarded-Port, else the scheme default. */
    @Test
    public void forwardedHeadersSetThePortToo() throws Exception {
        routes.put("/", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, req.getServerName() + ":" + req.getServerPort());
            }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder()
                .filter(new com.snoworca.tinyhttpd.http.filter.ForwardedHeadersFilter("127.0.0.1")));
        String h = "GET / HTTP/1.1\r\nHost: inner:8080\r\n";
        assertEquals("nas.example:8443", get(h + "X-Forwarded-Host: nas.example:8443\r\n\r\n").body);
        assertEquals("nas.example:443",
                get(h + "X-Forwarded-Host: nas.example\r\nX-Forwarded-Proto: https\r\n\r\n").body);
        assertEquals("nas.example:80", get(h + "X-Forwarded-Host: nas.example\r\n\r\n").body);
        assertEquals("nas.example:443",
                get("GET / HTTP/1.1\r\nHost: nas.example\r\nX-Forwarded-Proto: https\r\n\r\n").body);
        assertEquals("nas.example:9000",
                get(h + "X-Forwarded-Host: nas.example\r\nX-Forwarded-Port: 9000\r\n\r\n").body);
        assertEquals("[::1]:8443", get(h + "X-Forwarded-Host: [::1]:8443\r\n\r\n").body);
        assertEquals("inner:8080", get(h + "\r\n").body);
    }

    /** The worker loop survives a Throwable escaping a connection (here from the logger) and returns the slot. */
    @Test
    public void throwableEscapingAConnectionReturnsTheSlot() throws Exception {
        routes.put("/fail", new Handler() {

            @Override
            public void handle(Request req, Response res) {
                throw new IllegalStateException("handler bug");
            }
        });
        routes.put("/ok", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, "ok");
            }
        });
        final java.util.concurrent.atomic.AtomicInteger caught = new java.util.concurrent.atomic.AtomicInteger();
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxWorkers(1).maxConnections(2)
                .queueWaitTimeout(1000).logger(new com.snoworca.tinyhttpd.log.TinyLog() {

                    @Override
                    public boolean isLoggable(java.util.logging.Level level) {
                        return true;
                    }

                    @Override
                    public void log(java.util.logging.Level level, String message, Throwable t) {
                        if (message.startsWith("handler failed")) {
                            throw new AssertionError("logger bug"); // escapes dispatch's catch blocks
                        }
                        if (message.startsWith("connection failed")) {
                            caught.incrementAndGet();
                        }
                    }
                }));
        for (int i = 0; i < 2; i++) {
            RawConnection c = connect();
            try {
                c.send("GET /fail HTTP/1.1\r\nHost: x\r\n\r\n");
                c.setReadTimeout(3000);
                c.input().read(); // closed without an answer
            } catch (IOException expected) {
                // reset
            } finally {
                c.close();
            }
        }
        assertEquals(200, get("GET /ok HTTP/1.1\r\nHost: x\r\n\r\n").status());
        assertEquals(2, caught.get());
    }
}
