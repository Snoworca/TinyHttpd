package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.snoworca.tinyhttpd.http.Cookie;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MultipartLimits;
import com.snoworca.tinyhttpd.http.MultipartReader;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.RequestWrapper;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

/**
 * Request header order and the Upgrade offer of a non-101 response: FR-HTTP-001 (Request.getHeaderEntries) and
 * FR-HTTP-002 (Connection: Upgrade beside Upgrade), docs/spec/02.http-contracts.srs.md.
 */
public class HeaderContractsTest extends EngineTestBase {

    private static String join(List<Map.Entry<String, String>> entries) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : entries) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    private RawResponse exchange(String raw) throws IOException {
        if (server == null) {
            start();
        }
        RawConnection c = connect();
        try {
            c.send(raw);
            return c.read();
        } finally {
            c.close();
        }
    }

    // FR-HTTP-001 AC-1: duplicates stay at their own positions, in received order, with the name case as sent
    @Test
    public void fr_HTTP_001_AC1_entriesAreInReceivedOrderWithDuplicatesAndCaseAsSent() throws IOException {
        routes.put("/e", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, join(req.getHeaderEntries()));
            }
        });
        assertEquals("Host=x;A=1;B=2;a=3;X-Y=4;",
                exchange("GET /e HTTP/1.1\r\nHost: x\r\nA: 1\r\nB: 2\r\na: 3\r\nX-Y: 4\r\n\r\n").body);
    }

    // FR-HTTP-001 AC-2: the list and its entries are read-only
    @Test
    public void fr_HTTP_001_AC2_listAndEntriesAreUnmodifiable() throws IOException {
        routes.put("/e", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                List<Map.Entry<String, String>> entries = req.getHeaderEntries();
                StringBuilder sb = new StringBuilder();
                try {
                    entries.add(new AbstractMap.SimpleEntry<String, String>("Z", "9"));
                    sb.append("add ok;");
                } catch (UnsupportedOperationException expected) {
                    sb.append("add refused;");
                }
                try {
                    entries.remove(0);
                    sb.append("remove ok;");
                } catch (UnsupportedOperationException expected) {
                    sb.append("remove refused;");
                }
                try {
                    entries.get(0).setValue("changed");
                    sb.append("setValue ok;");
                } catch (UnsupportedOperationException expected) {
                    sb.append("setValue refused;");
                }
                text(res, sb.toString());
            }
        });
        assertEquals("add refused;remove refused;setValue refused;",
                exchange("GET /e HTTP/1.1\r\nHost: x\r\nA: 1\r\n\r\n").body);
    }

    // FR-HTTP-001 AC-3: a wrapper answers with the entries of the wrapped request
    @Test
    public void fr_HTTP_001_AC3_wrapperReturnsTheWrappedEntries() throws IOException {
        routes.put("/e", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, join(new RequestWrapper(req).getHeaderEntries()));
            }
        });
        assertEquals("Host=x;A=1;B=2;A=3;", exchange("GET /e HTTP/1.1\r\nHost: x\r\nA: 1\r\nB: 2\r\nA: 3\r\n\r\n").body);
    }

    // FR-HTTP-001 AC-4: the existing readers keep their results
    @Test
    public void fr_HTTP_001_AC4_existingHeaderReadersKeepTheirResults() throws IOException {
        routes.put("/e", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                text(res, req.getHeaders("a") + " " + req.getHeaderNames() + " " + req.getHeader("A"));
            }
        });
        assertEquals("[1, 3] [Host, A, B] 1", exchange("GET /e HTTP/1.1\r\nHost: x\r\nA: 1\r\nB: 2\r\na: 3\r\n\r\n").body);
    }

    // FR-HTTP-001 AC-5: an implementer that does not override the method still answers, grouped by name
    @Test
    public void fr_HTTP_001_AC5_defaultImplementationGroupsByNameForOldImplementers() {
        Request old = new GroupedRequest();
        assertEquals("A=1;A=3;B=2;", join(old.getHeaderEntries()));
        try {
            old.getHeaderEntries().add(new AbstractMap.SimpleEntry<String, String>("Z", "9"));
            org.junit.Assert.fail("the default list is read-only too");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    // FR-HTTP-002 AC-1: a 426 offer carries Connection: Upgrade beside Upgrade
    @Test
    public void fr_HTTP_002_AC1_426WithUpgradeSendsConnectionUpgrade() throws IOException {
        routes.put("/u", upgradeOffer(426, "Upgrade"));
        RawResponse r = exchange("GET /u HTTP/1.1\r\nHost: x\r\n\r\n");
        assertEquals(r.statusLine, 426, r.status());
        assertEquals("websocket", r.header("upgrade"));
        assertEquals("Upgrade", r.header("connection"));
    }

    // FR-HTTP-002 AC-2: the token list also carries the connection's own fate
    @Test
    public void fr_HTTP_002_AC2_connectionUpgradeIsJoinedWithCloseAndKeepAlive() throws IOException {
        routes.put("/u", upgradeOffer(426, "Upgrade"));
        start();
        RawConnection c = connect();
        try {
            c.send("GET /u HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            RawResponse r = c.read();
            assertEquals("Upgrade, close", r.header("connection"));
            assertEquals("closed", c.awaitClose(3000));
        } finally {
            c.close();
        }
        RawConnection c10 = connect();
        try {
            c10.send("GET /u HTTP/1.0\r\nHost: x\r\nConnection: keep-alive\r\n\r\n");
            RawResponse r = c10.read();
            assertEquals("Upgrade, keep-alive", r.header("connection"));
        } finally {
            c10.close();
        }
    }

    // FR-HTTP-002 AC-3: without an Upgrade header a Connection value other than close is still ignored
    @Test
    public void fr_HTTP_002_AC3_connectionUpgradeWithoutUpgradeHeaderIsIgnored() throws IOException {
        routes.put("/n", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                res.setHeader("Connection", "Upgrade");
                text(res, "plain");
            }
        });
        RawResponse r = exchange("GET /n HTTP/1.1\r\nHost: x\r\n\r\n");
        assertEquals(200, r.status());
        assertFalse(r.headerLines.toString(), r.header("connection") != null);
    }

    private static Handler upgradeOffer(final int status, final String connection) {
        return new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                res.setStatus(status);
                res.setHeader("Upgrade", "websocket");
                res.setHeader("Connection", connection);
                text(res, "upgrade required");
            }
        };
    }

    /** A Request that knows only the old header readers: A=[1,3], B=[2]. */
    private static final class GroupedRequest implements Request {

        @Override
        public Collection<String> getHeaderNames() {
            return Arrays.asList("A", "B");
        }

        @Override
        public List<String> getHeaders(String name) {
            return "A".equals(name) ? Arrays.asList("1", "3") : "B".equals(name) ? Arrays.asList("2")
                    : Collections.<String> emptyList();
        }

        @Override
        public String getHeader(String name) {
            List<String> all = getHeaders(name);
            return all.isEmpty() ? null : all.get(0);
        }

        @Override
        public String getMethod() {
            return "GET";
        }

        @Override
        public String getRequestURI() {
            return "/";
        }

        @Override
        public String getQueryString() {
            return null;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public String getScheme() {
            return "http";
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public String getPath() {
            return "/";
        }

        @Override
        public String getServerName() {
            return null;
        }

        @Override
        public int getServerPort() {
            return 80;
        }

        @Override
        public long getDateHeader(String name) {
            return -1;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public String getCharacterEncoding() {
            return null;
        }

        @Override
        public List<Cookie> getCookies() {
            return Collections.<Cookie> emptyList();
        }

        @Override
        public String getQueryParameter(String name) {
            return null;
        }

        @Override
        public List<String> getQueryParameterValues(String name) {
            return Collections.<String> emptyList();
        }

        @Override
        public Map<String, List<String>> getQueryParameterMap() {
            return Collections.<String, List<String>> emptyMap();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, List<String>> readForm(int maxBytes) throws IOException, HttpException {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultipartReader readMultipart(MultipartLimits limits) throws IOException, HttpException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getRemoteAddr() {
            return "127.0.0.1";
        }

        @Override
        public int getRemotePort() {
            return 0;
        }

        @Override
        public String getLocalAddr() {
            return "127.0.0.1";
        }

        @Override
        public int getLocalPort() {
            return 0;
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void removeAttribute(String name) {
        }
    }
}
