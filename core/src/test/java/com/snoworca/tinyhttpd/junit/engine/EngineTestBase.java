package com.snoworca.tinyhttpd.junit.engine;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Rule;
import org.junit.rules.Timeout;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.testing.RawConnection;

/** Starts a server whose handler dispatches on the exact path to handlers registered by the test. */
public abstract class EngineTestBase {

    @Rule
    public Timeout timeout = Timeout.seconds(60);

    protected final Map<String, Handler> routes = new ConcurrentHashMap<String, Handler>();

    protected TinyHttpd server;

    protected int port;

    protected final Handler router = new Handler() {

        @Override
        public void handle(Request req, Response res) throws IOException, HttpException {
            Handler h = routes.get(req.getPath());
            if (h == null) {
                h = routes.get("*");
            }
            if (h == null) {
                throw new HttpException(404, "no route " + req.getPath());
            }
            h.handle(req, res);
        }
    };

    protected void start() throws IOException {
        start(TinyHttpd.builder());
    }

    protected void start(TinyHttpd.Builder builder) throws IOException {
        server = builder.port(0).handler(router).build();
        server.start();
        port = server.getPort();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(500);
        }
    }

    protected RawConnection connect() throws IOException {
        return new RawConnection(port);
    }

    protected static void text(Response res, String s) throws IOException {
        res.setContentType("text/plain");
        res.getOutputStream().write(s.getBytes("UTF-8"));
    }

    protected static String repeat(char c, int n) {
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }
}
