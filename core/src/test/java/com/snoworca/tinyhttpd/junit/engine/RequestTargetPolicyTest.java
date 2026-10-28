package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.*;

import com.snoworca.tinyhttpd.RequestTargetPolicy;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.RequestWrapper;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.TestServers;
import com.snoworca.tinyhttpd.testing.TestTls;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** FR-HTTP-004: raw routing is opt-in; decoded-path and transport validation remain independent. */
public class RequestTargetPolicyTest extends EngineTestBase {
    private static final String[] INVALID = {
        "/x/a%2Fb", "/x/%00", "/x/%2e", "/x/%2e%2e", "/x/%ZZ",
        "/x/%", "/x/%0", "/x/%ff", "/x/%C0%AF", "/x/%ED%A0%80", "/x/../a"
    };

    private void startWith(TinyHttpd.Builder builder, Handler handler) throws IOException {
        if (server != null) server.stop(200);
        if (tls) builder.tls(TestTls.server().build());
        server = builder.port(0).hostCheck(false).handler(handler).build();
        server.start();
        port = server.getPort();
    }

    private RawConnection.RawResponse get(String target) throws IOException {
        try (RawConnection c = connect()) {
            c.send("GET " + target + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            return c.read();
        }
    }

    // FR-HTTP-004 AC-1, AC-7
    @Test
    public void fr_HTTP_004_AC1_defaultAndExplicitStrictRejectBeforeHandler() throws Exception {
        for (boolean explicit : new boolean[] {false, true}) {
            AtomicInteger calls = new AtomicInteger();
            TinyHttpd.Builder builder = TestServers.builder();
            if (explicit) builder.requestTargetPolicy(RequestTargetPolicy.STRICT);
            startWith(builder, (req, res) -> { calls.incrementAndGet(); text(res, req.getPath()); });
            for (String path : INVALID) assertEquals(path, 400, get(path).status());
            assertEquals(0, calls.get());
            assertEquals("/x/ok", get("/x/ok").body);
            assertEquals("/x/a/b", get("/x/a//b").body);
            assertEquals(2, calls.get());
        }
    }

    // FR-HTTP-004 AC-2: invalid encodings belong to the framework only after explicit RAW selection.
    @Test
    public void fr_HTTP_004_AC2_rawAccessorsPreserveEveryTargetAndQuery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startWith(TestServers.builder().requestTargetPolicy(RequestTargetPolicy.RAW), (req, res) -> {
            calls.incrementAndGet();
            Request wrapped = new RequestWrapper(req);
            text(res, wrapped.getRequestURI() + "\n" + String.valueOf(wrapped.getQueryString()));
        });
        String query = "Q=%2F&x=a+b&bad=%ZZ&x=1??tail";
        for (String path : INVALID) {
            RawConnection.RawResponse response = get(path + "?" + query);
            assertEquals(path, 200, response.status());
            assertEquals(path + "\n" + query, response.body);
        }
        for (String path : new String[] {"/x/ok", "/x/a//b", "/x/%252e%252e", "/x/%2f%2F"}) {
            assertEquals(path + "\nnull", get(path).body);
            assertEquals(path + "\n", get(path + "?").body);
        }
        assertEquals("/x/a%2Fb\n" + query, get("http://x/x/a%2Fb?" + query).body);
        assertEquals("/\nx=%ZZ", get("http://x?x=%ZZ").body);
        try (RawConnection c = connect()) {
            c.send("OPTIONS * HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            assertEquals("*\nnull", c.read().body);
        }
        assertEquals(INVALID.length + 11, calls.get());
    }

    // FR-HTTP-004 AC-3: an unchecked exception preserves the existing getPath source signature.
    @Test
    public void fr_HTTP_004_AC3_decodedAccessorAndWrapperRejectWithoutNullOr500() throws Exception {
        AtomicInteger entered = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> wrong = new AtomicReference<>();
        startWith(TestServers.builder().requestTargetPolicy(RequestTargetPolicy.RAW), (req, res) -> {
            entered.incrementAndGet();
            try {
                String decoded = new RequestWrapper(req).getPath();
                if (decoded == null) wrong.set(new AssertionError("nullable decoded path"));
                text(res, decoded);
            } catch (IllegalArgumentException expected) {
                rejected.incrementAndGet();
                throw expected;
            }
        });
        for (String path : INVALID) assertEquals(path, 400, get(path).status());
        assertEquals(INVALID.length, entered.get());
        assertEquals(INVALID.length, rejected.get());
        assertNull(wrong.get());
        assertEquals("/x/a b", get("/x/a%20b").body);
        assertEquals("/x/a/b", get("/x/a//b").body);
        assertEquals("/x/%2e%2e", get("/x/%252e%252e").body);
        assertEquals(0, Request.class.getMethod("getPath").getExceptionTypes().length);
    }

    // FR-HTTP-004 AC-2: admitting a raw path does not corrupt extraction or the next request.
    @Test
    public void fr_HTTP_004_AC2_rawTargetsRemainDistinctOnKeepAlive() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startWith(TestServers.builder().requestTargetPolicy(RequestTargetPolicy.RAW), (req, res) -> {
            calls.incrementAndGet();
            text(res, req.getRequestURI() + "|" + req.getQueryString());
        });
        try (RawConnection c = connect()) {
            c.send("GET /x/%ZZ?bad=% HTTP/1.1\r\nHost: x\r\n\r\n"
                    + "GET /x/a//b?x=2 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            assertEquals("/x/%ZZ|bad=%", c.read().body);
            assertEquals("/x/a//b|x=2", c.read().body);
            assertEquals(2, calls.get());
        }
    }

    // FR-HTTP-004 AC-3: a late decoded-path failure cannot replace an already committed response.
    @Test
    public void fr_HTTP_004_AC3_invalidDecodedPathAfterCommitAbortsWithoutCompletingBody() throws Exception {
        AtomicInteger next = new AtomicInteger();
        startWith(TestServers.builder().requestTargetPolicy(RequestTargetPolicy.RAW), (req, res) -> {
            if ("/next".equals(req.getRequestURI())) { next.incrementAndGet(); text(res, "next"); return; }
            res.getOutputStream().write("prefix".getBytes("US-ASCII"));
            res.flushBuffer();
            req.getPath();
        });
        try (RawConnection c = connect()) {
            c.setReadTimeout(3000);
            c.send("GET /x/%ZZ HTTP/1.1\r\nHost: x\r\n\r\nGET /next HTTP/1.1\r\nHost: x\r\n\r\n");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try {
                int value;
                while ((value = c.input().read()) != -1) bytes.write(value);
            } catch (java.net.SocketException | javax.net.ssl.SSLException closed) { }
            String raw = bytes.toString("ISO-8859-1");
            assertTrue(raw, raw.startsWith("HTTP/1.1 200"));
            assertTrue(raw, raw.contains("6\r\nprefix\r\n"));
            assertFalse(raw, raw.endsWith("0\r\n\r\n"));
            assertFalse(raw, raw.contains("HTTP/1.1 400"));
            assertEquals(0, next.get());
        }
    }

    // FR-HTTP-004 AC-4: the rejected request and trailing pipeline never reach either policy's handler.
    @Test
    public void fr_HTTP_004_AC4_rawPolicyNeverBypassesSyntaxHostOrFraming() throws Exception {
        for (RequestTargetPolicy policy : RequestTargetPolicy.values()) {
            AtomicInteger calls = new AtomicInteger();
            startWith(TestServers.builder().requestTargetPolicy(policy), (req, res) -> {
                calls.incrementAndGet(); text(res, "must not run");
            });
            String[] bad = {
                "GET /x/a b HTTP/1.1\r\nHost: x\r\n\r\n",
                "GET /x/a#b HTTP/1.1\r\nHost: x\r\n\r\n",
                "GET /x/a\u0000b HTTP/1.1\r\nHost: x\r\n\r\n",
                "GET /x/a\tb HTTP/1.1\r\nHost: x\r\n\r\n",
                "GET /x/a\u0080b HTTP/1.1\r\nHost: x\r\n\r\n",
                "GET /x/%ZZ HTTP/1.1\r\n\r\n",
                "GET /x/%ZZ HTTP/1.1\r\nHost: x\r\nHost: y\r\n\r\n",
                "GET /x/%ZZ HTTP/1.1\r\nHost: bad host\r\n\r\n",
                "POST /x/%ZZ HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx",
                "POST /x/%ZZ HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n0\r\n\r\n"
            };
            for (String request : bad) {
                try (RawConnection c = connect()) {
                    c.send(request + "GET /healthy HTTP/1.1\r\nHost: x\r\n\r\n");
                    RawConnection.RawResponse response = c.read();
                    assertEquals(policy + " " + request, 400, response.status());
                    assertEquals("close", response.header("connection"));
                    assertEquals("closed", c.awaitClose(3000));
                }
                assertEquals(0, calls.get());
            }
        }
    }

    // FR-HTTP-004 AC-3, AC-7: changing a reused builder cannot mutate an existing server's policy.
    @Test
    public void fr_HTTP_004_AC7_policyIsImmutablePerServerAndNullIsRejected() throws Exception {
        TinyHttpd.Builder builder = TestServers.builder().requestTargetPolicy(RequestTargetPolicy.RAW)
                .port(0).hostCheck(false).handler((req, res) -> text(res, req.getRequestURI()));
        if (tls) builder.tls(TestTls.server().build());
        server = builder.build();
        TinyHttpd strict = builder.requestTargetPolicy(RequestTargetPolicy.STRICT).build();
        try {
            server.start(); port = server.getPort();
            strict.start();
            assertEquals(RequestTargetPolicy.RAW, server.getConfig().requestTargetPolicy);
            assertEquals(RequestTargetPolicy.STRICT, strict.getConfig().requestTargetPolicy);
            assertEquals(200, get("/x/%ZZ").status());
            try (RawConnection c = new RawConnection(strict.getPort(), 0, tls ? TestTls.socketFactory() : null)) {
                c.send("GET /x/%ZZ HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
                assertEquals(400, c.read().status());
            }
            try { builder.requestTargetPolicy(null); fail("null policy accepted"); }
            catch (NullPointerException expected) { }
        } finally { strict.stop(200); }
    }
}
