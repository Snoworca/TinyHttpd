package com.snoworca.tinyhttpd.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * One fixed buffer per connection. Lines are read in place, so one line is limited to {@code buf.length - 2}.
 * Bytes left after a request stay for the next one (pipelining) or for an upgraded protocol.
 */
final class InputBuffer {

    final byte[] buf;

    int pos;

    int lim;

    private final InputStream in;

    private final Connection conn;

    InputBuffer(InputStream in, int size, Connection conn) {
        this.in = in;
        this.buf = new byte[size];
        this.conn = conn;
    }

    boolean hasBuffered() {
        return pos < lim;
    }

    int buffered() {
        return lim - pos;
    }

    /** Reads more bytes after the buffered ones. @return bytes read, or -1 at EOF */
    int fill() throws IOException {
        if (pos == lim) {
            pos = 0;
            lim = 0;
        } else if (lim == buf.length) {
            System.arraycopy(buf, pos, buf, 0, lim - pos);
            lim -= pos;
            pos = 0;
        }
        conn.beforeRead();
        int n = in.read(buf, lim, buf.length - lim);
        if (n > 0) {
            lim += n;
        }
        return n;
    }

    /**
     * Reads one line ending in CRLF or a bare LF. A bare CR inside the line is an error.
     *
     * @param max longest line content
     * @param tooLong status when the line is longer
     * @return the line without its terminator, decoded as ISO-8859-1
     * @throws EOFException if the stream ends inside the line
     */
    String readLine(int max, int tooLong) throws IOException, ParseError {
        return readLine(max, tooLong, false);
    }

    /** Like {@link #readLine(int, int)}; with {@code crlfOnly} a bare LF is an error (chunked framing, RFC 9112 7.1). */
    String readLine(int max, int tooLong, boolean crlfOnly) throws IOException, ParseError {
        int scanned = 0;
        while (true) {
            for (int i = pos + scanned; i < lim; i++) {
                if (buf[i] == '\n') {
                    int end = i > pos && buf[i - 1] == '\r' ? i - 1 : i;
                    if (crlfOnly && end == i) {
                        throw new ParseError(400, "bare LF");
                    }
                    if (end - pos > max) {
                        throw new ParseError(tooLong, "line too long");
                    }
                    for (int j = pos; j < end; j++) {
                        if (buf[j] == '\r') {
                            throw new ParseError(400, "bare CR");
                        }
                    }
                    String s = new String(buf, pos, end - pos, Ascii.LATIN1);
                    pos = i + 1;
                    return s;
                }
            }
            scanned = lim - pos;
            if (scanned > max + 1 || scanned == buf.length) {
                throw new ParseError(tooLong, "line too long");
            }
            if (fill() < 0) {
                throw new EOFException("connection closed inside a line");
            }
        }
    }

    int read() throws IOException {
        if (pos == lim && fill() <= 0) {
            return -1;
        }
        return buf[pos++] & 0xff;
    }

    /** Reads buffered bytes first; large reads with an empty buffer go straight to the socket. */
    int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (pos == lim) {
            if (len >= buf.length) {
                conn.beforeRead();
                return in.read(b, off, len);
            }
            if (fill() <= 0) {
                return -1;
            }
        }
        int n = Math.min(len, lim - pos);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    /** Discards up to {@code max} bytes. @return bytes skipped, or -1 at EOF */
    long skip(long max) throws IOException {
        if (pos == lim && fill() <= 0) {
            return -1;
        }
        int n = (int) Math.min(max, lim - pos);
        pos += n;
        return n;
    }

    int available() throws IOException {
        return (lim - pos) + in.available();
    }

    InputStream raw() {
        return in;
    }
}
