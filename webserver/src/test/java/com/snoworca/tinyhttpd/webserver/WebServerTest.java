package com.snoworca.tinyhttpd.webserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
        TinyHttpd.Builder b = com.snoworca.tinyhttpd.testing.TestServers.builder().port(0).hostCheck(false).handler(h);
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

    /** AUD-04: an index file that links outside the root is not served. */
    @Test
    public void aud04_indexSymlinkOutsideRoot() throws Exception {
        File dir = new File(root, "linked");
        dir.mkdir();
        Process p = new ProcessBuilder("ln", "-s", outside.getAbsolutePath(), new File(dir, "index.html").getAbsolutePath())
                .start();
        assertEquals(0, p.waitFor());
        start(new StaticFileHandler(root), null);
        assertEquals(403, send("GET", "/linked/", "").status());
    }

    /** AUD-05: the directory redirect is built from the normalized path, never "//host". */
    @Test
    public void aud05_noProtocolRelativeRedirect() throws IOException {
        new File(root, "evil.example").mkdir();
        start(new StaticFileHandler(root), null);
        RawResponse r = send("GET", "//evil.example", "");
        assertEquals(302, r.status());
        assertEquals("/evil.example/", r.header("location"));
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

    @Test
    public void settingsChangeWhileServing() throws IOException {
        StaticFileHandler h = new StaticFileHandler(root);
        start(h, null);
        assertEquals(403, send("GET", "/docs/", "").status());
        h.directoryListing(true);
        assertEquals(200, send("GET", "/docs/", "").status());
        h.untrustedContent(true);
        assertEquals("sandbox", send("GET", "/hello.txt", "").header("content-security-policy"));
        h.maxFileBytes(5);
        assertEquals(403, send("GET", "/hello.txt", "").status());
        h.maxFileBytes(-1);
        assertEquals(200, send("GET", "/hello.txt", "").status());
    }

    @Test
    public void settingsRevertWhileServing() throws IOException {
        StaticFileHandler h = new StaticFileHandler(root).directoryListing(true).untrustedContent(true);
        start(h, null);
        assertEquals(200, send("GET", "/docs/", "").status());
        h.directoryListing(false);
        assertEquals(403, send("GET", "/docs/", "").status());
        h.untrustedContent(false);
        RawResponse r = send("GET", "/page.html", "");
        assertNull(r.header("content-security-policy"));
        assertNull(r.header("content-disposition"));
    }

    @Test
    public void maxFileBytesBoundaryAndEveryPath() throws IOException {
        StaticFileHandler h = new StaticFileHandler(root).maxFileBytes(11); // hello.txt is 11 bytes
        start(h, null);
        assertEquals("hello world", send("GET", "/hello.txt", "").body);
        h.maxFileBytes(10);
        assertEquals(403, send("GET", "/hello.txt", "").status());
        assertEquals(403, send("HEAD", "/hello.txt", "").status());
        assertEquals(403, send("GET", "/hello.txt", "Range: bytes=0-1\r\n").status());
        assertEquals(200, send("GET", "/site/", "").status()); // index.html is 5 bytes
        h.maxFileBytes(4);
        assertEquals(403, send("GET", "/site/", "").status());
        h.plugin(new UpperCasePlugin());
        assertEquals(403, send("GET", "/note.up", "").status());
        h.maxFileBytes(0);
        assertEquals(403, send("GET", "/note.up", "").status());
        h.maxFileBytes(-1);
        assertEquals(206, send("GET", "/hello.txt", "Range: bytes=0-1\r\n").status());
    }

    @Test
    public void pluginAddedWhileServing() throws IOException {
        StaticFileHandler h = new StaticFileHandler(root);
        start(h, null);
        assertEquals("shout", send("GET", "/note.up", "").body);
        h.plugin(new UpperCasePlugin());
        assertEquals("SHOUT", send("GET", "/note.up", "").body);
    }

    @Test
    public void settingsFlippedUnderTraffic() throws Exception {
        final StaticFileHandler h = new StaticFileHandler(root);
        start(h, null);
        final AtomicBoolean stop = new AtomicBoolean();
        final List<String> bad = new CopyOnWriteArrayList<String>();
        Thread flipper = new Thread(() -> {
            boolean on = false;
            while (!stop.get()) {
                on = !on;
                h.directoryListing(on).untrustedContent(on).maxFileBytes(on ? 5 : -1);
            }
        });
        flipper.start();
        List<Thread> clients = new ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            Thread c = new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        RawResponse f = send("GET", "/hello.txt", "");
                        int d = send("GET", "/docs/", "").status();
                        boolean fileOk = f.status() == 200 && "hello world".equals(f.body) || f.status() == 403;
                        if (!fileOk || d != 200 && d != 403) {
                            bad.add(f.status() + "/" + d);
                        }
                    }
                } catch (IOException e) {
                    bad.add(e.toString());
                }
            });
            c.start();
            clients.add(c);
        }
        for (Thread c : clients) {
            c.join(20000);
        }
        stop.set(true);
        flipper.join(2000);
        assertTrue(bad.toString(), bad.isEmpty());
    }
}
