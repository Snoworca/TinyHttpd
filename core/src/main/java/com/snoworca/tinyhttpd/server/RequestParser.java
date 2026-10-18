package com.snoworca.tinyhttpd.server;

import java.io.IOException;
import java.util.List;

import com.snoworca.tinyhttpd.ServerConfig;

/** Parses the request line and header (RFC 9112 §2-§6) and chooses the body framing. */
final class RequestParser {

    private RequestParser() {
    }

    static void parse(Connection conn, Exchange ex) throws IOException, ParseError {
        ServerConfig cfg = conn.server.config;
        InputBuffer in = conn.in;
        String line = in.readLine(cfg.maxRequestLineBytes, 414);
        if (line.isEmpty()) {
            line = in.readLine(cfg.maxRequestLineBytes, 414); // one empty line before the request is tolerated
        }
        parseRequestLine(line, ex);
        parseHeaders(in, ex, cfg);
        List<String> hosts = ex.getHeaders("Host");
        if (hosts.size() > 1 || hosts.isEmpty() && ex.isHttp11()) {
            throw new ParseError(400, hosts.isEmpty() ? "missing Host" : "several Host headers");
        }
        if (!hosts.isEmpty() && !validHost(hosts.get(0))) {
            throw new ParseError(400, "invalid Host");
        }
        if (ex.path == null) {
            throw new ParseError(400, "invalid path");
        }
        chooseFraming(conn, ex, cfg);
        List<String> expect = ex.getHeaders("Expect");
        if (ex.isHttp11() && !expect.isEmpty()) {
            if (expect.size() != 1 || !"100-continue".equalsIgnoreCase(expect.get(0).trim())) {
                throw new ParseError(417, "unsupported expectation");
            }
            ex.expectContinue = ex.contentLength != 0;
        }
    }

    static void parseRequestLine(String line, Exchange ex) throws ParseError {
        int sp1 = line.indexOf(' ');
        int sp2 = sp1 < 0 ? -1 : line.indexOf(' ', sp1 + 1);
        if (sp1 <= 0 || sp2 < 0 || sp2 == sp1 + 1 || line.indexOf(' ', sp2 + 1) >= 0) {
            throw new ParseError(400, "malformed request line");
        }
        String method = line.substring(0, sp1);
        String target = line.substring(sp1 + 1, sp2);
        String version = line.substring(sp2 + 1);
        if (!Ascii.isToken(method)) {
            throw new ParseError(400, "invalid method");
        }
        ex.method = method;
        parseVersion(version, ex);
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '#') {
                throw new ParseError(400, "invalid character in request target");
            }
        }
        if (target.charAt(0) == '/') {
            setOriginForm(target, ex);
        } else if ("*".equals(target)) {
            if (!"OPTIONS".equals(method)) {
                throw new ParseError(400, "asterisk-form is only for OPTIONS");
            }
            ex.requestUri = "*";
            ex.path = "*";
        } else if (startsWithIgnoreCase(target, "http://") || startsWithIgnoreCase(target, "https://")) {
            int hostStart = target.indexOf("//") + 2;
            int slash = target.indexOf('/', hostStart);
            int q = target.indexOf('?', hostStart);
            int hostEnd = slash < 0 ? (q < 0 ? target.length() : q) : (q >= 0 && q < slash ? q : slash);
            String host = target.substring(hostStart, hostEnd);
            if (host.isEmpty() || host.indexOf('@') >= 0) {
                throw new ParseError(400, "invalid authority");
            }
            ex.targetHost = host;
            String rest = target.substring(hostEnd);
            setOriginForm(rest.isEmpty() ? "/" : rest.charAt(0) == '?' ? "/" + rest : rest, ex);
        } else if ("CONNECT".equals(method)) {
            throw new ParseError(501, "CONNECT is not supported");
        } else {
            throw new ParseError(400, "invalid request target");
        }
    }

    private static void setOriginForm(String target, Exchange ex) {
        int q = target.indexOf('?');
        ex.requestUri = q < 0 ? target : target.substring(0, q);
        ex.queryString = q < 0 ? null : target.substring(q + 1);
        ex.path = PercentCodec.decodePath(ex.requestUri);
    }

    private static void parseVersion(String v, Exchange ex) throws ParseError {
        if (v.length() != 8 || !v.startsWith("HTTP/") || v.charAt(6) != '.' || !isDigit(v.charAt(5))
                || !isDigit(v.charAt(7))) {
            throw new ParseError(400, "malformed version");
        }
        if (v.charAt(5) != '1') {
            throw new ParseError(505, "unsupported version");
        }
        ex.minor = v.charAt(7) == '0' ? 0 : 1;
    }

    /** RFC 9112 3.2: {@code uri-host [":" port]}, where uri-host is an IP-literal or a reg-name (RFC 3986 3.2.2). */
    static boolean validHost(String h) {
        int i = 0;
        int n = h.length();
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            if (close < 2) {
                return false;
            }
            for (i = 1; i < close; i++) {
                char c = h.charAt(i);
                if (!(Ascii.isHexDigit(c) || c == ':' || c == '.')) {
                    return false;
                }
            }
            i = close + 1;
        } else {
            for (; i < n && h.charAt(i) != ':'; i++) {
                char c = h.charAt(i);
                if (c == '%') {
                    if (i + 2 >= n || !Ascii.isHexDigit(h.charAt(i + 1)) || !Ascii.isHexDigit(h.charAt(i + 2))) {
                        return false;
                    }
                    i += 2;
                } else if (!(Ascii.isAlphaNum(c) || "-._~!$&'()*+,;=".indexOf(c) >= 0)) {
                    return false;
                }
            }
        }
        if (i == n) {
            return true;
        }
        if (h.charAt(i) != ':') {
            return false;
        }
        for (i++; i < n; i++) {
            if (h.charAt(i) < '0' || h.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static void parseHeaders(InputBuffer in, Exchange ex, ServerConfig cfg) throws IOException, ParseError {
        int total = 0;
        int lineMax = in.buf.length - 2;
        while (true) {
            String line = in.readLine(lineMax, 431);
            if (line.isEmpty()) {
                return;
            }
            total += line.length() + 2;
            if (total > cfg.maxHeaderBytes || ex.headerNames.size() >= cfg.maxHeaderCount) {
                throw new ParseError(431, "header too large");
            }
            char first = line.charAt(0);
            if (first == ' ' || first == '\t') {
                throw new ParseError(400, "obsolete line folding");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new ParseError(400, "malformed header line");
            }
            for (int i = 0; i < colon; i++) {
                if (!Ascii.isTchar(line.charAt(i))) {
                    throw new ParseError(400, "invalid header name");
                }
            }
            int vs = colon + 1;
            int ve = line.length();
            while (vs < ve && isOws(line.charAt(vs))) {
                vs++;
            }
            while (ve > vs && isOws(line.charAt(ve - 1))) {
                ve--;
            }
            for (int i = vs; i < ve; i++) {
                char c = line.charAt(i);
                if (c == 0 || c == '\r' || c == '\n') {
                    throw new ParseError(400, "invalid header value");
                }
            }
            ex.headerNames.add(line.substring(0, colon));
            ex.headerValues.add(line.substring(vs, ve));
        }
    }

    /** Body framing in the order of RFC 9112 §6.3. */
    private static void chooseFraming(Connection conn, Exchange ex, ServerConfig cfg) throws ParseError {
        List<String> te = ex.getHeaders("Transfer-Encoding");
        List<String> cl = ex.getHeaders("Content-Length");
        if (!te.isEmpty()) {
            if (!ex.isHttp11()) {
                throw new ParseError(400, "Transfer-Encoding in HTTP/1.0");
            }
            if (!cl.isEmpty()) {
                throw new ParseError(400, "both Transfer-Encoding and Content-Length");
            }
            StringBuilder all = new StringBuilder();
            for (String v : te) {
                all.append(all.length() == 0 ? "" : ",").append(v);
            }
            String[] codings = all.toString().split(",");
            String last = codings[codings.length - 1].trim();
            if (!"chunked".equalsIgnoreCase(last)) {
                throw new ParseError(400, "last transfer coding is not chunked");
            }
            if (codings.length > 1) {
                throw new ParseError(501, "unsupported transfer coding");
            }
            ex.contentLength = -1;
            ex.body = new BodyInput.Chunked(conn, cfg.getMaxRequestBodyBytes(), cfg.maxChunkSizeLineBytes,
                    cfg.maxTrailerBytes);
        } else if (!cl.isEmpty()) {
            long length = -1;
            for (String v : cl) {
                for (String part : v.split(",", -1)) {
                    long n = parseLength(part.trim());
                    if (length >= 0 && n != length) {
                        throw new ParseError(400, "conflicting Content-Length");
                    }
                    length = n;
                }
            }
            long max = cfg.getMaxRequestBodyBytes();
            if (max >= 0 && length > max) {
                throw new ParseError(413, "body too large");
            }
            ex.contentLength = length;
            ex.body = length == 0 ? new BodyInput.Empty(conn)
                    : new BodyInput.Bounded(conn, length, max);
        } else {
            ex.contentLength = 0;
            ex.body = new BodyInput.Empty(conn);
        }
        ex.body.attach(ex);
    }

    private static long parseLength(String s) throws ParseError {
        if (s.isEmpty() || s.length() > 18) {
            throw new ParseError(400, "invalid Content-Length");
        }
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isDigit(c)) {
                throw new ParseError(400, "invalid Content-Length");
            }
            n = n * 10 + (c - '0');
        }
        return n;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isOws(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
