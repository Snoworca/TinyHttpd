package com.snoworca.tinyhttpd.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

/** P8-04 sessions and CSRF (MOD-20..25). */
public class SessionTest {

    @Rule
    public Timeout timeout = Timeout.seconds(30);

    private TinyHttpd server;

    private SessionFilter sessions;

    @After
    public void stop() {
        if (server != null) {
            server.stop(200);
        }
    }

    private static final Handler APP = new Handler() {

        @Override
        public void handle(Request req, Response res) throws IOException, HttpException {
            String p = req.getPath();
            Session s;
            if (p.equals("/create")) {
                s = Session.create(req, res);
                s.setAttribute("user", "kim");
            } else if (p.equals("/login")) {
                s = Session.create(req, res);
                s.markAuthenticated();
            } else if (p.equals("/rotate")) {
                s = Session.rotate(req, res);
            } else {
                s = Session.get(req);
            }
            String body = s == null ? "none" : s.getId() + "|" + s.getAttribute("user") + "|" + s.getCsrfToken();
            res.getOutputStream().write(body.getBytes("UTF-8"));
        }
    };

    private void start(SessionFilter f, boolean csrf) throws IOException {
        sessions = f;
        TinyHttpd.Builder b = com.snoworca.tinyhttpd.testing.TestServers.builder().port(0).hostCheck(false).filter(f).handler(APP);
        if (csrf) {
            b.filter(new CsrfFilter());
        }
        server = b.build();
        server.start();
    }

    private RawResponse send(String method, String path, String headers) throws IOException {
        RawConnection c = new RawConnection(server.getPort());
        try {
            c.send(method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + server.getPort() + "\r\n" + headers
                    + "Content-Length: 0\r\n\r\n");
            return c.read();
        } finally {
            c.close();
        }
    }

    private static String sid(RawResponse r) {
        String setCookie = r.header("set-cookie");
        return setCookie.substring("TSESSIONID=".length(), setCookie.indexOf(';'));
    }

    @Test
    public void mod20_sessionsOnlyOnDemand() throws IOException {
        start(new SessionFilter(), false);
        RawResponse none = send("GET", "/show", "");
        assertEquals("none", none.body);
        assertNull(none.header("set-cookie"));
        RawResponse created = send("GET", "/create", "");
        String cookie = created.header("set-cookie");
        assertTrue(cookie, cookie.matches("TSESSIONID=[A-Za-z0-9_-]{22}; Path=/; HttpOnly; SameSite=Lax"));
        assertTrue(send("GET", "/show", "Cookie: TSESSIONID=" + sid(created) + "\r\n").body.contains("|kim|"));
    }

    @Test
    public void mod21_expiredSessionIsGone() throws Exception {
        start(new SessionFilter().maxInactive(200), false);
        String id = sid(send("GET", "/create", ""));
        Thread.sleep(400);
        assertEquals("none", send("GET", "/show", "Cookie: TSESSIONID=" + id + "\r\n").body);
    }

    @Test
    public void mod22_rotateKeepsAttributes() throws IOException {
        start(new SessionFilter(), false);
        String old = sid(send("GET", "/create", ""));
        RawResponse rotated = send("GET", "/rotate", "Cookie: TSESSIONID=" + old + "\r\n");
        String fresh = sid(rotated);
        assertTrue(!fresh.equals(old));
        assertTrue(rotated.body.startsWith(fresh + "|kim|"));
        assertEquals("none", send("GET", "/show", "Cookie: TSESSIONID=" + old + "\r\n").body);
    }

    @Test
    public void mod23_evictsOldestUnauthenticated() throws Exception {
        start(new SessionFilter().maxSessions(5).maxSessionsPerAddress(100), false);
        String[] auth = new String[2];
        for (int i = 0; i < 2; i++) {
            auth[i] = sid(send("GET", "/login", ""));
        }
        String oldest = sid(send("GET", "/create", ""));
        Thread.sleep(5);
        for (int i = 0; i < 2; i++) {
            send("GET", "/create", "");
        }
        send("GET", "/create", "");
        assertEquals("none", send("GET", "/show", "Cookie: TSESSIONID=" + oldest + "\r\n").body);
        for (String a : auth) {
            assertTrue(send("GET", "/show", "Cookie: TSESSIONID=" + a + "\r\n").body.startsWith(a));
        }
    }

    @Test
    public void mod24_perAddressLimit() throws IOException {
        start(new SessionFilter().maxSessionsPerAddress(50), false);
        for (int i = 0; i < 50; i++) {
            assertEquals(200, send("GET", "/create", "").status());
        }
        assertEquals(503, send("GET", "/create", "").status());
    }

    @Test
    public void mod25_csrf() throws IOException {
        start(new SessionFilter(), true);
        int port = server.getPort();
        assertEquals(403, send("POST", "/show", "Origin: http://evil.example\r\n").status());
        assertEquals(200, send("POST", "/show", "Origin: http://127.0.0.1:" + port + "\r\n").status());
        assertEquals(200, send("POST", "/show", "Referer: http://127.0.0.1:" + port + "/page\r\n").status());
        assertEquals(403, send("POST", "/show", "").status());
        RawResponse created = send("GET", "/create", "");
        String token = created.body.split("\\|")[2];
        assertEquals(200, send("POST", "/show", "Cookie: TSESSIONID=" + sid(created) + "\r\nX-CSRF-Token: " + token
                + "\r\n").status());
        assertNotNull(sessions);
    }
}
