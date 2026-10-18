package com.snoworca.tinyhttpd.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

/** P8-01 router (MOD-01..06). */
public class RouterTest {

    @Rule
    public Timeout timeout = Timeout.seconds(30);

    private TinyHttpd server;

    @After
    public void stop() {
        if (server != null) {
            server.stop(200);
        }
    }

    private static Handler says(final String what) {
        return new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.getOutputStream().write((what + " " + PathParams.all(req) + " " + req.getPath()).getBytes("UTF-8"));
            }
        };
    }

    private RawResponse send(Router router, String method, String path) throws IOException {
        if (server == null) {
            server = com.snoworca.tinyhttpd.testing.TestServers.builder().port(0).hostCheck(false).handler(router).build();
            server.start();
        }
        RawConnection c = new RawConnection(server.getPort());
        try {
            c.send(method + " " + path + " HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n");
            return c.read("HEAD".equals(method));
        } finally {
            c.close();
        }
    }

    @Test
    public void mod01_literalBeatsVariable() throws IOException {
        Router r = new Router().get("/users/{id}", says("byId")).get("/users/me", says("me"));
        assertEquals("me {} /users/me", send(r, "GET", "/users/me").body);
        assertEquals("byId {id=42} /users/42", send(r, "GET", "/users/42").body);
    }

    @Test
    public void mod02_restVariable() throws IOException {
        Router r = new Router().get("/files/{path*}", says("file"));
        assertEquals("file {path=a/b/c.txt} /files/a/b/c.txt", send(r, "GET", "/files/a/b/c.txt").body);
        assertEquals("file {path=한} /files/한", send(r, "GET", "/files/%ED%95%9C").body);
    }

    @Test
    public void mod03_methodNotAllowed() throws IOException {
        Router r = new Router().get("/only-get", says("g"));
        RawResponse res = send(r, "POST", "/only-get");
        assertEquals(405, res.status());
        assertEquals("GET, HEAD, OPTIONS", res.header("allow"));
        RawResponse opt = send(r, "OPTIONS", "/only-get");
        assertEquals(204, opt.status());
        assertEquals("GET, HEAD, OPTIONS", opt.header("allow"));
    }

    @Test
    public void mod04_notFound() throws IOException {
        assertEquals(404, send(new Router().get("/a", says("a")), "GET", "/b").status());
    }

    @Test
    public void mod05_headUsesGetRoute() throws IOException {
        RawResponse res = send(new Router().get("/h", says("h")), "HEAD", "/h");
        assertEquals(200, res.status());
        assertEquals("7", res.header("content-length")); // "h {} /h"
    }

    @Test
    public void mod06_mount() throws IOException {
        Router api = new Router().get("/items/{id}", says("item"));
        Router root = new Router().mount("/api", api).get("/", says("root"));
        assertEquals("item {id=7} /items/7", send(root, "GET", "/api/items/7").body);
        assertEquals("root {} /", send(root, "GET", "/").body);
    }

    @Test
    public void mod06_unmountWhileServing() throws IOException {
        Router root = new Router().get("/", says("root"));
        assertEquals(404, send(root, "GET", "/share/a").status());
        root.mount("/share", says("share"));
        assertEquals(200, send(root, "GET", "/share/a").status());
        assertTrue(root.unmount("/share"));
        assertFalse(root.unmount("/share"));
        assertEquals(404, send(root, "GET", "/share/a").status());
        assertEquals("root {} /", send(root, "GET", "/").body);
    }

    @Test
    public void mod06_unmountMatchesThePrefixAsMounted() throws IOException {
        Router root = new Router().mount("share", says("share"));
        assertEquals(200, send(root, "GET", "/share/a").status());
        assertFalse(root.unmount("/other"));
        assertFalse(root.unmount("/shar"));
        assertTrue(root.unmount("/share")); // leading slash optional, as in mount
        assertEquals(404, send(root, "GET", "/share/a").status());
    }

    @Test
    public void mod06_unmountLeavesRoutesAndRemovesEveryMountAtThePrefix() throws IOException {
        Router root = new Router().get("/a", says("route")).mount("/a", says("m1")).mount("/a", says("m2"))
                .mount("/b", says("b"));
        assertTrue(root.unmount("/a"));
        assertEquals("route {} /a", send(root, "GET", "/a").body);
        assertEquals(404, send(root, "GET", "/a/x").status());
        assertEquals(200, send(root, "GET", "/b/x").status());
        root.mount("/a", says("again"));
        assertEquals(200, send(root, "GET", "/a/x").status());
    }

    @Test
    public void mod06_mountAndUnmountUnderTraffic() throws Exception {
        final Router root = new Router().get("/", says("root"));
        send(root, "GET", "/"); // starts the server before the threads share it
        final AtomicBoolean stop = new AtomicBoolean();
        final List<String> bad = new CopyOnWriteArrayList<String>();
        Thread flipper = new Thread(() -> {
            while (!stop.get()) {
                root.mount("/x", says("x"));
                root.unmount("/x");
            }
        });
        flipper.start();
        List<Thread> clients = new ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            Thread c = new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        int st = send(root, "GET", "/").status();
                        int sx = send(root, "GET", "/x/y").status();
                        if (st != 200 || sx != 200 && sx != 404) {
                            bad.add(st + "/" + sx);
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

    @Test
    public void mod06_mountKeepsTrailingSlash() throws IOException {
        Router root = new Router().mount("/share", says("m"));
        assertEquals("m {} /d/", send(root, "GET", "/share/d/").body);
        assertEquals("m {} /d", send(root, "GET", "/share/d").body);
        assertEquals("m {} /", send(root, "GET", "/share/").body);
    }
}
