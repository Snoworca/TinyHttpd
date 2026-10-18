package com.snoworca.tinyhttpd.server;

import java.io.IOException;
import java.io.InputStream;

import com.snoworca.tinyhttpd.http.BodyTooLargeException;
import com.snoworca.tinyhttpd.http.MalformedBodyException;
import com.snoworca.tinyhttpd.http.TruncatedBodyException;

/**
 * The request body as framed by Content-Length or chunked coding. Reads end at the end of the body and never touch
 * the next request. {@code close()} does not close the connection; the rest is drained after the response.
 */
abstract class BodyInput extends InputStream {

    final Connection conn;

    final InputBuffer in;

    final long limit;

    long consumed;

    boolean complete;

    /** Set when the body failed; further reads repeat the failure. */
    IOException failure;

    private Exchange exchange;

    BodyInput(Connection conn, long limit) {
        this.conn = conn;
        this.in = conn.in;
        this.limit = limit;
    }

    void attach(Exchange exchange) {
        this.exchange = exchange;
    }

    /** @return bytes into b, or -1 at the end of the body */
    abstract int read0(byte[] b, int off, int len) throws IOException;

    /** @return remaining length if known, else -1 */
    abstract long remaining();

    @Override
    public final int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public final int read(byte[] b, int off, int len) throws IOException {
        if (failure != null) {
            throw failure;
        }
        if (complete) {
            return -1;
        }
        if (len == 0) {
            return 0;
        }
        if (exchange != null) {
            exchange.beforeBodyRead();
        }
        try {
            int n = read0(b, off, len);
            if (n < 0) {
                complete = true;
                return -1;
            }
            consumed += n;
            if (limit >= 0 && consumed > limit) {
                throw fail(new BodyTooLargeException("request body larger than " + limit + " bytes"));
            }
            return n;
        } catch (java.net.SocketTimeoutException e) {
            conn.server.bodyReadTimeouts.incrementAndGet();
            throw fail(e);
        } catch (IOException e) {
            throw fail(e);
        }
    }

    final IOException fail(IOException e) {
        failure = e;
        conn.mustClose = true;
        return e;
    }

    @Override
    public int available() throws IOException {
        if (complete || failure != null) {
            return 0;
        }
        long r = remaining();
        int buffered = in.buffered();
        return r < 0 ? 0 : (int) Math.min(r, buffered);
    }

    @Override
    public void close() {
        // the connection stays open; unread bytes are drained after the response
    }

    /** Discards up to {@code max} remaining bytes. @return true if the body ended within the budget */
    boolean drain(long max) {
        if (complete) {
            return true;
        }
        if (failure != null || (exchange != null && !exchange.mayReadBody())) {
            return false;
        }
        long r = remaining();
        if (r > max) {
            return false;
        }
        byte[] scratch = new byte[(int) Math.min(4096, max + 1)];
        long budget = max;
        long deadline = System.currentTimeMillis() + conn.cfg.getBodyReadTimeout();
        try {
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    return false; // a client trickling the rest of the body must not hold the worker
                }
                int n = read(scratch, 0, (int) Math.min(scratch.length, budget + 1));
                if (n < 0) {
                    return true;
                }
                budget -= n;
                if (budget < 0) {
                    return false;
                }
            }
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ framings

    /** No body. */
    static final class Empty extends BodyInput {

        Empty(Connection conn) {
            super(conn, -1);
            complete = true;
        }

        @Override
        int read0(byte[] b, int off, int len) {
            return -1;
        }

        @Override
        long remaining() {
            return 0;
        }
    }

    /** Exactly {@code length} bytes. */
    static final class Bounded extends BodyInput {

        private long left;

        Bounded(Connection conn, long length, long limit) {
            super(conn, limit);
            this.left = length;
            this.complete = length == 0;
        }

        @Override
        int read0(byte[] b, int off, int len) throws IOException {
            if (left == 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) {
                throw new TruncatedBodyException("client closed with " + left + " body bytes missing");
            }
            left -= n;
            if (left == 0) {
                complete = true;
            }
            return n;
        }

        @Override
        long remaining() {
            return left;
        }
    }

    /** Chunked coding (RFC 9112 §7.1); extensions are ignored, trailers discarded. */
    static final class Chunked extends BodyInput {

        private final int maxSizeLine;

        private final int maxTrailer;

        /** Bytes left in the current chunk; -1 before the first size line. */
        private long chunkLeft = -1;

        private boolean ended;

        Chunked(Connection conn, long limit, int maxSizeLine, int maxTrailer) {
            super(conn, limit);
            this.maxSizeLine = maxSizeLine;
            this.maxTrailer = maxTrailer;
        }

        @Override
        int read0(byte[] b, int off, int len) throws IOException {
            if (ended) {
                return -1;
            }
            if (chunkLeft <= 0) {
                if (chunkLeft == 0) {
                    expectCrlf();
                }
                chunkLeft = readSize();
                if (chunkLeft == 0) {
                    readTrailer();
                    ended = true;
                    complete = true;
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, chunkLeft));
            if (n < 0) {
                throw new TruncatedBodyException("client closed inside a chunk");
            }
            chunkLeft -= n;
            return n;
        }

        private String line(int max) throws IOException {
            try {
                return in.readLine(max, 400);
            } catch (ParseError e) {
                throw new MalformedBodyException("chunked: " + e.getMessage());
            } catch (java.io.EOFException e) {
                throw new TruncatedBodyException("client closed inside chunked framing");
            }
        }

        private void expectCrlf() throws IOException {
            if (!line(0).isEmpty()) {
                throw new MalformedBodyException("chunk data not followed by CRLF");
            }
        }

        private long readSize() throws IOException {
            String s = line(maxSizeLine);
            int end = s.indexOf(';');
            if (end < 0) {
                end = s.length();
            }
            while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
                end--;
            }
            int start = 0;
            while (start < end - 1 && s.charAt(start) == '0') {
                start++;
            }
            if (end == 0 || end - start > 15) {
                throw new MalformedBodyException("bad chunk size");
            }
            long size = 0;
            for (int i = start; i < end; i++) {
                int d = Character.digit(s.charAt(i), 16);
                if (d < 0) {
                    throw new MalformedBodyException("bad chunk size");
                }
                size = size << 4 | d;
            }
            return size;
        }

        private void readTrailer() throws IOException {
            int total = 0;
            while (true) {
                String l = line(in.buf.length - 2);
                if (l.isEmpty()) {
                    return;
                }
                total += l.length() + 2;
                if (total > maxTrailer) {
                    throw new MalformedBodyException("trailer too large");
                }
            }
        }

        @Override
        long remaining() {
            return ended ? 0 : -1;
        }
    }
}
