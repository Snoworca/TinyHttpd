package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.FileResponses;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MalformedBodyException;
import com.snoworca.tinyhttpd.http.MultipartLimits;
import com.snoworca.tinyhttpd.http.MultipartReader;
import com.snoworca.tinyhttpd.http.Part;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.TruncatedBodyException;
import com.snoworca.tinyhttpd.http.filter.GzipFilter;
import com.snoworca.tinyhttpd.log.TinyLog;
import com.snoworca.tinyhttpd.testing.Clients;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

import okhttp3.OkHttpClient;

/** P5 uploads, downloads and compression (IO-nn, docs/next/p05-body-io.md). */
public class BodyIoTest extends EngineTestBase {

    private static final String B = "----tinyBoundary7MA4YWxk";

    private static File file10m;

    private static byte[] content10m;

    private final AtomicReference<Object> seen = new AtomicReference<Object>();

    @BeforeClass
    public static void createFile() throws IOException {
        content10m = new byte[10 << 20];
        new Random(1).nextBytes(content10m);
        file10m = File.createTempFile("tinyhttpd-io", ".bin");
        FileOutputStream out = new FileOutputStream(file10m);
        out.write(content10m);
        out.close();
    }

    @AfterClass
    public static void deleteFile() {
        file10m.delete();
    }

    // ------------------------------------------------------------------ multipart

    private static String part(String name, String fileName, String content) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (fileName != null ? "; filename=\"" + fileName + "\"" : "") + "\r\n"
                + (fileName != null ? "Content-Type: application/octet-stream\r\n" : "") + "\r\n" + content + "\r\n";
    }

    private static String multipart(String... parts) {
        StringBuilder sb = new StringBuilder("preamble ignored\r\n");
        for (String p : parts) {
            sb.append(p);
        }
        return sb.append("--").append(B).append("--\r\nepilogue").toString();
    }

    /** Lists parts as name|filename|size|content-prefix. */
    private void multipartEcho(final MultipartLimits limits, final boolean skipContent) {
        routes.put("/upload", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                StringBuilder sb = new StringBuilder();
                try {
                    MultipartReader mr = req.readMultipart(limits);
                    for (Part p; (p = mr.next()) != null;) {
                        sb.append(p.getName()).append('|').append(p.getSubmittedFileName()).append('|');
                        if (!skipContent) {
                            byte[] data = readAll(p.getInputStream());
                            sb.append(data.length).append('|').append(new String(data, 0, Math.min(data.length, 20),
                                    "UTF-8"));
                        }
                        sb.append('\n');
                    }
                } catch (IOException e) {
                    seen.set(e);
                    throw e;
                }
                text(res, sb.toString());
            }
        });
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[777];
        int n;
        while ((n = in.read(b)) != -1) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    private RawResponse post(String body, String extraHeaders) throws IOException {
        RawConnection c = connect();
        try {
            byte[] bytes = body.getBytes("UTF-8");
            c.send("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: multipart/form-data; boundary=" + B + "\r\n"
                    + extraHeaders + "Content-Length: " + bytes.length + "\r\n\r\n");
            c.send(bytes);
            return c.read();
        } finally {
            c.close();
        }
    }

    @Test
    public void io01_filesAndFields() throws IOException {
        multipartEcho(MultipartLimits.of(1 << 20, 10), false);
        start();
        RawResponse r = post(multipart(part("f1", "a.txt", "AAAA"), part("x", null, "1"), part("f2", "b.bin", "BB"),
                part("y", null, "two")), "");
        assertEquals("f1|a.txt|4|AAAA\nx|null|1|1\nf2|b.bin|2|BB\ny|null|3|two\n", r.body);
    }

    @Test
    public void io02_io03_boundaryEdges() throws IOException {
        multipartEcho(MultipartLimits.of(1 << 20, 10).withBufferBytes(1024), false);
        start();
        for (int pad = 950; pad < 1100; pad += 7) {
            String content = repeat('p', pad) + "\r\n--" + B.substring(0, 10) + "x";
            RawResponse r = post(multipart(part("a", "a", content), part("b", null, "z")), "");
            assertEquals("a|a|" + content.length() + "|pppppppppppppppppppp\nb|null|1|z\n", r.body);
        }
    }

    @Test
    public void io04_missingClosingBoundary() throws IOException {
        multipartEcho(MultipartLimits.of(1 << 20, 10), false);
        start();
        RawResponse r = post(part("a", null, "x") + "--" + B + "\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\nyy", "");
        assertEquals(400, r.status());
        assertTrue(String.valueOf(seen.get()), seen.get() instanceof MalformedBodyException);
    }

    @Test
    public void io05_tooManyParts() throws IOException {
        multipartEcho(MultipartLimits.of(1 << 20, 2), false);
        start();
        RawResponse r = post(multipart(part("a", null, "1"), part("b", null, "2"), part("c", null, "3")), "");
        assertEquals(413, r.status());
        assertEquals("close", r.header("connection"));
    }

    @Test
    public void io06_partTooLarge() throws IOException {
        multipartEcho(MultipartLimits.of(1 << 20, 10).withMaxPartBytes(10), false);
        start();
        RawResponse r = post(multipart(part("a", null, repeat('z', 11))), "");
        assertEquals(413, r.status());
        assertEquals("close", r.header("connection"));
    }

    @Test
    public void io07_unreadPartIsSkipped() throws IOException {
        multipartEcho(MultipartLimits.of(1 << 20, 10), true);
        start();
        assertEquals("a|x|\nb|null|\n", post(multipart(part("a", "x", repeat('q', 50000)), part("b", null, "2")), "").body);
    }

    @Test
    public void io08_chunkedMultipart() throws IOException {
        multipartEcho(MultipartLimits.of(1 << 20, 10), false);
        start();
        byte[] body = multipart(part("f1", "a.txt", "AAAA"), part("x", null, "1")).getBytes("UTF-8");
        RawConnection c = connect();
        try {
            c.send("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: multipart/form-data; boundary=" + B
                    + "\r\nTransfer-Encoding: chunked\r\n\r\n");
            for (int off = 0; off < body.length; off += 13) {
                int n = Math.min(13, body.length - off);
                c.send(Integer.toHexString(n) + "\r\n");
                c.send(java.util.Arrays.copyOfRange(body, off, off + n));
                c.send("\r\n");
            }
            c.send("0\r\n\r\n");
            assertEquals("f1|a.txt|4|AAAA\nx|null|1|1\n", c.read().body);
        } finally {
            c.close();
        }
    }

    @Test
    public void io09_hugeUploadUsesConstantMemory() throws Exception {
        final long size = Long.getLong("io.uploadBytes", 4L << 30);
        final AtomicLong peak = new AtomicLong();
        routes.put("/upload", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                MultipartReader mr = req.readMultipart(MultipartLimits.of(Long.MAX_VALUE, 10));
                Part p = mr.next();
                InputStream in = p.getInputStream();
                byte[] b = new byte[64 * 1024];
                long total = 0;
                long nextCheck = size / 4;
                int n;
                while ((n = in.read(b)) != -1) {
                    total += n;
                    if (total >= nextCheck) {
                        peak.set(Math.max(peak.get(), usedHeap()));
                        nextCheck += size / 4;
                    }
                }
                text(res, "stored " + total);
            }
        });
        start();
        RawConnection c = connect();
        try {
            String head = "--" + B + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"big\"\r\n\r\n";
            String tail = "\r\n--" + B + "--\r\n";
            c.send("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: multipart/form-data; boundary=" + B
                    + "\r\nContent-Length: " + (head.length() + size + tail.length()) + "\r\n\r\n" + head);
            byte[] block = new byte[64 * 1024];
            java.util.Arrays.fill(block, (byte) 'u');
            long baseline = usedHeap();
            OutputStream out = c.socket().getOutputStream();
            for (long left = size; left > 0; left -= block.length) {
                out.write(block, 0, (int) Math.min(block.length, left));
            }
            c.send(tail);
            c.setReadTimeout(60000);
            assertEquals("stored " + size, c.read().body);
            long growth = peak.get() - baseline;
            System.out.println("IO-09 upload " + (size >> 20) + " MB heap growth " + growth / 1024 + " KB");
            assertTrue("heap growth " + growth, growth <= 1 << 20);
        } finally {
            c.close();
        }
    }

    static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    @Test
    public void io10_io11_fileNames() throws IOException {
        multipartEcho(MultipartLimits.of(1 << 20, 10), true);
        start();
        String body = multipart(part("a", "../../etc/passwd", "1"), part("b", "C:\\a\\b.txt", "2"),
                part("c", "한.txt", "3"), part("d", "a%22b.txt", "4"))
                + "";
        body = body.replace("--" + B + "--", "--" + B + "\r\nContent-Disposition: form-data; name=\"e\"; "
                + "filename=\"x.txt\"; filename*=UTF-8''%ED%95%9C.txt\r\n\r\n5\r\n--" + B + "--");
        assertEquals("a|passwd|\nb|b.txt|\nc|한.txt|\nd|a\"b.txt|\ne|한.txt|\n", post(body, "").body);
    }

    // ------------------------------------------------------------------ form

    @Test
    public void io12_io13_readForm() throws IOException {
        routes.put("/form", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                Map<String, List<String>> form = req.readForm(1024);
                text(res, form.toString());
            }
        });
        routes.put("/mixed", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                req.getInputStream();
                try {
                    req.readForm(1024);
                } catch (IllegalStateException e) {
                    seen.set(e);
                }
            }
        });
        start();
        RawConnection c = connect();
        try {
            c.send("POST /form HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n"
                    + "Content-Length: 14\r\n\r\na=1+2&b=%ED%95");
            assertEquals("{a=[1 2], b=[\uFFFD]}", c.read().body);
            String big = "a=" + repeat('x', 2046);
            c.send("POST /form HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n"
                    + "Content-Length: " + big.length() + "\r\n\r\n" + big);
            assertEquals(413, c.read().status());
        } finally {
            c.close();
        }
        c = connect();
        try {
            c.send("POST /mixed HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n");
            c.read();
        } finally {
            c.close();
        }
        assertTrue(seen.get() instanceof IllegalStateException);
    }

    // ------------------------------------------------------------------ downloads

    private void fileRoutes() {
        routes.put("/file", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.sendFile(file10m, 0, file10m.length());
            }
        });
        routes.put("/channel", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                FileChannel ch = new RandomAccessFile(file10m, "r").getChannel();
                try {
                    res.sendFile(ch, 0, ch.size());
                } finally {
                    ch.close();
                }
            }
        });
        routes.put("/serve", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                FileResponses.serve(req, res, file10m, null);
            }
        });
    }

    @Test
    public void io14_io15_sendFile() throws IOException {
        fileRoutes();
        start();
        RawConnection c = connect();
        try {
            for (String p : new String[] {"/file", "/channel"}) {
                c.send("GET " + p + " HTTP/1.1\r\nHost: x\r\n\r\n");
                RawResponse r = c.read();
                assertEquals(String.valueOf(content10m.length), r.header("content-length"));
                assertArrayEquals(content10m, r.bodyBytes);
            }
            c.send("HEAD /file HTTP/1.1\r\nHost: x\r\n\r\n");
            RawResponse h = c.read(true);
            assertEquals(String.valueOf(content10m.length), h.header("content-length"));
            c.send("GET /file HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            assertArrayEquals(content10m, c.read().bodyBytes);
        } finally {
            c.close();
        }
    }

    @Test
    public void io16_clientsVanishingAreNotSevere() throws Exception {
        final List<String> severe = Collections.synchronizedList(new ArrayList<String>());
        fileRoutes();
        multipartEcho(MultipartLimits.of(Long.MAX_VALUE, 10), false);
        start(TinyHttpd.builder().logger(new TinyLog() {

            @Override
            public boolean isLoggable(Level level) {
                return level.intValue() >= Level.SEVERE.intValue();
            }

            @Override
            public void log(Level level, String message, Throwable thrown) {
                severe.add(message + " " + thrown);
            }
        }));
        RawConnection c = connect();
        c.send("GET /file HTTP/1.1\r\nHost: x\r\n\r\n");
        c.readLine();
        c.socket().setSoLinger(true, 0);
        c.close();
        c = connect();
        c.send("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: multipart/form-data; boundary=" + B
                + "\r\nContent-Length: 100000\r\n\r\n--" + B + "\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n"
                + repeat('x', 1000));
        Thread.sleep(200);
        c.close();
        long deadline = System.currentTimeMillis() + 3000;
        while ((server.getStats().openConnections > 0 || seen.get() == null) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, server.getStats().openConnections);
        assertTrue(String.valueOf(seen.get()), seen.get() instanceof TruncatedBodyException);
        assertEquals(severe.toString(), 0, severe.size());
    }

    private RawResponse serve(String headers) throws IOException {
        RawConnection c = connect();
        try {
            c.send("GET /serve HTTP/1.1\r\nHost: x\r\n" + headers + "\r\n");
            return c.read();
        } finally {
            c.close();
        }
    }

    @Test
    public void io17_io22_io28_io29_rangesAndConditions() throws IOException {
        fileRoutes();
        start();
        long len = content10m.length;
        RawResponse r = serve("Range: bytes=0-99\r\n");
        assertEquals(206, r.status());
        assertEquals("bytes 0-99/" + len, r.header("content-range"));
        assertArrayEquals(java.util.Arrays.copyOfRange(content10m, 0, 100), r.bodyBytes);
        r = serve("Range: bytes=-100\r\n");
        assertArrayEquals(java.util.Arrays.copyOfRange(content10m, (int) len - 100, (int) len), r.bodyBytes);
        r = serve("Range: bytes=" + len + "-\r\n");
        assertEquals(416, r.status());
        assertEquals("bytes */" + len, r.header("content-range"));
        r = serve("Range: bytes=0-1,5-6\r\n");
        assertEquals(200, r.status());
        assertEquals(len, r.bodyBytes.length);
        String etag = r.header("etag");
        assertEquals("bytes", r.header("accept-ranges"));
        assertEquals("nosniff", r.header("x-content-type-options"));
        r = serve("If-None-Match: " + etag + "\r\n");
        assertEquals(304, r.status());
        assertEquals(0, r.bodyBytes.length);
        r = serve("If-Range: \"old-etag\"\r\nRange: bytes=10-\r\n");
        assertEquals(200, r.status());
        r = serve("If-Range: " + etag + "\r\nRange: bytes=10-\r\n");
        assertEquals(206, r.status());
        assertEquals(len - 10, r.bodyBytes.length);
        assertEquals(200, serve("If-Match: " + etag + "\r\n").status());
        assertEquals(412, serve("If-Match: \"other\"\r\n").status());
    }

    @Test
    public void io23_okHttpResume() throws IOException {
        fileRoutes();
        start();
        OkHttpClient client = Clients.okHttp();
        try {
            String url = "http://127.0.0.1:" + port + "/serve";
            okhttp3.Response first = client.newCall(new okhttp3.Request.Builder().url(url)
                    .header("Range", "bytes=0-4194303").build()).execute();
            byte[] a = first.body().bytes();
            String etag = first.header("ETag");
            first.close();
            okhttp3.Response second = client.newCall(new okhttp3.Request.Builder().url(url)
                    .header("Range", "bytes=" + a.length + "-").header("If-Range", etag).build()).execute();
            byte[] b = second.body().bytes();
            assertEquals(206, second.code());
            second.close();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            all.write(a);
            all.write(b);
            assertArrayEquals(content10m, all.toByteArray());
        } finally {
            Clients.shutdown(client);
        }
    }

    @Test
    public void io30_attachmentName() {
        final String[] header = new String[1];
        com.snoworca.tinyhttpd.http.ResponseWrapper probe = new com.snoworca.tinyhttpd.http.ResponseWrapper(
                (Response) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {Response.class}, new java.lang.reflect.InvocationHandler() {

                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                                if (m.getName().equals("setHeader")) {
                                    header[0] = args[0] + ": " + args[1];
                                }
                                return null;
                            }
                        }));
        FileResponses.attachment(probe, "보고서 \"최종\".pdf");
        assertEquals("Content-Disposition: attachment; filename=\"___ __.pdf\"; "
                + "filename*=UTF-8''%EB%B3%B4%EA%B3%A0%EC%84%9C%20%EC%B5%9C%EC%A2%85.pdf", header[0]);
    }

    @Test
    public void io31_parallelDownloadsUseConstantMemory() throws Exception {
        final File big = File.createTempFile("tinyhttpd-io31", ".bin");
        try {
            RandomAccessFile raf = new RandomAccessFile(big, "rw");
            raf.setLength(100L << 20);
            raf.close();
            routes.put("/big", new Handler() {

                @Override
                public void handle(Request req, Response res) throws IOException {
                    res.sendFile(big, 0, big.length());
                }
            });
            start();
            final int n = 16;
            final RawConnection[] cs = new RawConnection[n];
            final byte[][] bufs = new byte[n][16 * 1024];
            for (int i = 0; i < n; i++) {
                cs[i] = connect();
                cs[i].setReadTimeout(30000);
            }
            long baseline = usedHeap();
            final AtomicLong peak = new AtomicLong();
            Thread[] ts = new Thread[n];
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            for (int i = 0; i < n; i++) {
                final int k = i;
                ts[i] = new Thread(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            cs[k].send("GET /big HTTP/1.1\r\nHost: x\r\n\r\n");
                            String line;
                            long len = -1;
                            while (!(line = cs[k].readLine()).isEmpty()) {
                                if (line.toLowerCase().startsWith("content-length:")) {
                                    len = Long.parseLong(line.substring(15).trim());
                                }
                            }
                            InputStream in = cs[k].input();
                            long got = 0;
                            int r;
                            while (got < len && (r = in.read(bufs[k])) != -1) {
                                got += r;
                            }
                            if (got != len) {
                                throw new IOException("got " + got + " of " + len);
                            }
                        } catch (Throwable t) {
                            failure.set(t);
                        }
                    }
                });
                ts[i].start();
            }
            Thread.sleep(100);
            peak.set(usedHeap());
            for (Thread t : ts) {
                t.join(60000);
            }
            for (RawConnection c : cs) {
                c.close();
            }
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            long growth = peak.get() - baseline;
            System.out.println("IO-31 heap growth " + growth / 1024 + " KB");
            assertTrue("heap growth " + growth, growth <= 1 << 20);
        } finally {
            big.delete();
        }
    }

    // ------------------------------------------------------------------ gzip

    private void gzipRoutes(final int bytes, final String type, final String encoding, final boolean declare) {
        routes.put("/g", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.setContentType(type);
                res.setHeader("ETag", "\"abc\"");
                if (encoding != null) {
                    res.setHeader("Content-Encoding", encoding);
                }
                if (declare) {
                    res.setContentLengthLong(bytes);
                }
                byte[] b = new byte[bytes];
                java.util.Arrays.fill(b, (byte) '{');
                res.getOutputStream().write(b);
            }
        });
    }

    private RawResponse gzipGet() throws IOException {
        RawConnection c = connect();
        try {
            c.send("GET /g HTTP/1.1\r\nHost: x\r\nAccept-Encoding: gzip, deflate\r\n\r\n");
            return c.read();
        } finally {
            c.close();
        }
    }

    @Test
    public void io24_io32_gzipCompresses() throws IOException {
        gzipRoutes(10 * 1024, "application/json", null, true);
        start(TinyHttpd.builder().filter(new GzipFilter()));
        RawResponse r = gzipGet();
        assertEquals("gzip", r.header("content-encoding"));
        assertEquals("Accept-Encoding", r.header("vary"));
        assertTrue(!String.valueOf(10 * 1024).equals(r.header("content-length"))); // declared length dropped
        assertEquals("W/\"abc\"", r.header("etag"));
        assertEquals(10 * 1024, readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(r.bodyBytes))).length);
    }

    @Test
    public void io25_alreadyEncoded() throws IOException {
        gzipRoutes(10 * 1024, "application/json", "br", false);
        start(TinyHttpd.builder().filter(new GzipFilter()));
        RawResponse r = gzipGet();
        assertEquals("br", r.header("content-encoding"));
        assertEquals(10 * 1024, r.bodyBytes.length);
    }

    @Test
    public void io27_smallResponseIsNotCompressed() throws IOException {
        gzipRoutes(100, "text/plain", null, false);
        start(TinyHttpd.builder().filter(new GzipFilter()));
        RawResponse r = gzipGet();
        assertNull(r.header("content-encoding"));
        assertEquals("100", r.header("content-length"));
    }

    @Test
    public void io33_concurrentGzipLimit() throws Exception {
        final java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(3);
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        routes.put("/g", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.setContentType("text/plain");
                OutputStream out = res.getOutputStream();
                out.write(new byte[2048]);
                inside.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                out.write(new byte[2048]);
            }
        });
        start(TinyHttpd.builder().filter(new GzipFilter(2)));
        final List<String> encodings = Collections.synchronizedList(new ArrayList<String>());
        Thread[] ts = new Thread[3];
        for (int i = 0; i < 3; i++) {
            ts[i] = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        encodings.add(String.valueOf(gzipGet().header("content-encoding")));
                    } catch (IOException e) {
                        encodings.add("error " + e);
                    }
                }
            });
            ts[i].start();
        }
        inside.await();
        go.countDown();
        for (Thread t : ts) {
            t.join(5000);
        }
        Collections.sort(encodings);
        assertEquals("[gzip, gzip, null]", encodings.toString());
    }

    /** Unused helper kept for symmetry with the other suites. */
    static void unused() {
        fail();
    }
}
