package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.TruncatedBodyException;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

/** P1 request parsing and request body framing (RQ-nn, docs/next/p01-request-parsing.md). */
public class RequestParsingTest extends EngineTestBase {

    private final AtomicInteger calls = new AtomicInteger();

    private final AtomicReference<Object> seen = new AtomicReference<Object>();

    @Before
    public void routes() throws IOException {
        routes.put("*", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                calls.incrementAndGet();
                text(res, req.getMethod() + " " + req.getPath() + " " + req.getQueryString() + " "
                        + ProbeBody.read(req.getInputStream()));
            }
        });
    }

    private RawResponse exchange(String raw) throws IOException {
        if (server == null) {
            start();
        }
        RawConnection c = connect();
        try {
            c.send(raw);
            return c.read();
        } finally {
            c.close();
        }
    }

    /** Sends a request that must be rejected: checks status, Connection: close and that the server closes. */
    private void rejected(String raw, int status) throws IOException {
        if (server == null) {
            start();
        }
        RawConnection c = connect();
        try {
            c.send(raw);
            RawResponse r = c.read();
            assertEquals(r.statusLine, status, r.status());
            assertEquals("close", r.header("connection"));
            assertTrue(r.header("date") != null);
            assertEquals("closed", c.awaitClose(3000));
        } finally {
            c.close();
        }
    }

    @Test
    public void rq01_bareLfLineEndings() throws IOException {
        assertEquals("GET /a null 0", exchange("GET /a HTTP/1.1\nHost: x\n\n").body);
    }

    @Test
    public void rq02_bareCrInHeaderIsRejected() throws IOException {
        rejected("GET /a HTTP/1.1\r\nHost: x\rY: z\r\n\r\n", 400);
    }

    @Test
    public void rq03_oneEmptyLineBeforeRequestLine() throws IOException {
        assertEquals("GET /a null 0", exchange("\r\nGET /a HTTP/1.1\r\nHost: x\r\n\r\n").body);
    }

    @Test
    public void rq04_spaceBeforeColon() throws IOException {
        rejected("GET /a HTTP/1.1\r\nHost : x\r\n\r\n", 400);
    }

    @Test
    public void rq05_obsFold() throws IOException {
        rejected("GET /a HTTP/1.1\r\nHost: x\r\nX: a\r\n b\r\n\r\n", 400);
    }

    @Test
    public void rq06_missingHost() throws IOException {
        rejected("GET /a HTTP/1.1\r\n\r\n", 400);
    }

    @Test
    public void rq07_twoHosts() throws IOException {
        rejected("GET /a HTTP/1.1\r\nHost: x\r\nHost: y\r\n\r\n", 400);
    }

    @Test
    public void rq08_transferEncodingWithContentLength() throws IOException {
        rejected("POST /a HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n0\r\n\r\n", 400);
        assertEquals(0, calls.get());
    }

    @Test
    public void rq09_unsupportedTransferCodings() throws IOException {
        rejected("POST /a HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: gzip\r\n\r\n", 400);
        rejected("POST /a HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: gzip, chunked\r\n\r\n", 501);
    }

    @Test
    public void rq10_transferEncodingInHttp10() throws IOException {
        rejected("POST /a HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n", 400);
    }

    @Test
    public void rq11_hugeContentLength() throws IOException {
        rejected("POST /a HTTP/1.1\r\nHost: x\r\nContent-Length: 99999999999999999999\r\n\r\n", 400);
    }

    @Test
    public void rq12_repeatedContentLength() throws IOException {
        assertEquals("POST /a null 5", exchange("POST /a HTTP/1.1\r\nHost: x\r\nContent-Length: 5, 5\r\n\r\nhello").body);
        rejected("POST /a HTTP/1.1\r\nHost: x\r\nContent-Length: 5, 6\r\n\r\nhello", 400);
    }

    @Test
    public void rq13_badChunkSizes() throws IOException {
        rejected("POST /a HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n", 400);
        rejected("POST /a HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n10000000000000000\r\n", 400);
    }

    @Test
    public void rq14_longChunkExtension() throws IOException {
        rejected("POST /a HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n5;" + repeat('e', 2048)
                + "\r\nhello\r\n0\r\n\r\n", 400);
    }

    @Test
    public void rq15_largeTrailer() throws IOException {
        StringBuilder trailer = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            trailer.append("X-T").append(i).append(": ").append(repeat('t', 4000)).append("\r\n");
        }
        rejected("POST /a HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n" + trailer
                + "\r\n", 400);
    }

    @Test
    public void rq16_truncatedBody() throws Exception {
        routes.put("/t", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                try {
                    ProbeBody.read(req.getInputStream());
                    seen.set("no exception");
                } catch (IOException e) {
                    seen.set(e);
                    throw e;
                }
            }
        });
        start();
        RawConnection c = connect();
        try {
            c.send("POST /t HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\nhello");
            c.shutdownOutput();
            String state = c.awaitClose(3000);
            assertTrue(state, !"open".equals(state));
        } finally {
            c.close();
        }
        assertTrue(String.valueOf(seen.get()), seen.get() instanceof TruncatedBodyException);
    }

    @Test
    public void rq17_unreadLargeBodyClosesAfterResponse() throws IOException {
        routes.put("/ignore", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, "ignored");
            }
        });
        start();
        RawConnection c = connect();
        try {
            c.send("POST /ignore HTTP/1.1\r\nHost: x\r\nContent-Length: 1048576\r\n\r\n");
            c.send(new byte[256 * 1024]);
            RawResponse r = c.read();
            assertEquals("ignored", r.body);
            assertEquals("close", r.header("connection"));
            String state = c.awaitClose(5000);
            assertTrue(state, !"open".equals(state));
        } finally {
            c.close();
        }
    }

    @Test
    public void rq18_unknownExpectation() throws IOException {
        rejected("POST /a HTTP/1.1\r\nHost: x\r\nExpect: foo\r\nContent-Length: 1\r\n\r\nx", 417);
    }

    @Test
    public void rq19_versions() throws IOException {
        rejected("GET /a HTTP/2.0\r\nHost: x\r\n\r\n", 505);
        RawResponse r = exchange("GET /a HTTP/1.2\r\nHost: x\r\n\r\n");
        assertEquals(200, r.status());
        rejected("GET /a HTTP/1.1x\r\nHost: x\r\n\r\n", 400);
    }

    @Test
    public void rq20_requestLineTooLong() throws IOException {
        String target = "/" + repeat('a', 8190 - "GET / HTTP/1.1".length()); // line is exactly 8190 bytes
        assertEquals(200, exchange("GET " + target + " HTTP/1.1\r\nHost: x\r\n\r\n").status());
        rejected("GET " + target + "a HTTP/1.1\r\nHost: x\r\n\r\n", 414);
    }

    @Test
    public void rq21_requestSentOneByteAtATime() throws IOException {
        start();
        RawConnection c = connect();
        try {
            c.sendSlowly("GET /slow HTTP/1.1\r\nHost: x\r\n\r\n".getBytes("US-ASCII"), 1, 2);
            assertEquals("GET /slow null 0", c.read().body);
        } finally {
            c.close();
        }
    }

    @Test
    public void rq22_absoluteForm() throws IOException {
        routes.put("/p", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, req.getPath() + "|" + req.getQueryString() + "|" + req.getServerName() + "|"
                        + req.getServerPort());
            }
        });
        assertEquals("/p|q=1|h|" + (tls ? 443 : 80), exchange("GET http://h/p?q=1 HTTP/1.1\r\nHost: other:81\r\n\r\n").body);
    }

    @Test
    public void rq23_asteriskForm() throws IOException {
        RawResponse r = exchange("OPTIONS * HTTP/1.1\r\nHost: x\r\n\r\n");
        assertEquals("OPTIONS * null 0", r.body);
        rejected("GET * HTTP/1.1\r\nHost: x\r\n\r\n", 400);
    }

    @Test
    public void rq24_repeatedHeadersKeepOrder() throws IOException {
        routes.put("/h", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                List<String> v = req.getHeaders("x-a");
                text(res, v.toString());
            }
        });
        assertEquals("[1, 2, 3]", exchange("GET /h HTTP/1.1\r\nHost: x\r\nX-A: 1\r\nx-a: 2\r\nX-a: 3\r\n\r\n").body);
    }

    @Test
    public void rq25_remoteAddrIsNotAHeader() throws IOException {
        routes.put("/r", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, req.getRemoteAddr());
            }
        });
        assertEquals("127.0.0.1", exchange("GET /r HTTP/1.1\r\nHost: x\r\nremote-addr: 1.2.3.4\r\n\r\n").body);
    }

    @Test
    public void rq26_tooManyOrTooLongHeaders() throws IOException {
        StringBuilder many = new StringBuilder("GET /a HTTP/1.1\r\nHost: x\r\n");
        for (int i = 0; i < 100; i++) {
            many.append("X-").append(i).append(": v\r\n");
        }
        rejected(many + "\r\n", 431);
        rejected("GET /a HTTP/1.1\r\nHost: x\r\nX-Big: " + repeat('a', 9 * 1024) + "\r\n\r\n", 431);
    }

    @Test
    public void rq27_headerTotalLimit() throws IOException {
        StringBuilder sb = new StringBuilder("GET /a HTTP/1.1\r\nHost: x\r\n");
        for (int i = 0; i < 3; i++) {
            sb.append("X-").append(i).append(": ").append(repeat('a', 7 * 1024)).append("\r\n");
        }
        rejected(sb + "\r\n", 431);
    }

    @Test
    public void rq28_chunkedBodyOverGlobalLimit() throws IOException {
        routes.put("/c", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                try {
                    ProbeBody.read(req.getInputStream());
                    seen.set("read all");
                } catch (IOException e) {
                    seen.set(e);
                    throw e;
                }
            }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxRequestBodyBytes(1024));
        RawConnection c = connect();
        try {
            c.send("POST /c HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n800\r\n" + repeat('b', 2048)
                    + "\r\n0\r\n\r\n");
            RawResponse r = c.read();
            assertEquals(413, r.status());
            assertEquals("close", r.header("connection"));
        } finally {
            c.close();
        }
        assertTrue(String.valueOf(seen.get()), seen.get() instanceof IOException);
        assertFalse(server.getStats().openConnections > 1);
    }

    /** Reads a stream to its end and counts the bytes. */
    static final class ProbeBody {

        static long read(InputStream in) throws IOException {
            long n = 0;
            byte[] b = new byte[4096];
            int r;
            while ((r = in.read(b)) != -1) {
                n += r;
            }
            return n;
        }
    }
}
