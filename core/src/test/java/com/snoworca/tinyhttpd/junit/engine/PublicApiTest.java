package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

import org.junit.Test;

import com.snoworca.tinyhttpd.ServerConfig;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Cookie;
import com.snoworca.tinyhttpd.http.ErrorHandler;
import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.FilterChain;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MimeTypes;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.filter.ForwardedHeadersFilter;
import com.snoworca.tinyhttpd.http.util.Base64;
import com.snoworca.tinyhttpd.http.util.Html;
import com.snoworca.tinyhttpd.log.TinyLog;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

/** P4 public API (API-nn, docs/next/p04-public-api.md). */
public class PublicApiTest extends EngineTestBase {

    private final List<String> order = Collections.synchronizedList(new ArrayList<String>());

    private RawResponse get(String raw) throws IOException {
        RawConnection c = connect();
        try {
            c.send(raw);
            return c.read();
        } finally {
            c.close();
        }
    }

    private RawResponse get(String path, String extraHeaders) throws IOException {
        return get("GET " + path + " HTTP/1.1\r\nHost: x\r\n" + extraHeaders + "\r\n");
    }

    private void echo(final String what) {
        routes.put("*", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                Object v;
                if ("path".equals(what)) {
                    v = req.getPath();
                } else if ("query".equals(what)) {
                    v = req.getQueryParameterMap();
                } else if ("cookies".equals(what)) {
                    StringBuilder sb = new StringBuilder();
                    for (Cookie c : req.getCookies()) {
                        sb.append(c.getName()).append('=').append(c.getValue()).append(';');
                    }
                    v = sb;
                } else if ("date".equals(what)) {
                    v = req.getDateHeader("If-Modified-Since");
                } else if ("charset".equals(what)) {
                    v = req.getCharacterEncoding();
                } else {
                    v = req.getRemoteAddr() + "|" + req.getScheme();
                }
                text(res, String.valueOf(v));
            }
        });
    }

    @Test
    public void api01_defaults() throws IOException {
        TinyHttpd s = com.snoworca.tinyhttpd.testing.TestServers.builder().handler(router).build();
        ServerConfig c = s.getConfig();
        assertEquals(8192, c.inputBufferBytes);
        assertEquals(8192, c.outputBufferBytes);
        assertEquals(8190, c.maxRequestLineBytes);
        assertEquals(16384, c.maxHeaderBytes);
        assertEquals(100, c.maxHeaderCount);
        assertEquals(-1, c.getMaxRequestBodyBytes());
        assertEquals(65536, c.maxDrainBytes);
        assertEquals(1024, c.maxChunkSizeLineBytes);
        assertEquals(8192, c.maxTrailerBytes);
        assertEquals(2000, c.getLingerTimeout());
        assertEquals(1000, c.maxRequestsPerConnection);
        assertNull(c.serverHeader);
        assertEquals(15000, c.getIdleTimeout());
        assertEquals(2000, c.getIdleTimeoutUnderLoad());
        assertEquals(10000, c.getHeaderTimeout());
        assertEquals(30000, c.getBodyReadTimeout());
        assertEquals(30000, c.getWriteTimeout());
        assertEquals(64, c.getMaxWorkers());
        assertEquals(128, c.getMaxConnections());
        assertEquals(32, c.maxConnectionsPerAddress);
        assertEquals(5000, c.getQueueWaitTimeout());
        assertEquals(256 * 1024, c.workerStackBytes);
        assertEquals(60000, c.getWorkerKeepAlive());
        assertTrue(c.daemon);
        assertEquals(5000, c.getShutdownGrace());
        assertEquals(64 * 1024, c.sendFileBufferBytes);
        assertEquals(8, c.sendFileBuffers);
        assertEquals(64, c.getMaxEventStreams());
        assertEquals(64, c.eventQueueEvents);
        assertEquals(64 * 1024, c.eventQueueBytes);
        assertEquals(16, c.getMaxUpgradedConnections());
        s.start();
        assertTrue(s.getPort() > 0);
        s.stop(100);
    }

    @Test
    public void api02_invalidOptionIsNamed() {
        try {
            com.snoworca.tinyhttpd.testing.TestServers.builder().handler(router).inputBufferBytes(8191).build();
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("inputBufferBytes"));
        }
        try {
            // AUD-02: a chunk size line longer than the input buffer could never complete
            com.snoworca.tinyhttpd.testing.TestServers.builder().handler(router).inputBufferBytes(1024)
                    .maxRequestLineBytes(1000).build();
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("maxChunkSizeLineBytes"));
        }
        try {
            com.snoworca.tinyhttpd.testing.TestServers.builder().handler(router).maxWorkers(0).build();
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("maxWorkers"));
        }
    }

    private Filter recording(final String name, final boolean proceed) {
        return new Filter() {

            @Override
            public void doFilter(Request req, Response res, FilterChain chain) throws IOException, HttpException {
                order.add(name);
                req.setAttribute("from", name);
                if (proceed) {
                    chain.doFilter(req, res);
                } else {
                    text(res, "stopped by " + name);
                }
            }
        };
    }

    @Test
    public void api03_api07_filtersRunInOrderAndShareAttributes() throws IOException {
        final Object[] attr = new Object[1];
        routes.put("/f", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                order.add("handler");
                attr[0] = req.getAttribute("from");
                text(res, "handled");
            }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().filter(recording("a", true)).filter(recording("b", true)));
        assertEquals("handled", get("/f", "").body);
        assertEquals("[a, b, handler]", order.toString());
        assertEquals("b", attr[0]);
    }

    @Test
    public void api04_filterCanStopTheChain() throws IOException {
        routes.put("/f", new Handler() {

            @Override
            public void handle(Request req, Response res) {
                order.add("handler");
            }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().filter(recording("a", false)));
        assertEquals("stopped by a", get("/f", "").body);
        assertEquals("[a]", order.toString());
    }

    @Test
    public void api05_dateFormats() throws IOException {
        echo("date");
        start();
        String imf = get("/", "If-Modified-Since: Sun, 06 Nov 1994 08:49:37 GMT\r\n").body;
        assertEquals("784111777000", imf);
        assertEquals(imf, get("/", "If-Modified-Since: Sunday, 06-Nov-94 08:49:37 GMT\r\n").body);
        assertEquals(imf, get("/", "If-Modified-Since: Sun Nov  6 08:49:37 1994\r\n").body);
    }

    @Test
    public void api06_characterEncoding() throws IOException {
        echo("charset");
        start();
        assertEquals("EUC-KR", get("/", "Content-Type: text/plain; charset=EUC-KR\r\n").body);
    }

    @Test
    public void api08_customErrorHandler() throws IOException {
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().errorHandler(new ErrorHandler() {

            @Override
            public void render(int status, String message, Request request, Response response) throws IOException {
                response.setContentType("application/json");
                response.getOutputStream().write(("{\"status\":" + status + "}").getBytes("UTF-8"));
            }
        }));
        RawResponse r = get("/missing", "");
        assertEquals(404, r.status());
        assertEquals("{\"status\":404}", r.body);
        assertEquals("application/json", r.header("content-type"));
    }

    @Test
    public void api09_api13_pathDecoding() throws IOException {
        echo("path");
        start();
        assertEquals(400, get("/a%2Fb", "").status());
        assertEquals(400, get("/a/%2e%2e/b", "").status());
        assertEquals(400, get("/a/../b", "").status());
        assertEquals(400, get("/a%ZZ", "").status());
        assertEquals(400, get("/a%C3%28", "").status());
        assertEquals("/a+b", get("/a+b", "").body);
        assertEquals("/한글", get("/%ED%95%9C%EA%B8%80", "").body);
        assertEquals("/a/b/", get("//a//b/", "").body);
    }

    @Test
    public void api14_queryDecodingIsLenient() throws IOException {
        echo("query");
        start();
        assertEquals("{q=[a b&c, 2]}", get("/?q=a+b%26c&q=2", "").body);
        assertEquals("{q=[100%], r=[%ZZ]}", get("/?q=100%&r=%ZZ", "").body);
    }

    @Test
    public void api15_requestCookies() throws IOException {
        echo("cookies");
        start();
        assertEquals("a=1;b=2;", get("/", "Cookie: a=1; b=\"2\"\r\n").body);
        assertEquals("a=1;b=2;c=3;", get("/", "Cookie: a=1;b=2 ;  c=3\r\n").body);
    }

    @Test
    public void api16_setCookieSerialization() {
        Cookie c = new Cookie("sid", "abc").setMaxAge(60).setExpires(784111777000L).setPath("/").setDomain("nas.local")
                .setSecure(true).setHttpOnly(true).setSameSite("Lax");
        assertEquals("sid=abc; Max-Age=60; Expires=Sun, 06 Nov 1994 08:49:37 GMT; Path=/; Domain=nas.local; Secure; "
                + "HttpOnly; SameSite=Lax", c.toSetCookie());
        try {
            new Cookie("a b", "1");
            fail();
        } catch (IllegalArgumentException expected) {
            // forbidden name character
        }
    }

    @Test
    public void api17_clientAbortIsNotSevere() throws Exception {
        final List<String> severe = Collections.synchronizedList(new ArrayList<String>());
        routes.put("/big", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                byte[] block = new byte[64 * 1024];
                for (int i = 0; i < 1000; i++) {
                    res.getOutputStream().write(block);
                }
            }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().logger(new TinyLog() {

            @Override
            public boolean isLoggable(Level level) {
                return level.intValue() >= Level.WARNING.intValue();
            }

            @Override
            public void log(Level level, String message, Throwable thrown) {
                severe.add(message + " " + thrown);
            }
        }));
        RawConnection c = connect();
        c.send("GET /big HTTP/1.1\r\nHost: x\r\n\r\n");
        c.readLine();
        c.socket().setSoLinger(true, 0);
        c.close();
        long deadline = System.currentTimeMillis() + 3000;
        while (server.getStats().openConnections > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, server.getStats().openConnections);
        assertEquals(severe.toString(), 0, severe.size());
    }

    @Test
    public void api18_mimeTypes() {
        assertEquals("video/x-matroska", MimeTypes.get("MKV"));
        assertEquals("image/heic", MimeTypes.get("photo.HEIC"));
        assertNull(MimeTypes.get("unknownext"));
    }

    @Test
    public void api19_htmlEscape() {
        assertEquals("&lt;a href=&#39;x&#39;&gt;&amp;", Html.escape("<a href='x'>&"));
    }

    @Test
    public void api20_api21_hostAllowlistIsOnByDefault() throws IOException {
        hostCheck = true;
        echo("path");
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().allowedHosts("nas.local"));
        assertEquals(421, get("/", "").status());
        assertEquals(421, get("GET / HTTP/1.1\r\nHost: evil.example\r\n\r\n").status());
        for (String host : new String[] {"192.168.0.7:8080", "[fe80::1]", "LOCALHOST.", "nas.local:80"}) {
            assertEquals(host, 200, get("GET / HTTP/1.1\r\nHost: " + host + "\r\n\r\n").status());
        }
    }

    @Test
    public void api22_base64() throws IOException {
        String[][] vectors = {{"", ""}, {"f", "Zg=="}, {"fo", "Zm8="}, {"foo", "Zm9v"}, {"foob", "Zm9vYg=="},
            {"fooba", "Zm9vYmE="}, {"foobar", "Zm9vYmFy"}};
        for (String[] v : vectors) {
            assertEquals(v[1], Base64.encode(v[0].getBytes("US-ASCII")));
            assertEquals(v[0], new String(Base64.decode(v[1]), "US-ASCII"));
        }
        byte[] raw = {(byte) 0xfb, (byte) 0xff, (byte) 0xbf};
        assertEquals("-_-_", Base64.encodeUrlSafe(raw));
        assertArrayEquals(raw, Base64.decode("-_-_"));
        assertEquals("Zg", Base64.encodeUrlSafe("f".getBytes("US-ASCII")));
        assertEquals("f", new String(Base64.decode("Zg"), "US-ASCII"));
    }

    @Test
    public void api23_secT01_forwardedHeadersOnlyFromTrustedProxies() throws IOException {
        echo("remote");
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().filter(new ForwardedHeadersFilter("127.0.0.1")));
        assertEquals("1.2.3.4|https", get("/", "X-Forwarded-For: 9.9.9.9, 1.2.3.4\r\nX-Forwarded-Proto: https\r\n").body);
        server.stop(100);
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().filter(new ForwardedHeadersFilter("10.0.0.1")));
        assertEquals("127.0.0.1|http", get("/", "X-Forwarded-For: 1.2.3.4\r\nX-Forwarded-Proto: https\r\n").body);
    }

    /** Keeps the InputStream import used in the usability scenarios. */
    static void consume(InputStream in) throws IOException {
        while (in.read() != -1) {
            // consume
        }
    }

    @Test
    public void sameInstanceAttributes() throws IOException {
        final Object value = new Object();
        final Object[] got = new Object[1];
        routes.put("/a", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                got[0] = req.getAttribute("obj");
            }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().filter(new Filter() {

            @Override
            public void doFilter(Request req, Response res, FilterChain chain) throws IOException, HttpException {
                req.setAttribute("obj", value);
                chain.doFilter(req, res);
            }
        }));
        get("/a", "");
        assertSame(value, got[0]);
    }
}
