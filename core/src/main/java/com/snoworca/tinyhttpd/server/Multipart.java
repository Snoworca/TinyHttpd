package com.snoworca.tinyhttpd.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.snoworca.tinyhttpd.http.BodyTooLargeException;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MalformedBodyException;
import com.snoworca.tinyhttpd.http.MultipartLimits;
import com.snoworca.tinyhttpd.http.MultipartReader;
import com.snoworca.tinyhttpd.http.Part;

/**
 * Streaming {@code multipart/form-data} parser (RFC 7578). One fixed buffer; the delimiter {@code CRLF--boundary} is
 * found with Horspool's algorithm, keeping a delimiter-length tail when it may straddle two reads. Memory does not
 * depend on the upload size.
 */
final class Multipart implements MultipartReader {

    private final Exchange ex;

    private final InputStream body;

    private final MultipartLimits limits;

    private final byte[] delimiter;

    private final int[] skip = new int[256];

    private final byte[] buf;

    private int pos;

    private int lim;

    private boolean eof;

    private long total;

    private int parts;

    /** The open part's stream, or null between parts. */
    private PartStream current;

    private boolean finished;

    private Multipart(Exchange ex, MultipartLimits limits, String boundary) {
        this.ex = ex;
        this.body = ex.body;
        this.limits = limits;
        this.delimiter = ("\r\n--" + boundary).getBytes(Ascii.LATIN1);
        int bufSize = Math.max(limits.getBufferBytes(), delimiter.length * 4);
        this.buf = new byte[Math.max(bufSize, limits.getMaxPartHeaderBytes() + 4)];
        for (int i = 0; i < 256; i++) {
            skip[i] = delimiter.length;
        }
        for (int i = 0; i < delimiter.length - 1; i++) {
            skip[delimiter[i] & 0xff] = delimiter.length - 1 - i;
        }
        // Pretend the body starts with CRLF so the first boundary matches the same delimiter.
        buf[0] = '\r';
        buf[1] = '\n';
        lim = 2;
    }

    static MultipartReader open(Exchange ex, MultipartLimits limits) throws HttpException {
        String type = ex.getContentType();
        if (!"multipart/form-data".equals(Ascii.mediaType(type))) {
            throw new HttpException(415, "expected multipart/form-data");
        }
        String boundary = Ascii.parameter(type, "boundary");
        if (boundary == null || boundary.isEmpty() || boundary.length() > 70 || boundary.endsWith(" ")) {
            throw new HttpException(400, "missing or invalid boundary");
        }
        return new Multipart(ex, limits, boundary);
    }

    // ------------------------------------------------------------------ buffer

    /** Reads more body bytes, keeping unread ones. @return false at the end of the body */
    private boolean fill() throws IOException {
        if (eof) {
            return false;
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, lim - pos);
            lim -= pos;
            pos = 0;
        }
        if (lim == buf.length) {
            return true;
        }
        int n = body.read(buf, lim, buf.length - lim);
        if (n < 0) {
            eof = true;
            return false;
        }
        total += n;
        if (total > limits.getMaxTotalBytes()) {
            throw tooLarge("multipart body larger than " + limits.getMaxTotalBytes() + " bytes");
        }
        lim += n;
        return true;
    }

    private BodyTooLargeException tooLarge(String message) {
        ex.conn.mustClose = true;
        return new BodyTooLargeException(message);
    }

    /** @return index of the delimiter in buf[pos, lim), or -1 */
    private int find() {
        int m = delimiter.length;
        int i = pos;
        while (i + m <= lim) {
            int j = m - 1;
            while (j >= 0 && buf[i + j] == delimiter[j]) {
                j--;
            }
            if (j < 0) {
                return i;
            }
            i += skip[buf[i + m - 1] & 0xff];
        }
        return -1;
    }

    // ------------------------------------------------------------------ parts

    @Override
    public Part next() throws IOException, HttpException {
        if (finished) {
            return null;
        }
        if (current != null) {
            current.skipRest();
        } else {
            skipPreamble();
        }
        current = null;
        // After a delimiter: "--" ends the body, otherwise optional whitespace and CRLF start a part.
        while (lim - pos < 2) {
            if (!fill()) {
                throw malformed("body ended after a boundary");
            }
        }
        if (buf[pos] == '-' && buf[pos + 1] == '-') {
            finished = true;
            return null;
        }
        String line = readHeaderLine(new int[] {0});
        if (!line.trim().isEmpty()) {
            throw malformed("garbage after boundary");
        }
        if (++parts > limits.getMaxParts()) {
            ex.conn.mustClose = true;
            throw new HttpException(413, "more than " + limits.getMaxParts() + " parts");
        }
        List<String> headers = new ArrayList<String>();
        int[] headerBytes = {0};
        while (true) {
            String h = readHeaderLine(headerBytes);
            if (h.isEmpty()) {
                break;
            }
            headers.add(h);
        }
        current = new PartStream();
        return new FormPart(headers, current);
    }

    private void skipPreamble() throws IOException {
        while (true) {
            int at = find();
            if (at >= 0) {
                pos = at + delimiter.length;
                return;
            }
            pos = Math.max(pos, lim - delimiter.length + 1);
            if (!fill()) {
                throw malformed("no boundary in body");
            }
        }
    }

    /** Reads one CRLF-terminated part header line as UTF-8. */
    private String readHeaderLine(int[] count) throws IOException, HttpException {
        while (true) {
            for (int i = pos; i + 1 < lim; i++) {
                if (buf[i] == '\r' && buf[i + 1] == '\n') {
                    int len = i - pos;
                    count[0] += len + 2;
                    if (count[0] > limits.getMaxPartHeaderBytes()) {
                        throw partHeaderTooLarge();
                    }
                    String s = new String(buf, pos, len, Ascii.UTF8);
                    pos = i + 2;
                    return s;
                }
            }
            if (count[0] + lim - pos > limits.getMaxPartHeaderBytes()) {
                throw partHeaderTooLarge();
            }
            if (!fill()) {
                throw malformed("body ended inside part headers");
            }
        }
    }

    private HttpException partHeaderTooLarge() {
        ex.conn.mustClose = true;
        return new HttpException(413, "part header larger than " + limits.getMaxPartHeaderBytes() + " bytes");
    }

    private MalformedBodyException malformed(String message) {
        ex.conn.mustClose = true;
        return new MalformedBodyException("multipart: " + message);
    }

    @Override
    public void close() throws IOException {
        current = null;
    }

    /** The content of one part, ending before the next delimiter. */
    private final class PartStream extends InputStream {

        private long size;

        private boolean done;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done || current != this) {
                return -1;
            }
            if (len == 0) {
                return 0;
            }
            while (true) {
                int at = find();
                int available = at >= 0 ? at - pos : lim - pos - (delimiter.length - 1);
                if (available > 0) {
                    int n = Math.min(len, available);
                    System.arraycopy(buf, pos, b, off, n);
                    pos += n;
                    size += n;
                    if (size > limits.getMaxPartBytes()) {
                        throw tooLarge("part larger than " + limits.getMaxPartBytes() + " bytes");
                    }
                    return n;
                }
                if (at >= 0) {
                    pos = at + delimiter.length;
                    done = true;
                    return -1;
                }
                if (!fill()) {
                    throw malformed("body ended without a closing boundary");
                }
            }
        }

        void skipRest() throws IOException {
            byte[] scratch = new byte[4096];
            while (read(scratch, 0, scratch.length) >= 0) {
                // discard the rest of the part
            }
        }
    }

    /** A part's headers and content. */
    private static final class FormPart implements Part {

        private final List<String> headers;

        private final PartStream stream;

        private String name;

        private String fileName;

        FormPart(List<String> headers, PartStream stream) {
            this.headers = headers;
            this.stream = stream;
            String cd = getHeader("Content-Disposition");
            if (cd != null) {
                String star = null;
                for (String[] p : dispositionParams(cd)) {
                    if (p[0].equals("name")) {
                        name = p[1];
                    } else if (p[0].equals("filename")) {
                        fileName = p[1];
                    } else if (p[0].equals("filename*")) {
                        star = decodeExtValue(p[1]);
                    }
                }
                if (star != null) {
                    fileName = star;
                }
            }
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getSubmittedFileName() {
            return sanitizeFileName(fileName);
        }

        @Override
        public String getContentType() {
            return getHeader("Content-Type");
        }

        @Override
        public String getHeader(String n) {
            for (String h : headers) {
                int colon = h.indexOf(':');
                if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase(n)) {
                    return h.substring(colon + 1).trim();
                }
            }
            return null;
        }

        @Override
        public InputStream getInputStream() {
            return stream;
        }

        @Override
        public String readString(int maxBytes) throws IOException, HttpException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[Math.min(4096, maxBytes + 1)];
            int n;
            while ((n = stream.read(b, 0, b.length)) >= 0) {
                out.write(b, 0, n);
                if (out.size() > maxBytes) {
                    throw new HttpException(413, "field " + name + " larger than " + maxBytes + " bytes");
                }
            }
            String cs = Ascii.parameter(getContentType(), "charset");
            Charset charset = Ascii.UTF8;
            if (cs != null) {
                try {
                    charset = Charset.forName(cs);
                } catch (RuntimeException e) {
                    throw new HttpException(415, "unsupported charset " + cs);
                }
            }
            return new String(out.toByteArray(), charset);
        }
    }

    /** Parameters of a Content-Disposition value; quoted values undo the browsers' %22, %0D, %0A escapes. */
    static List<String[]> dispositionParams(String value) {
        List<String[]> out = new ArrayList<String[]>();
        int i = value.indexOf(';');
        while (i >= 0 && i < value.length()) {
            i++;
            int eq = value.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            String key = value.substring(i, eq).trim().toLowerCase(Locale.US);
            int j = eq + 1;
            while (j < value.length() && value.charAt(j) == ' ') {
                j++;
            }
            String v;
            if (j < value.length() && value.charAt(j) == '"') {
                StringBuilder sb = new StringBuilder();
                j++;
                while (j < value.length() && value.charAt(j) != '"') {
                    sb.append(value.charAt(j)); // browsers percent-escape quotes; a backslash is a literal
                    j++;
                }
                v = sb.toString().replace("%22", "\"").replace("%0D", "\r").replace("%0A", "\n");
                i = value.indexOf(';', j);
            } else {
                int semi = value.indexOf(';', j);
                v = (semi < 0 ? value.substring(j) : value.substring(j, semi)).trim();
                i = semi;
            }
            out.add(new String[] {key, v});
        }
        return out;
    }

    /** RFC 8187 {@code charset'lang'pct-encoded}; null if malformed. */
    static String decodeExtValue(String v) {
        int q1 = v.indexOf('\'');
        int q2 = q1 < 0 ? -1 : v.indexOf('\'', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        Charset cs;
        try {
            cs = Charset.forName(v.substring(0, q1));
        } catch (RuntimeException e) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String s = v.substring(q2 + 1);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0
                    && Character.digit(s.charAt(i + 2), 16) >= 0) {
                bytes.write(Character.digit(s.charAt(i + 1), 16) << 4 | Character.digit(s.charAt(i + 2), 16));
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        return new String(bytes.toByteArray(), cs);
    }

    /** Drops directories, control characters and NUL; null for empty, {@code .} and {@code ..}. */
    static String sanitizeFileName(String name) {
        if (name == null) {
            return null;
        }
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = name.substring(cut + 1);
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() || s.equals(".") || s.equals("..") ? null : s;
    }
}
