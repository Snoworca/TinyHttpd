package com.snoworca.tinyhttpd.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.snoworca.tinyhttpd.http.Cookie;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MultipartLimits;
import com.snoworca.tinyhttpd.http.MultipartReader;
import com.snoworca.tinyhttpd.http.Request;

/** One request. Created per request, so no state leaks into the next request on the connection. */
final class Exchange implements Request {

    private static final int BODY_UNUSED = 0, BODY_STREAM = 1, BODY_FORM = 2, BODY_MULTIPART = 3;

    final Connection conn;

    String method;

    String requestUri;

    String queryString;

    /** 0 for HTTP/1.0, 1 for HTTP/1.1 and later 1.x. */
    int minor;

    String path;

    /** Host from an absolute-form target, else null. */
    String targetHost;

    final ArrayList<String> headerNames = new ArrayList<String>(16);

    final ArrayList<String> headerValues = new ArrayList<String>(16);

    BodyInput body;

    long contentLength;

    boolean expectContinue;

    boolean continueSent;

    ExchangeResponse response;

    private int bodyMode = BODY_UNUSED;

    private Map<String, List<String>> query;

    private Map<String, Object> attributes;

    Exchange(Connection conn) {
        this.conn = conn;
    }

    boolean isHttp11() {
        return minor >= 1;
    }

    boolean isHead() {
        return "HEAD".equals(method);
    }

    /** @return true if the client asked to keep the connection (RFC 9112 §9.3) */
    boolean wantsKeepAlive() {
        List<String> c = getHeaders("Connection");
        return isHttp11() ? !Ascii.hasToken(c, "close") : Ascii.hasToken(c, "keep-alive");
    }

    /** False while a 100-continue request waits for an interim response that was never sent. */
    boolean mayReadBody() {
        return !expectContinue || continueSent;
    }

    /** Sends {@code 100 Continue} before the first body read if the client expects it. */
    void beforeBodyRead() throws IOException {
        if (expectContinue && !continueSent && !response.isCommitted()) {
            continueSent = true;
            byte[] interim = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(Ascii.LATIN1);
            conn.write(interim, 0, interim.length);
        }
    }

    // ------------------------------------------------------------------ request line

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getRequestURI() {
        return requestUri;
    }

    @Override
    public String getQueryString() {
        return queryString;
    }

    @Override
    public String getProtocol() {
        return minor == 0 ? "HTTP/1.0" : "HTTP/1.1";
    }

    @Override
    public String getScheme() {
        return conn.isSecure() ? "https" : "http";
    }

    @Override
    public boolean isSecure() {
        return conn.isSecure();
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getServerName() {
        String authority = targetHost != null ? targetHost : getHeader("Host");
        if (authority == null) {
            return null;
        }
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close < 0 ? authority : authority.substring(0, close + 1);
        }
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    @Override
    public int getServerPort() {
        String authority = targetHost != null ? targetHost : getHeader("Host");
        if (authority != null) {
            int close = authority.lastIndexOf(']');
            int colon = authority.lastIndexOf(':');
            if (colon > close && colon + 1 < authority.length()) {
                try {
                    return Integer.parseInt(authority.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    // fall back to the scheme default
                }
            }
        }
        return isSecure() ? 443 : 80;
    }

    // ------------------------------------------------------------------ headers

    @Override
    public String getHeader(String name) {
        for (int i = 0, n = headerNames.size(); i < n; i++) {
            if (headerNames.get(i).equalsIgnoreCase(name)) {
                return headerValues.get(i);
            }
        }
        return null;
    }

    @Override
    public List<String> getHeaders(String name) {
        List<String> out = null;
        for (int i = 0, n = headerNames.size(); i < n; i++) {
            if (headerNames.get(i).equalsIgnoreCase(name)) {
                if (out == null) {
                    out = new ArrayList<String>(2);
                }
                out.add(headerValues.get(i));
            }
        }
        return out == null ? Collections.<String> emptyList() : out;
    }

    @Override
    public Collection<String> getHeaderNames() {
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String n : headerNames) {
            boolean dup = false;
            for (String s : seen) {
                if (s.equalsIgnoreCase(n)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                seen.add(n);
            }
        }
        return seen;
    }

    @Override
    public long getDateHeader(String name) {
        String v = getHeader(name);
        return v == null ? -1 : HttpDates.parse(v);
    }

    @Override
    public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override
    public long getContentLengthLong() {
        return contentLength;
    }

    @Override
    public String getCharacterEncoding() {
        return Ascii.parameter(getContentType(), "charset");
    }

    @Override
    public List<Cookie> getCookies() {
        return Cookie.parse(getHeaders("Cookie"));
    }

    // ------------------------------------------------------------------ query

    @Override
    public String getQueryParameter(String name) {
        List<String> v = getQueryParameterMap().get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    @Override
    public List<String> getQueryParameterValues(String name) {
        List<String> v = getQueryParameterMap().get(name);
        return v == null ? Collections.<String> emptyList() : v;
    }

    @Override
    public Map<String, List<String>> getQueryParameterMap() {
        if (query == null) {
            query = Collections.unmodifiableMap(PercentCodec.parseForm(queryString, Ascii.UTF8));
        }
        return query;
    }

    // ------------------------------------------------------------------ body

    private void useBody(int mode) {
        if (bodyMode != BODY_UNUSED && bodyMode != mode) {
            throw new IllegalStateException("the request body was already read another way");
        }
        bodyMode = mode;
    }

    @Override
    public InputStream getInputStream() {
        useBody(BODY_STREAM);
        return body;
    }

    @Override
    public Map<String, List<String>> readForm(int maxBytes) throws IOException, HttpException {
        useBody(BODY_FORM);
        if (!"application/x-www-form-urlencoded".equals(Ascii.mediaType(getContentType()))) {
            throw new HttpException(415, "expected application/x-www-form-urlencoded");
        }
        if (contentLength > maxBytes) {
            conn.mustClose = true;
            throw new HttpException(413, "form larger than " + maxBytes + " bytes");
        }
        byte[] buf = new byte[contentLength >= 0 ? (int) contentLength : Math.min(maxBytes, 1024)];
        int len = 0;
        while (true) {
            if (len == buf.length) {
                if (len >= maxBytes) {
                    if (body.read() < 0) {
                        break;
                    }
                    conn.mustClose = true;
                    throw new HttpException(413, "form larger than " + maxBytes + " bytes");
                }
                buf = java.util.Arrays.copyOf(buf, (int) Math.min((long) maxBytes, buf.length * 2L + 1));
            }
            int n = body.read(buf, len, buf.length - len);
            if (n < 0) {
                break;
            }
            len += n;
        }
        Charset cs = Ascii.UTF8;
        String enc = getCharacterEncoding();
        if (enc != null) {
            try {
                cs = Charset.forName(enc);
            } catch (RuntimeException e) {
                throw new HttpException(415, "unsupported charset " + enc);
            }
        }
        return PercentCodec.parseForm(new String(buf, 0, len, Ascii.LATIN1), cs);
    }

    @Override
    public MultipartReader readMultipart(MultipartLimits limits) throws IOException, HttpException {
        useBody(BODY_MULTIPART);
        return Multipart.open(this, limits);
    }

    // ------------------------------------------------------------------ connection

    @Override
    public String getRemoteAddr() {
        return conn.socket.getInetAddress().getHostAddress();
    }

    @Override
    public int getRemotePort() {
        return conn.socket.getPort();
    }

    @Override
    public String getLocalAddr() {
        return conn.socket.getLocalAddress().getHostAddress();
    }

    @Override
    public int getLocalPort() {
        Socket s = conn.socket;
        return s.getLocalPort();
    }

    // ------------------------------------------------------------------ attributes

    @Override
    public Object getAttribute(String name) {
        return attributes == null ? null : attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            removeAttribute(name);
            return;
        }
        if (attributes == null) {
            attributes = new HashMap<String, Object>(4);
        }
        attributes.put(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        if (attributes != null) {
            attributes.remove(name);
        }
    }

    @Override
    public String toString() {
        InetSocketAddress remote = (InetSocketAddress) conn.socket.getRemoteSocketAddress();
        return method + " " + requestUri + (queryString != null ? "?" + queryString : "") + " from " + remote;
    }
}
