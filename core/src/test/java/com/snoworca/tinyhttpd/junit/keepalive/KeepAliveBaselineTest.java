package com.snoworca.tinyhttpd.junit.keepalive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Keep-alive behavior the inherited code already gets right. These guard the rewrite against regressions; the IDs
 * (KB-nn) are referenced from docs/research/keep-alive.md.
 */
public class KeepAliveBaselineTest {

    /** Idle timeout for these tests; KB-04/05 wait for it (P3-03 shortens it so the tests run fast). */
    private static final int IDLE = 500;

    @Rule
    public Timeout timeout = Timeout.seconds(30);

    private ProbeServer server;

    private int port;

    private OkHttpClient client;

    @Before
    public void setUp() throws IOException {
        server = new ProbeServer(TinyHttpd.builder().idleTimeout(IDLE));
        server.start();
        port = server.port();
        client = new OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build();
    }

    @After
    public void tearDown() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        server.stop();
    }

    /** KB-01: sequential OkHttp requests reuse one connection. */
    @Test
    public void kb01_okHttpReusesConnection() throws IOException {
        for (int i = 0; i < 20; i++) {
            assertEquals("hello", get("/hello"));
        }
        assertEquals(1, server.stats().acceptedConnections);
    }

    /** KB-02: two GET requests written in one packet both get answered in order. */
    @Test
    public void kb02_pipelinedGetsAreAnsweredInOrder() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\nGET /second HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("hello", c.read().body);
            assertEquals("second", c.read().body);
        } finally {
            c.close();
        }
    }

    /** KB-03: Connection: close from the client is answered with close and the server closes. */
    @Test
    public void kb03_clientConnectionCloseIsHonored() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            RawResponse r = c.read();
            assertEquals("close", r.header("connection"));
            assertEquals("closed", c.awaitClose(2000));
        } finally {
            c.close();
        }
    }

    /** KB-04: an idle connection is closed by the server after the socket read timeout. */
    @Test
    public void kb04_idleConnectionClosedAfterReadTimeout() throws IOException {
        RawConnection c = new RawConnection(port);
        try {
            c.send("GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
            c.read();
            long start = System.currentTimeMillis();
            assertEquals("closed", c.awaitClose(IDLE + 3000));
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("KB-04 idle close after " + elapsed + " ms");
            assertTrue("closed after " + elapsed + " ms", elapsed >= IDLE - 100);
        } finally {
            c.close();
        }
    }

    /**
     * KB-05: after the server closes an idle pooled connection, OkHttp's next non-idempotent request still
     * succeeds on a new connection (OkHttp checks pooled sockets before reusing them for non-GET requests).
     */
    @Test
    public void kb05_okHttpRecoversFromServerIdleClose() throws Exception {
        assertEquals("parsed 3", post("/parse", "abc"));
        Thread.sleep(IDLE + 1000);
        assertEquals("parsed 3", post("/parse", "abc"));
        assertEquals(2, server.stats().acceptedConnections);
    }

    /** KB-06: a body read to its end leaves the connection usable. */
    @Test
    public void kb06_parsedBodyKeepsConnectionUsable() throws IOException {
        for (int i = 0; i < 20; i++) {
            assertEquals("parsed 3", post("/parse", "abc"));
        }
        assertEquals(1, server.stats().acceptedConnections);
    }

    private String get(String path) throws IOException {
        Response r = client.newCall(new Request.Builder().url("http://127.0.0.1:" + port + path).build()).execute();
        try {
            return r.body().string();
        } finally {
            r.close();
        }
    }

    private String post(String path, String body) throws IOException {
        RequestBody rb = RequestBody.create(body, MediaType.get("text/plain"));
        Response r = client.newCall(new Request.Builder().url("http://127.0.0.1:" + port + path).post(rb).build()).execute();
        try {
            return r.body().string();
        } finally {
            r.close();
        }
    }
}
