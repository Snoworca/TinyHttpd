package com.snoworca.tinyhttpd.junit.keepalive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;
import com.snoworca.tinyhttpd.testing.ThreadCpu;

/**
 * Keep-alive behavior the server must have. Each test failed on the inherited NanoHttpd code; the IDs (KA-nn) are
 * referenced from docs/research/keep-alive.md. They now run against the new engine.
 */
public class KeepAliveDefectTest {

    @Rule
    public Timeout timeout = Timeout.seconds(30);

    private ProbeServer server;

    private int port;

    @Before
    public void startServer() throws IOException {
        server = new ProbeServer();
        server.start();
        port = server.port();
    }

    @After
    public void stopServer() {
        server.stop();
    }

    /** KA-01: a body the handler never reads must not be parsed as the next request (upstream #314, #217). */
    @Test
    public void ka01_unreadBodyIsDrainedBeforeNextRequest() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("POST /ignore-body HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\nAAAAAAAAAA");
            assertEquals(200, c.read().status());
            c.send("GET /second HTTP/1.1\r\nHost: x\r\n\r\n");
            RawResponse second = c.read();
            assertEquals(200, second.status());
            assertEquals("second", second.body);
        } finally {
            c.close();
        }
    }

    /** KA-02: a HEAD response must not carry a body (RFC 7230 §3.3), or the next response is corrupted. */
    @Test
    public void ka02_headResponseHasNoBody() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("HEAD /hello HTTP/1.1\r\nHost: x\r\n\r\n");
            RawResponse head = c.read(true);
            assertEquals(200, head.status());
            c.send("GET /second HTTP/1.1\r\nHost: x\r\n\r\n");
            RawResponse second = c.read();
            assertEquals("HTTP/1.1 200 OK", second.statusLine);
            assertEquals("second", second.body);
        } finally {
            c.close();
        }
    }

    /** KA-03: HEAD of a chunked resource must neither send the body nor a negative Content-Length. */
    @Test
    public void ka03_headOfChunkedResourceHasNoBodyAndNoNegativeLength() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("HEAD /chunked HTTP/1.1\r\nHost: x\r\n\r\n");
            RawResponse head = c.read(true);
            String contentLength = head.header("content-length");
            assertTrue("Content-Length: " + contentLength, contentLength == null || Long.parseLong(contentLength) >= 0);
            c.send("GET /second HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("HTTP/1.1 200 OK", c.read().statusLine);
        } finally {
            c.close();
        }
    }

    /**
     * KA-04: a chunked request body must be decoded and consumed (or rejected with the connection closed), not
     * left on the wire (upstream #211, #617).
     */
    @Test
    public void ka04_chunkedRequestBodyIsConsumed() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("POST /parse HTTP/1.1\r\nHost: x\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n");
            RawResponse first = c.read();
            if ("close".equalsIgnoreCase(first.header("connection"))) {
                assertTrue("rejecting is fine only with a 4xx status", first.status() >= 400 && first.status() < 500);
                return;
            }
            assertEquals("parsed 5", first.body);
            c.send("GET /second HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("second", c.read().body);
        } finally {
            c.close();
        }
    }

    /**
     * KA-05: when the response stream ends before the declared Content-Length, the only way to tell the client is
     * to close the connection. Keeping it open leaves the client waiting for the missing bytes.
     */
    @Test
    public void ka05_shortFixedLengthBodyClosesConnection() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("GET /short HTTP/1.1\r\nHost: x\r\n\r\n");
            RawResponse r = c.read(true);
            assertEquals("10", r.header("content-length"));
            assertEquals("12345", new String(c.readExactly(5), RawConnection.ASCII));
            assertEquals("closed", c.awaitClose(2000));
        } finally {
            c.close();
        }
    }

    /** KA-06: request headers over the limit get 431 and the worker exits (upstream #621, #362). */
    @Test
    public void ka06_oversizedHeaderIsRejected() throws Exception {
        char[] filler = new char[16 * 1024];
        Arrays.fill(filler, 'a');
        RawConnection c = new RawConnection(port);
        RawResponse r;
        try {
            c.send("GET /hello HTTP/1.1\r\nHost: x\r\nX-Big: " + new String(filler) + "\r\n\r\n");
            r = c.read();
        } finally {
            c.close();
        }
        int alive = server.awaitWorkersIdle(2000);
        String cpu = workerCpuMillis();
        assertEquals("worker threads still alive 2s after client closed (cpu ms: " + cpu + ")", 0, alive);
        assertEquals(431, r.status());
    }

    /** KA-07: a client that sends a partial header and disconnects must not leave a spinning worker (upstream #529). */
    @Test
    public void ka07_partialHeaderThenDisconnectDoesNotSpin() throws Exception {
        RawConnection c = new RawConnection(port);
        c.send("GET /hello HTTP/1.1\r\n");
        Thread.sleep(200);
        c.close();
        int alive = server.awaitWorkersIdle(2000);
        assertEquals("worker threads still alive 2s after client closed (cpu ms: " + workerCpuMillis() + ")", 0, alive);
    }

    /** KA-08: when the server closes after an error response, the response must say so. */
    @Test
    public void ka08_errorResponseAnnouncesClose() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("GET\r\nHost: x\r\n\r\n");
            RawResponse r = c.read();
            assertEquals(400, r.status());
            assertEquals("close", r.header("connection"));
        } finally {
            c.close();
        }
    }

    /** KA-09: HTTP/1.0 clients that ask for keep-alive explicitly may keep the connection (RFC 7230 §6.3). */
    @Test
    public void ka09_http10KeepAliveIsHonored() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("GET /hello HTTP/1.0\r\nConnection: keep-alive\r\n\r\n");
            RawResponse r = c.read();
            assertEquals("hello", r.body);
            assertEquals("keep-alive", r.header("connection"));
            c.send("GET /second HTTP/1.0\r\nConnection: keep-alive\r\n\r\n");
            assertEquals("second", c.read().body);
        } finally {
            c.close();
        }
    }

    /**
     * KA-10: the request body stream handed to the handler must end at the end of the body. Today it is the raw
     * connection stream, so reading to EOF blocks until the socket read timeout.
     */
    @Test
    public void ka10_requestBodyStreamEndsAtContentLength() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            long start = System.currentTimeMillis();
            c.send("POST /read-all HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello");
            RawResponse r = readWithLongTimeout(c);
            long elapsed = System.currentTimeMillis() - start;
            assertEquals("read 5", r.body);
            assertTrue("took " + elapsed + " ms", elapsed < 1000);
        } finally {
            c.close();
        }
    }

    /** KA-11: an unparsable Content-Length is a 400, not a dropped connection without a response. */
    @Test
    public void ka11_invalidContentLengthIsBadRequest() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("POST /parse HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n");
            assertEquals(400, c.read().status());
        } finally {
            c.close();
        }
    }

    /** KA-12: idle keep-alive connections must not cost one thread each without limit (upstream #86). */
    @Test
    public void ka12_idleConnectionsDoNotEachHoldAThread() throws Exception {
        List<RawConnection> idle = new ArrayList<RawConnection>();
        try {
            for (int i = 0; i < 100; i++) {
                RawConnection c = new RawConnection(port);
                c.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
                c.read();
                idle.add(c);
            }
            int threads = server.liveWorkerThreads();
            assertTrue(threads + " worker threads for 100 idle connections", threads <= 64);
        } finally {
            for (RawConnection c : idle) {
                c.close();
            }
        }
    }

    /**
     * KA-13: without Content-Length or Transfer-Encoding a request has no body (RFC 7230 §3.3.3). Today
     * parseBody() treats the bytes already buffered, i.e. the next pipelined request, as the body.
     */
    @Test
    public void ka13_requestWithoutLengthHasEmptyBody() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("GET /parse HTTP/1.1\r\nHost: x\r\n\r\nGET /second HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("parsed 0", c.read().body);
            assertEquals("second", c.read().body);
        } finally {
            c.close();
        }
    }

    /**
     * KA-14: closing with unread request bytes in the receive buffer makes the kernel send RST, which can destroy
     * the response the client has not read yet. The server must close gracefully (lingering close).
     */
    @Test
    public void ka14_closeWithUnreadInputDoesNotResetResponse() throws IOException {
        byte[] head = "GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes(RawConnection.ASCII);
        byte[] junk = new byte[256 * 1024];
        Arrays.fill(junk, (byte) 'x');
        RawConnection c = new RawConnection(port);
        try {
            c.send(head);
            try {
                c.send(junk);
            } catch (IOException ignored) {
                // The server may already have closed; what matters is whether the response survives.
            }
            sleepQuietly(300);
            RawResponse r = c.read();
            assertEquals("hello", r.body);
        } finally {
            c.close();
        }
    }

    /** KA-15: a client that sends Expect: 100-continue waits for 100 Continue before sending the body (RFC 7231 §5.1.1). */
    @Test
    public void ka15_expectContinueGetsInterimResponse() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("POST /parse HTTP/1.1\r\nHost: x\r\nContent-Type: text/plain\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n");
            RawResponse interim = c.read(true);
            assertEquals(100, interim.status());
            c.send("hello");
            assertEquals("parsed 5", c.read().body);
        } finally {
            c.close();
        }
    }

    /** KA-16: per-request state must not leak into the next request on the same connection (upstream #311, #596). */
    @Test
    public void ka16_queryStringDoesNotLeakIntoNextRequest() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("GET /query?secret=1 HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("query=secret=1", c.read().body);
            c.send("GET /query HTTP/1.1\r\nHost: x\r\n\r\n");
            String body = c.read().body;
            assertTrue(body, "query=null".equals(body) || "query=".equals(body));
        } finally {
            c.close();
        }
    }

    private static RawResponse readWithLongTimeout(RawConnection c) throws IOException {
        c.setReadTimeout(10000);
        return c.read();
    }

    private String workerCpuMillis() {
        return ThreadCpu.millis(server.workerThreads);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
