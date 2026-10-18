package com.snoworca.tinyhttpd.router;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

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
            server = TinyHttpd.builder().port(0).hostCheck(false).handler(router).build();
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
}
