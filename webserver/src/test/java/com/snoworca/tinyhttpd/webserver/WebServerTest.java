package com.snoworca.tinyhttpd.webserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

/** P8-02 webserver (MOD-07..12, MOD-30, SEC-T02). */
public class WebServerTest {

    @Rule
    public Timeout timeout = Timeout.seconds(30);

    private File root;

    private File outside;

    private TinyHttpd server;

    @Before
    public void files() throws IOException {
        root = File.createTempFile("www", "");
        root.delete();
        root.mkdirs();
        outside = File.createTempFile("secret", ".txt");
        write(outside, "secret");
        write(new File(root, "hello.txt"), "hello world");
        write(new File(root, "page.html"), "<script>alert(1)</script>");
        write(new File(root, "pic.svg"), "<svg/>");
        write(new File(root, "note.up"), "shout");
        new File(root, "docs").mkdir();
        write(new File(root, "docs/<script>x.txt"), "x");
        new File(root, "site").mkdir();
        write(new File(root, "site/index.html"), "index");
    }

    @After
    public void cleanup() {
        if (server != null) {
            server.stop(200);
        }
        deleteTree(root);
        outside.delete();
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        f.delete();
    }

    private static void write(File f, String s) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes("UTF-8"));
        out.close();
    }

    private void start(StaticFileHandler h, CorsFilter cors) throws IOException {
        TinyHttpd.Builder b = TinyHttpd.builder().port(0).hostCheck(false).handler(h);
        if (cors != null) {
            b.filter(cors);
        }
        server = b.build();
        server.start();
    }

    private RawResponse send(String method, String path, String headers) throws IOException {
        RawConnection c = new RawConnection(server.getPort());
        try {
            c.send(method + " " + path + " HTTP/1.1\r\nHost: x\r\n" + headers + "\r\n");
            return c.read("HEAD".equals(method));
        } finally {
            c.close();
        }
    }

    @Test
    public void mod07_getHeadRange() throws IOException {
        start(new StaticFileHandler(root), null);
        assertEquals("hello world", send("GET", "/hello.txt", "").body);
        RawResponse head = send("HEAD", "/hello.txt", "");
        assertEquals("11", head.header("content-length"));
        RawResponse range = send("GET", "/hello.txt", "Range: bytes=6-\r\n");
        assertEquals(206, range.status());
        assertEquals("world", range.body);
        assertEquals("index", send("GET", "/site/", "").body);
        assertEquals(302, send("GET", "/site", "").status());
        assertEquals(404, send("GET", "/missing.txt", "").status());
    }

    @Test
    public void mod08_symlinkOutsideRoot() throws Exception {
        File link = new File(root, "escape.txt");
        Process p = new ProcessBuilder("ln", "-s", outside.getAbsolutePath(), link.getAbsolutePath()).start();
        assertEquals(0, p.waitFor());
        start(new StaticFileHandler(root), null);
        assertEquals(403, send("GET", "/escape.txt", "").status());
    }

    @Test
    public void mod09_listingEscapesNames() throws IOException {
        start(new StaticFileHandler(root).directoryListing(true), null);
        String body = send("GET", "/docs/", "").body;
        assertTrue(body, body.contains("&lt;script&gt;x.txt"));
        assertTrue(body, !body.contains("<script>"));
    }

    @Test
    public void mod10_corsPreflight() throws IOException {
        start(new StaticFileHandler(root), new CorsFilter("https://app.example"));
        RawResponse ok = send("OPTIONS", "/hello.txt",
                "Origin: https://app.example\r\nAccess-Control-Request-Method: PUT\r\n");
        assertEquals(204, ok.status());
        assertEquals("https://app.example", ok.header("access-control-allow-origin"));
        assertTrue(ok.header("access-control-allow-methods").contains("PUT"));
        RawResponse no = send("OPTIONS", "/hello.txt", "Origin: https://evil.example\r\nAccess-Control-Request-Method: PUT\r\n");
        assertNull(no.header("access-control-allow-origin"));
        assertEquals("https://app.example",
                send("GET", "/hello.txt", "Origin: https://app.example\r\n").header("access-control-allow-origin"));
        try {
            new CorsFilter("*").allowCredentials(true);
            throw new AssertionError("wildcard with credentials accepted");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    @Test
    public void mod11_mod12_cliWithServiceLoaderPlugin() throws IOException {
        server = SimpleWebServer.start("-h", "127.0.0.1", "-p", "0", "-d", root.getAbsolutePath());
        RawConnection c = new RawConnection(server.getPort());
        try {
            c.send("GET /note.up HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            assertEquals("SHOUT", c.read().body);
            c.send("GET /hello.txt HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            assertEquals("hello world", c.read().body);
        } finally {
            c.close();
        }
    }

    @Test
    public void mod30_untrustedContent() throws IOException {
        start(new StaticFileHandler(root).untrustedContent(true), null);
        for (String p : new String[] {"/page.html", "/pic.svg"}) {
            RawResponse r = send("GET", p, "");
            assertTrue(r.header("content-disposition"), r.header("content-disposition").startsWith("attachment"));
            assertEquals("sandbox", r.header("content-security-policy"));
            assertEquals("nosniff", r.header("x-content-type-options"));
        }
    }

    @Test
    public void secT02_listingOffByDefault() throws IOException {
        start(new StaticFileHandler(root), null);
        assertEquals(403, send("GET", "/docs/", "").status());
    }
}
