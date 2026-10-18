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
}
