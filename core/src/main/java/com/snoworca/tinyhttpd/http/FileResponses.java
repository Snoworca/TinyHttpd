package com.snoworca.tinyhttpd.http;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;

import com.snoworca.tinyhttpd.server.HttpDates;
import com.snoworca.tinyhttpd.server.PercentCodec;

/**
 * Serves files correctly: strong validators, conditional requests (RFC 9110 §13.2.2), single byte ranges and
 * {@code Content-Disposition} file names (RFC 6266).
 */
public final class FileResponses {

    /** Returned by {@link #parseRange} when the range cannot be satisfied (416). */
    public static final long[] UNSATISFIABLE = new long[0];

    private FileResponses() {
    }

    /**
     * Serves a file with {@link #serve(Request, Response, FileResource, String)}.
     *
     * @param req request
     * @param res response
     * @param file the file
     * @param contentType media type; null guesses from the extension
     * @throws IOException on failure
     */
    public static void serve(Request req, Response res, File file, String contentType) throws IOException {
        serve(req, res, FileResource.of(file), contentType);
    }

    /**
     * Answers GET or HEAD for a resource: 200, 206, 304, 412 or 416, with {@code ETag}, {@code Last-Modified},
     * {@code Accept-Ranges: bytes} and {@code X-Content-Type-Options: nosniff}.
     *
     * @param req request
     * @param res response, not committed
     * @param resource the resource
     * @param contentType media type; null guesses from the name
     * @throws IOException on failure
     */
    public static void serve(Request req, Response res, FileResource resource, String contentType) throws IOException {
        long length = resource.length();
        long modified = resource.lastModified();
        String etag = etag(length, modified);
        res.setHeader("Accept-Ranges", "bytes");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("ETag", etag);
        if (modified > 0) {
            res.setDateHeader("Last-Modified", modified);
        }
        int pre = evaluatePreconditions(req, etag, modified);
        if (pre != 0) {
            res.setStatus(pre);
            res.setContentLengthLong(0);
            return;
        }
        res.setContentType(contentType != null ? contentType
                : MimeTypes.get(resource.getName(), "application/octet-stream"));
        long start = 0;
        long count = length;
        String range = req.getHeader("Range");
        if (range != null && "GET".equals(req.getMethod()) && ifRangeMatches(req, etag, modified)) {
            long[] r = parseRange(range, length);
            if (r == UNSATISFIABLE) {
                res.setStatus(416);
                res.setHeader("Content-Range", "bytes */" + length);
                res.setContentLengthLong(0);
                return;
            }
            if (r != null) {
                start = r[0];
                count = r[1] - r[0] + 1;
                res.setStatus(206);
                res.setHeader("Content-Range", "bytes " + r[0] + "-" + r[1] + "/" + length);
            }
        }
        FileChannel ch = resource.openChannel();
        try {
            res.sendFile(ch, start, count);
        } finally {
            ch.close();
        }
    }

    /**
     * @param length size
     * @param modified modification time in milliseconds
     * @return the strong ETag {@code "<size hex>-<mtime ms hex>"}
     */
    public static String etag(long length, long modified) {
        return "\"" + Long.toHexString(length) + "-" + Long.toHexString(modified) + "\"";
    }

    /**
     * Evaluates {@code If-Match}, {@code If-Unmodified-Since}, {@code If-None-Match} and {@code If-Modified-Since} in
     * the order of RFC 9110 §13.2.2.
     *
     * @param req request
     * @param etag current strong ETag
     * @param modified modification time in milliseconds, 0 if unknown
     * @return 0 to proceed, 304 or 412
     */
    public static int evaluatePreconditions(Request req, String etag, long modified) {
        boolean getOrHead = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
        List<String> ifMatch = req.getHeaders("If-Match");
        if (!ifMatch.isEmpty()) {
            if (!matches(ifMatch, etag, true)) {
                return 412;
            }
        } else {
            long since = date(req, "If-Unmodified-Since");
            if (since >= 0 && modified > 0 && modified / 1000 > since / 1000) {
                return 412;
            }
        }
        List<String> ifNoneMatch = req.getHeaders("If-None-Match");
        if (!ifNoneMatch.isEmpty()) {
            if (matches(ifNoneMatch, etag, false)) {
                return getOrHead ? 304 : 412;
            }
        } else if (getOrHead) {
            long since = date(req, "If-Modified-Since");
            if (since >= 0 && modified > 0 && modified / 1000 <= since / 1000) {
                return 304;
            }
        }
        return 0;
    }

    private static long date(Request req, String name) {
        try {
            return req.getDateHeader(name);
        } catch (IllegalArgumentException invalid) {
            return -1; // an invalid date is ignored (RFC 9110 §13.1.3)
        }
    }

    private static boolean matches(List<String> headers, String etag, boolean strong) {
        for (String h : headers) {
            for (String tag : h.split(",")) {
                String t = tag.trim();
                if (t.equals("*")) {
                    return true;
                }
                if (t.startsWith("W/")) {
                    if (strong) {
                        continue;
                    }
                    t = t.substring(2);
                }
                if (t.equals(etag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean ifRangeMatches(Request req, String etag, long modified) {
        String ifRange = req.getHeader("If-Range");
        if (ifRange == null) {
            return true;
        }
        String v = ifRange.trim();
        if (v.startsWith("\"")) {
            return v.equals(etag);
        }
        if (v.startsWith("W/")) {
            return false;
        }
        try {
            return modified > 0 && HttpDates.parse(v) / 1000 == modified / 1000;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Parses one {@code bytes} range.
     *
     * @param header the {@code Range} value
     * @param length resource length
     * @return {first, last} inclusive; null to ignore the header (unsupported or several ranges);
     *         {@link #UNSATISFIABLE} for 416
     */
    public static long[] parseRange(String header, long length) {
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, 6) || h.indexOf(',') >= 0) {
            return null;
        }
        String spec = h.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            if (a.isEmpty()) {
                if (b.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(b);
                if (suffix <= 0 || length == 0) {
                    return UNSATISFIABLE;
                }
                return new long[] {Math.max(0, length - suffix), length - 1};
            }
            long first = Long.parseLong(a);
            long last = b.isEmpty() ? length - 1 : Long.parseLong(b);
            if (first < 0) {
                return null;
            }
            if (first >= length) {
                return UNSATISFIABLE;
            }
            if (last < first) {
                return null;
            }
            return new long[] {first, Math.min(last, length - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Sets {@code Content-Disposition: attachment} with an ASCII fallback name (non-ASCII as {@code _}) and an
     * RFC 8187 {@code filename*}. Quotes, CR, LF and path separators are removed first.
     *
     * @param res response
     * @param fileName download name
     */
    public static void attachment(Response res, String fileName) {
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c != '"' && c != '\r' && c != '\n' && c != '/' && c != '\\' && c >= 0x20 && c != 0x7f) {
                clean.append(c);
            }
        }
        String name = clean.toString();
        StringBuilder ascii = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            ascii.append(c < 0x80 ? c : '_');
        }
        res.setHeader("Content-Disposition",
                "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + PercentCodec.encode(name));
    }
}
