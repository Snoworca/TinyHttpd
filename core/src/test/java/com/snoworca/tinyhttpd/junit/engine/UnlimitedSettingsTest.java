package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MultipartLimits;
import com.snoworca.tinyhttpd.http.MultipartReader;
import com.snoworca.tinyhttpd.http.Part;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;
import com.snoworca.tinyhttpd.testing.TestServers;

/** {@link TinyHttpd#UNLIMITED} and {@link TinyHttpd#UNLIMITED_LONG} in every limit: no arithmetic overflows. */
public class UnlimitedSettingsTest extends EngineTestBase {

    private static final int U = TinyHttpd.UNLIMITED;

    private void startUnlimited() throws IOException {
        start(TestServers.builder().headerTimeout(U).bodyReadTimeout(U).writeTimeout(U).idleTimeout(U)
                .idleTimeoutUnderLoad(U).queueWaitTimeout(U).lingerTimeout(U).workerKeepAlive(U).shutdownGrace(U)
                .maxWorkers(U).maxConnections(U).maxConnectionsPerAddress(U).maxRequestLineBytes(U)
                .maxHeaderBytes(U).maxHeaderCount(U).maxChunkSizeLineBytes(U).maxTrailerBytes(U)
                .maxRequestsPerConnection(U).maxEventStreams(U).eventQueueEvents(U).eventQueueBytes(U)
                .maxUpgradedConnections(U).maxRequestBodyBytes(TinyHttpd.UNLIMITED_LONG)
                .maxDrainBytes(TinyHttpd.UNLIMITED_LONG));
    }

    /** A request line or chunk-size line split across reads (line limit check overflowed to 414/400). */
    @Test
    public void linesSplitAcrossReads() throws Exception {
        routes.put("/echo", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, Integer.toString(BodyIoTest.readAll(req.getInputStream()).length));
            }
        });
        startUnlimited();
        RawConnection c = connect();
        try {
            c.send("GET /ec");
            Thread.sleep(200);
            c.send("ho HTTP/1.0\r\nConnection: keep-alive\r\n\r\n");
            RawResponse r = c.read();
            assertEquals(200, r.status());
            assertEquals("timeout=" + U / 1000, r.header("Keep-Alive"));
            c.send("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n");
            Thread.sleep(200);
            c.send("5\r\nhello\r\n0\r\n\r\n");
            r = c.read();
            assertEquals(200, r.status());
            assertEquals("5", r.body);
        } finally {
            c.close();
        }
    }

    /** An unread body is drained before the next request (the drain buffer size overflowed). */
    @Test
    public void unreadBodyIsDrained() throws Exception {
        routes.put("/ignore", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, "ok");
            }
        });
        startUnlimited();
        RawConnection c = connect();
        try {
            for (int i = 0; i < 2; i++) {
                c.send("POST /ignore HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\n0123456789");
                RawResponse r = c.read();
                assertEquals(200, r.status());
                assertEquals(null, r.header("Connection"));
            }
        } finally {
            c.close();
        }
    }

    /** {@code Part.readString(UNLIMITED)} (its buffer size overflowed). */
    @Test
    public void multipartUnlimited() throws Exception {
        routes.put("/upload", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                MultipartReader mr = req.readMultipart(MultipartLimits.of(TinyHttpd.UNLIMITED_LONG, U)
                        .withMaxPartHeaderBytes(U));
                Part p = mr.next();
                text(res, p.readString(U));
            }
        });
        startUnlimited();
        String body = "--B\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nvalue\r\n--B--\r\n";
        RawConnection c = connect();
        try {
            c.send("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: multipart/form-data; boundary=B\r\n"
                    + "Content-Length: " + body.length() + "\r\n\r\n" + body);
            RawResponse r = c.read();
            assertEquals(200, r.status());
            assertEquals("value", r.body);
        } finally {
            c.close();
        }
    }

    /** {@code stop(UNLIMITED_LONG)} waits for a request in progress (the grace deadline overflowed to the past). */
    @Test
    public void unlimitedStopGraceWaits() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        routes.put("/slow", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                entered.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                text(res, "done");
            }
        });
        startUnlimited();
        RawConnection c = connect();
        try {
            c.send("GET /slow HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread stopper = new Thread(new Runnable() {

                @Override
                public void run() {
                    server.stop(TinyHttpd.UNLIMITED_LONG);
                }
            });
            stopper.start();
            stopper.join(500);
            assertTrue("stop returned while a request was in progress", stopper.isAlive());
            release.countDown();
            assertEquals("done", c.read().body);
            c.close(); // an unlimited lingerTimeout waits for the client's EOF
            stopper.join(5000);
            assertFalse(stopper.isAlive());
        } finally {
            release.countDown();
            c.close();
        }
    }
}
