package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.ContentLengthExceededException;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

/** P2 response framing and the keep-alive decision (RS-nn, docs/next/p02-response-framing.md). */
public class ResponseFramingTest extends EngineTestBase {

    private final AtomicReference<Object> seen = new AtomicReference<Object>();

    private static Handler writes(final int status, final int bytes) {
        return new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.setStatus(status);
                res.getOutputStream().write(new byte[bytes]);
            }
        };
    }

    @Before
    public void setUp() throws IOException {
        routes.put("/ok", writes(200, 2));
        routes.put("/204", writes(204, 5));
        routes.put("/304", writes(304, 5));
        routes.put("/205", writes(205, 5));
        routes.put("/100b", writes(200, 100));
        routes.put("/100k", writes(200, 100 * 1024));
        routes.put("/1k", writes(200, 1024));
        routes.put("/declared100", new Handler() {

            @Override
            public void handle(Request req, Response res) {
                res.setContentLengthLong(100);
            }
        });
        routes.put("/over", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.setContentLengthLong(5);
                try {
                    res.getOutputStream().write("0123456789".getBytes("US-ASCII"));
                    seen.set("no exception");
                } catch (ContentLengthExceededException e) {
                    seen.set(e);
                }
            }
        });
        routes.put("/headers", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                try {
                    res.setHeader("X", "a\r\nY: b");
                    seen.set("accepted");
                } catch (IllegalArgumentException e) {
                    seen.set(e);
                }
                res.addHeader("X", "1");
                res.addHeader("X", "2");
                text(res, "h");
            }
        });
        routes.put("/runtime", new Handler() {

            @Override
            public void handle(Request req, Response res) {
                throw new IllegalStateException("boom");
            }
        });
        routes.put("/late-failure", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.getOutputStream().write(new byte[64 * 1024]);
                throw new IllegalStateException("after commit");
            }
        });
        routes.put("/404", new Handler() {

            @Override
            public void handle(Request req, Response res) throws HttpException {
                throw new HttpException(404, "nothing here");
            }
        });
        routes.put("/mix", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.getWriter();
                try {
                    res.getOutputStream();
                    seen.set("allowed");
                } catch (IllegalStateException e) {
                    seen.set(e);
                }
            }
        });
        routes.put("/hangul", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.getWriter().write("한글");
            }
        });
        routes.put("/late-header", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.getOutputStream().write('x');
                res.flushBuffer();
                res.setHeader("X-Late", "1");
                res.getOutputStream().write('y');
            }
        });
        routes.put("/close", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.setCloseConnection(true);
                text(res, "bye");
            }
        });
        routes.put("/304-declared", new Handler() {

            @Override
            public void handle(Request req, Response res) {
                res.setStatus(304);
                res.setContentLengthLong(1000);
            }
        });
    }

    private RawResponse get(RawConnection c, String path) throws IOException {
        c.send("GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n");
        return c.read();
    }

    private void nextRequestWorks(RawConnection c) throws IOException {
        RawResponse r = get(c, "/ok");
        assertEquals("HTTP/1.1 200 OK", r.statusLine);
        assertEquals(2, r.bodyBytes.length);
    }

    @Test
    public void rs01_statusLine() throws IOException {
        start();
        RawConnection c = connect();
        try {
            assertEquals("HTTP/1.1 200 OK", get(c, "/ok").statusLine);
        } finally {
            c.close();
        }
    }

    @Test
    public void rs02_rs03_noBodyStatuses() throws IOException {
        start();
        RawConnection c = connect();
        try {
            for (String p : new String[] {"/204", "/304"}) {
                RawResponse r = get(c, p);
                assertNull(r.header("content-length"));
                assertNull(r.header("transfer-encoding"));
                nextRequestWorks(c);
            }
        } finally {
            c.close();
        }
    }

    @Test
    public void rs04_smallBodyGetsContentLength() throws IOException {
        start();
        RawConnection c = connect();
        try {
            RawResponse r = get(c, "/100b");
            assertEquals("100", r.header("content-length"));
            assertFalse(r.chunked);
        } finally {
            c.close();
        }
    }

    @Test
    public void rs05_largeBodyIsChunked() throws IOException {
        start();
        RawConnection c = connect();
        try {
            RawResponse r = get(c, "/100k");
            assertTrue(r.chunked);
            assertEquals(100 * 1024, r.bodyBytes.length);
            assertNull(r.header("connection"));
            nextRequestWorks(c);
        } finally {
            c.close();
        }
    }

    @Test
    public void rs06_rs21_http10LargeBodyIsCloseDelimited() throws IOException {
        start();
        RawConnection c = connect();
        try {
            c.send("GET /100k HTTP/1.0\r\n\r\n");
            RawResponse r = c.read();
            assertTrue(r.statusLine.startsWith("HTTP/1.1 "));
            assertFalse(r.chunked);
            assertEquals("close", r.header("connection"));
            assertEquals(100 * 1024, r.bodyBytes.length);
        } finally {
            c.close();
        }
    }

    @Test
    public void rs07_headKnownLength() throws IOException {
        start();
        RawConnection c = connect();
        try {
            for (String p : new String[] {"/100b", "/declared100"}) {
                c.send("HEAD " + p + " HTTP/1.1\r\nHost: x\r\n\r\n");
                RawResponse r = c.read(true);
                assertEquals("100", r.header("content-length"));
                nextRequestWorks(c);
            }
        } finally {
            c.close();
        }
    }

    @Test
    public void rs08_headUnknownLength() throws IOException {
        start();
        RawConnection c = connect();
        try {
            c.send("HEAD /100k HTTP/1.1\r\nHost: x\r\n\r\n");
            RawResponse r = c.read(true);
            assertNull(r.header("content-length"));
            assertNull(r.header("transfer-encoding"));
            nextRequestWorks(c);
        } finally {
            c.close();
        }
    }

    @Test
    public void rs09_writingMoreThanDeclared() throws IOException {
        start();
        RawConnection c = connect();
        try {
            RawResponse r = get(c, "/over");
            assertEquals("01234", r.body);
            assertEquals("closed", c.awaitClose(3000));
        } finally {
            c.close();
        }
        assertTrue(String.valueOf(seen.get()), seen.get() instanceof ContentLengthExceededException);
    }

    @Test
    public void rs10_rs11_headerValidationAndRepeats() throws IOException {
        start();
        RawConnection c = connect();
        try {
            RawResponse r = get(c, "/headers");
            assertEquals(2, r.count("X"));
            assertTrue(r.headerLines.contains("X: 1"));
            assertTrue(r.headerLines.contains("X: 2"));
        } finally {
            c.close();
        }
        assertTrue(seen.get() instanceof IllegalArgumentException);
    }

    @Test
    public void rs12_dateHeader() throws IOException {
        start();
        RawConnection c = connect();
        try {
            String date = get(c, "/ok").header("date");
            assertTrue(date, date.matches("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT"));
        } finally {
            c.close();
        }
    }

    @Test
    public void rs13_rs15_handlerExceptions() throws IOException {
        start();
        RawConnection c = connect();
        try {
            assertEquals(500, get(c, "/runtime").status());
            nextRequestWorks(c);
            RawResponse r = get(c, "/404");
            assertEquals(404, r.status());
            assertEquals("nothing here\n", r.body);
        } finally {
            c.close();
        }
    }

    @Test
    public void rs14_failureAfterCommitTruncates() throws IOException {
        start();
        RawConnection c = connect();
        try {
            c.send("GET /late-failure HTTP/1.1\r\nHost: x\r\n\r\n");
            try {
                c.read();
                throw new AssertionError("a complete response was read");
            } catch (IOException expected) {
                // missing last chunk
            }
        } finally {
            c.close();
        }
    }

    @Test
    public void rs16_maxRequestsPerConnection() throws IOException {
        start(TinyHttpd.builder().maxRequestsPerConnection(3));
        RawConnection c = connect();
        try {
            assertNull(get(c, "/ok").header("connection"));
            assertNull(get(c, "/ok").header("connection"));
            assertEquals("close", get(c, "/ok").header("connection"));
            assertEquals("closed", c.awaitClose(3000));
        } finally {
            c.close();
        }
    }

    @Test
    public void rs17_writerThenStream() throws IOException {
        start();
        RawConnection c = connect();
        try {
            get(c, "/mix");
        } finally {
            c.close();
        }
        assertTrue(seen.get() instanceof IllegalStateException);
    }

    @Test
    public void rs18_writerDefaultsToUtf8() throws IOException {
        start();
        RawConnection c = connect();
        try {
            RawResponse r = get(c, "/hangul");
            assertEquals("한글", r.body);
            assertTrue(r.header("content-type"), r.header("content-type").contains("charset=UTF-8"));
        } finally {
            c.close();
        }
    }

    @Test
    public void rs19_headerAfterCommitIsIgnored() throws IOException {
        start();
        RawConnection c = connect();
        try {
            RawResponse r = get(c, "/late-header");
            assertNull(r.header("x-late"));
            assertEquals("xy", r.body);
        } finally {
            c.close();
        }
    }

    @Test
    public void rs20_smallResponseIsOneWrite() throws IOException {
        start();
        RawConnection c = connect();
        try {
            nextRequestWorks(c);
            long before = server.getStats().socketWrites;
            get(c, "/1k");
            assertEquals(1, server.getStats().socketWrites - before);
        } finally {
            c.close();
        }
    }

    @Test
    public void rs22_resetContent() throws IOException {
        start();
        RawConnection c = connect();
        try {
            RawResponse r = get(c, "/205");
            assertEquals("0", r.header("content-length"));
            nextRequestWorks(c);
        } finally {
            c.close();
        }
    }

    @Test
    public void rs23_serverInitiatedClosesAreAnnounced() throws IOException {
        start(TinyHttpd.builder().maxRequestBodyBytes(10).maxRequestsPerConnection(1));
        RawConnection c = connect();
        try {
            RawResponse r = get(c, "/close");
            assertEquals("close", r.header("connection"));
            assertEquals("closed", c.awaitClose(3000));
        } finally {
            c.close();
        }
        c = connect();
        try {
            c.send("POST /ok HTTP/1.1\r\nHost: x\r\nContent-Length: 11\r\n\r\nhello world");
            RawResponse r = c.read();
            assertEquals(413, r.status());
            assertEquals("close", r.header("connection"));
            assertEquals("closed", c.awaitClose(3000));
        } finally {
            c.close();
        }
        c = connect();
        try {
            RawResponse r = get(c, "/ok");
            assertEquals("close", r.header("connection"));
            assertEquals("closed", c.awaitClose(3000));
        } finally {
            c.close();
        }
    }

    @Test
    public void rs24_notModifiedIgnoresDeclaredLength() throws IOException {
        start();
        RawConnection c = connect();
        try {
            RawResponse r = get(c, "/304-declared");
            assertNull(r.header("content-length"));
            nextRequestWorks(c);
        } finally {
            c.close();
        }
    }

    /** Keeps the OutputStream import used for handlers written inline elsewhere. */
    static void write(OutputStream out, int n) throws IOException {
        out.write(new byte[n]);
    }
}
