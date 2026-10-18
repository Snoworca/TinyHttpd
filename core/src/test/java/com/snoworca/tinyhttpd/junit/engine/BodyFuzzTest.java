package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;

import org.junit.Test;

import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MultipartLimits;
import com.snoworca.tinyhttpd.http.MultipartReader;
import com.snoworca.tinyhttpd.http.Part;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.log.TinyLog;
import com.snoworca.tinyhttpd.testing.TestServers;

/**
 * P9-05 (SEC-T03): mutated chunked and multipart bodies must not cause unhandled exceptions, spinning workers,
 * hanging connections or heap growth. 1,000 cases each by default, {@code -Dfuzz.cases=10000} for the full run.
 */
public class BodyFuzzTest extends EngineTestBase {

    private static final String B = "fuzzBoundary";

    private final List<String> severe = Collections.synchronizedList(new ArrayList<String>());

    private void startServer() throws IOException {
        routes.put("/chunked", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                InputStream in = req.getInputStream();
                byte[] b = new byte[512];
                while (in.read(b) != -1) {
                    // consume
                }
                text(res, "ok");
            }
        });
        routes.put("/multipart", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                MultipartReader mr = req.readMultipart(MultipartLimits.of(1 << 20, 20).withMaxPartHeaderBytes(1024));
                for (Part p; (p = mr.next()) != null;) {
                    p.getSubmittedFileName();
                    InputStream in = p.getInputStream();
                    byte[] b = new byte[512];
                    while (in.read(b) != -1) {
                        // consume
                    }
                }
                text(res, "ok");
            }
        });
        start(TestServers.builder().lingerTimeout(200).headerTimeout(1000).bodyReadTimeout(1000).logger(new TinyLog() {

            @Override
            public boolean isLoggable(Level level) {
                return level.intValue() >= Level.SEVERE.intValue();
            }

            @Override
            public void log(Level level, String message, Throwable thrown) {
                severe.add(message + " " + thrown);
            }
        }));
    }

    private void fuzz(String base) throws Exception {
        int cases = Integer.getInteger("fuzz.cases", 1000);
        long seed = Long.getLong("fuzz.seed", 20261018L);
        Random random = new Random(seed);
        byte[] bytes = base.getBytes("ISO-8859-1");
        long heapBefore = BodyIoTest.usedHeap();
        for (int i = 0; i < cases; i++) {
            byte[] input = ParserFuzzTest.mutate(bytes, random);
            String outcome = ParserFuzzTest.send(port, input);
            assertTrue("case " + i + " seed " + seed + " hung: " + ParserFuzzTest.escape(input), !"hung".equals(outcome));
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (server.getStats().openConnections > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(server.getStats().toString(), 0, server.getStats().openConnections);
        assertEquals(severe.toString(), 0, severe.size());
        long growth = BodyIoTest.usedHeap() - heapBefore;
        assertTrue("heap growth " + growth, growth < 2 << 20);
    }

    @Test
    public void secT03_chunkedBodies() throws Exception {
        startServer();
        fuzz("POST /chunked HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5;ext=1\r\nhello\r\n10\r\n0123456789abcdef\r\n0\r\nTrailer: t\r\n\r\n");
    }

    @Test
    public void secT03_multipartBodies() throws Exception {
        startServer();
        String body = "--" + B + "\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nvalue\r\n--" + B
                + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"../x.txt\"\r\nContent-Type: text/plain\r\n\r\n"
                + "file content\r\n--" + B + "--\r\n";
        fuzz("POST /multipart HTTP/1.1\r\nHost: x\r\nContent-Type: multipart/form-data; boundary=" + B
                + "\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
    }
}
