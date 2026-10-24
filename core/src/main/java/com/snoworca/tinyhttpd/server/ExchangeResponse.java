package com.snoworca.tinyhttpd.server;

import java.io.File;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.logging.Level;

import com.snoworca.tinyhttpd.http.ContentLengthExceededException;
import com.snoworca.tinyhttpd.http.Cookie;
import com.snoworca.tinyhttpd.http.EventStream;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.HttpStatus;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.UpgradedConnection;

/**
 * The response of one exchange. Framing is decided in one place, {@link #commit}, when the header is first written
 * (RFC 9112 §6.3 and §9). The output buffer is borrowed from a shared pool for the length of the response.
 */
final class ExchangeResponse implements Response {

    /** Room before the data for a chunk size line (8 hex digits + CRLF). */
    static final int HEAD_RESERVE = 10;

    /** Room after the data for CRLF and the last chunk {@code 0\r\n\r\n}. */
    static final int TAIL_RESERVE = 7;

    private static final int MODE_NONE = 0, MODE_STREAM = 1, MODE_WRITER = 2;

    final Connection conn;

    final Exchange ex;

    final HttpServer server;

    int status = 200;

    final ArrayList<String> names = new ArrayList<String>(8);

    final ArrayList<String> values = new ArrayList<String>(8);

    long contentLength = -1;

    boolean closeRequested;

    /** The handler set {@code Connection: Upgrade}; sent only beside an {@code Upgrade} header of a non-101 response. */
    private boolean upgradeToken;

    private int mode = MODE_NONE;

    private Writer writer;

    final Body body = new Body();

    // framing, decided at commit
    boolean committed;

    boolean chunked;

    boolean closeDelimited;

    /** Body bytes are counted but not sent: HEAD, 1xx, 204, 205, 304. */
    boolean discard;

    /** Whether the committed header announced a persistent connection. */
    boolean keepAlive;

    /** A socket write failed; the client is gone. */
    boolean broken;

    boolean finished;

    /** FR-HTTP-003: cancellation may be requested by a thread other than the owner. */
    volatile boolean aborted;

    /** After sendError: further writes are ignored. */
    private boolean ignoreWrites;

    /** Set while the writer is flushed at the end, so its flush does not commit a chunked response. */
    private boolean finishing;

    byte[] buf;

    int count;

    long written;

    ExchangeResponse(Connection conn, Exchange ex) {
        this.conn = conn;
        this.ex = ex;
        this.server = conn.server;
        ex.response = this;
    }

    private int capacity() {
        return buf.length - HEAD_RESERVE - TAIL_RESERVE;
    }

    private void ensureBuffer() {
        if (buf == null) {
            buf = server.outputBuffers.borrow();
        }
    }

    /** Returns the output buffer to the pool once the response is done. */
    void releaseBuffer() {
        if (buf != null) {
            server.outputBuffers.release(buf);
            buf = null;
        }
    }

    // ------------------------------------------------------------------ status and headers

    @Override
    public void setStatus(int status) {
        if (status < 200 || status > 999) {
            throw new IllegalArgumentException("status " + status + " is not allowed here");
        }
        if (committed) {
            ignored("setStatus");
            return;
        }
        this.status = status;
    }

    @Override
    public int getStatus() {
        return status;
    }

    private void ignored(String what) {
        if (server.log.isLoggable(Level.FINE)) {
            server.log.log(Level.FINE, what + " after commit ignored: " + ex, null);
        }
    }

    static void checkHeader(String name, String value) {
        if (!Ascii.isToken(name)) {
            throw new IllegalArgumentException("invalid header name: " + name);
        }
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\r' || c == '\n' || c == 0) {
                    throw new IllegalArgumentException("CR, LF or NUL in header " + name);
                }
            }
        }
    }

    /** @return true if the server handles the header itself */
    private boolean managed(String name, String value) {
        if (name.equalsIgnoreCase("Content-Length")) {
            if (value == null) {
                contentLength = -1;
                return true;
            }
            try {
                long n = Long.parseLong(value.trim());
                if (n < 0) {
                    throw new NumberFormatException();
                }
                contentLength = n;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid Content-Length: " + value);
            }
            return true;
        }
        if (name.equalsIgnoreCase("Transfer-Encoding")) {
            throw new IllegalArgumentException("Transfer-Encoding is managed by the server");
        }
        if (name.equalsIgnoreCase("Connection")) {
            boolean close = value != null && Ascii.hasToken(java.util.Collections.singletonList(value), "close");
            boolean upgrade = value != null && Ascii.hasToken(java.util.Collections.singletonList(value), "upgrade");
            if (close) {
                closeRequested = true;
            }
            if (upgrade) {
                upgradeToken = true;
            }
            if (!close && !upgrade) {
                ignored("Connection: " + value);
            }
            return true;
        }
        return name.equalsIgnoreCase("Date") || name.equalsIgnoreCase("Keep-Alive");
    }

    @Override
    public void setHeader(String name, String value) {
        checkHeader(name, value);
        if (committed) {
            ignored("setHeader " + name);
            return;
        }
        if (managed(name, value)) {
            return;
        }
        removeHeader(name);
        if (value != null) {
            names.add(name);
            values.add(value);
        }
    }

    @Override
    public void addHeader(String name, String value) {
        checkHeader(name, value);
        if (value == null) {
            throw new IllegalArgumentException("null header value");
        }
        if (committed) {
            ignored("addHeader " + name);
            return;
        }
        if (managed(name, value)) {
            return;
        }
        names.add(name);
        values.add(value);
    }

    void removeHeader(String name) {
        for (int i = names.size() - 1; i >= 0; i--) {
            if (names.get(i).equalsIgnoreCase(name)) {
                names.remove(i);
                values.remove(i);
            }
        }
    }

    @Override
    public void setDateHeader(String name, long millis) {
        setHeader(name, HttpDates.format(millis));
    }

    @Override
    public String getHeader(String name) {
        if (name.equalsIgnoreCase("Content-Length")) {
            return contentLength >= 0 ? Long.toString(contentLength) : null;
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return values.get(i);
            }
        }
        return null;
    }

    @Override
    public boolean containsHeader(String name) {
        return getHeader(name) != null;
    }

    @Override
    public void setContentType(String type) {
        setHeader("Content-Type", type);
    }

    @Override
    public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override
    public void setContentLengthLong(long length) {
        if (committed) {
            ignored("setContentLengthLong");
            return;
        }
        if (length < 0) {
            throw new IllegalArgumentException("negative length");
        }
        contentLength = length;
    }

    @Override
    public void addCookie(Cookie cookie) {
        addHeader("Set-Cookie", cookie.toSetCookie());
    }

    @Override
    public void setCloseConnection(boolean close) {
        closeRequested = close;
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public void resetBuffer() {
        if (committed) {
            throw new IllegalStateException("response already committed");
        }
        count = 0;
        written = 0;
    }

    @Override
    public void reset() {
        resetBuffer();
        status = 200;
        names.clear();
        values.clear();
        contentLength = -1;
    }

    // ------------------------------------------------------------------ body

    @Override
    public OutputStream getOutputStream() {
        if (mode == MODE_WRITER) {
            throw new IllegalStateException("getWriter() was already called");
        }
        mode = MODE_STREAM;
        return body;
    }

    @Override
    public Writer getWriter() {
        if (mode == MODE_STREAM) {
            throw new IllegalStateException("getOutputStream() was already called");
        }
        if (writer == null) {
            mode = MODE_WRITER;
            String type = getContentType();
            String charset = Ascii.parameter(type, "charset");
            Charset cs;
            if (charset == null) {
                cs = Ascii.UTF8;
                if (!committed) {
                    setContentType((type == null ? "text/plain" : type) + "; charset=UTF-8");
                }
            } else {
                cs = Charset.forName(charset);
            }
            writer = new FilterWriter(new OutputStreamWriter(body, cs)) {
                @Override
                public void write(int c) throws IOException {
                    checkAborted();
                    super.write(c);
                }

                @Override
                public void write(char[] chars, int off, int len) throws IOException {
                    checkAborted();
                    super.write(chars, off, len);
                }

                @Override
                public void write(String text, int off, int len) throws IOException {
                    checkAborted();
                    super.write(text, off, len);
                }

                @Override
                public void flush() throws IOException {
                    checkAborted();
                    super.flush();
                }
            };
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        } else {
            body.flush();
        }
    }

    @Override
    public void sendError(int status, String message) throws IOException {
        if (committed) {
            throw new IllegalStateException("response already committed");
        }
        sendErrorPage(status, message);
    }

    /** Replaces the body with the error handler's page and ends the response. */
    void sendErrorPage(int status, String message) throws IOException {
        resetBuffer();
        this.status = status;
        removeHeader("Content-Type");
        contentLength = -1;
        int savedMode = mode;
        mode = MODE_NONE;
        Writer savedWriter = writer;
        writer = null;
        try {
            server.config.errorHandler.render(status, message, ex, this);
        } catch (RuntimeException e) {
            fallbackError(e);
        } catch (IOException e) {
            if (broken || committed) {
                throw e;
            }
            fallbackError(e);
        }
        finish();
        ignoreWrites = true;
        mode = savedMode;
        writer = savedWriter;
    }

    private void fallbackError(Exception e) throws IOException {
        server.log.log(Level.SEVERE, "error handler failed", e);
        resetBuffer();
        removeHeader("Content-Type");
        contentLength = -1;
        mode = MODE_NONE;
        writer = null;
        com.snoworca.tinyhttpd.http.ErrorHandler.PLAIN.render(status, null, ex, this);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        if (committed) {
            throw new IllegalStateException("response already committed");
        }
        checkHeader("Location", location);
        resetBuffer();
        status = 302;
        setHeader("Location", location);
        finish();
        ignoreWrites = true;
    }

    @Override
    public void sendFile(File file, long offset, long length) throws IOException {
        FileTransfer.send(this, file, offset, length);
    }

    @Override
    public void sendFile(FileChannel channel, long offset, long length) throws IOException {
        FileTransfer.send(this, channel, offset, length);
    }

    @Override
    public EventStream startEventStream() throws IOException, HttpException {
        return SseStream.start(this);
    }

    @Override
    public UpgradedConnection upgrade(String protocol) throws IOException, HttpException {
        return conn.upgrade(this, protocol);
    }

    // ------------------------------------------------------------------ framing

    /** Whether the handler asked for {@code Connection: Upgrade} and this non-101 response has an {@code Upgrade} header. */
    private boolean offersUpgrade() {
        if (!upgradeToken || status == 101) {
            return false;
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase("Upgrade")) {
                return true;
            }
        }
        return false;
    }

    private boolean bodyless() {
        return status < 200 || status == 204 || status == 205 || status == 304 || ex.isHead();
    }

    /** Chooses framing and the connection header. @return the serialized status line and header */
    private byte[] commit(boolean last) {
        committed = true;
        long lengthHeader = -1;
        if (status < 200 || status == 204 || status == 304) {
            discard = true;
        } else if (status == 205) {
            discard = true;
            lengthHeader = 0;
        } else if (ex.isHead()) {
            discard = true;
            lengthHeader = contentLength >= 0 ? contentLength : last ? count : -1;
        } else if (contentLength >= 0) {
            lengthHeader = contentLength;
        } else if (last) {
            lengthHeader = count;
        } else if (ex.isHttp11()) {
            chunked = true;
        } else {
            closeDelimited = true;
        }
        keepAlive = conn.persistentAtCommit(this);
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(HttpStatus.reason(status)).append("\r\n");
        sb.append("Date: ").append(HttpDates.now()).append("\r\n");
        if (server.config.serverHeader != null) {
            sb.append("Server: ").append(server.config.serverHeader).append("\r\n");
        }
        for (int i = 0; i < names.size(); i++) {
            sb.append(names.get(i)).append(": ").append(values.get(i)).append("\r\n");
        }
        if (lengthHeader >= 0) {
            sb.append("Content-Length: ").append(lengthHeader).append("\r\n");
        } else if (chunked) {
            sb.append("Transfer-Encoding: chunked\r\n");
        }
        // RFC 7230 6.7: a sender of Upgrade also sends Connection: Upgrade, so a 426 offer stays a valid offer
        String upgrade = offersUpgrade() ? "Upgrade, " : "";
        if (!keepAlive) {
            sb.append("Connection: ").append(upgrade).append("close\r\n");
        } else if (!ex.isHttp11()) {
            int idle = server.isUnderLoad() ? server.config.getIdleTimeoutUnderLoad() : server.config.getIdleTimeout();
            sb.append("Connection: ").append(upgrade).append("keep-alive\r\nKeep-Alive: timeout=")
                    .append(Math.max(1, idle / 1000)).append("\r\n");
        } else if (!upgrade.isEmpty()) {
            sb.append("Connection: Upgrade\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(Ascii.UTF8);
    }

    /** Commits if needed and sends the buffered body; {@code last} also ends a chunked body. */
    void flushOut(boolean last) throws IOException {
        checkAborted();
        ensureBuffer();
        byte[] head = committed ? null : commit(last);
        int start = HEAD_RESERVE;
        int end = HEAD_RESERVE + count;
        if (discard) {
            end = start;
        } else if (chunked) {
            if (count > 0) {
                String size = Integer.toHexString(count);
                start -= size.length() + 2;
                for (int i = 0; i < size.length(); i++) {
                    buf[start + i] = (byte) size.charAt(i);
                }
                buf[HEAD_RESERVE - 2] = '\r';
                buf[HEAD_RESERVE - 1] = '\n';
                buf[end++] = '\r';
                buf[end++] = '\n';
            }
            if (last) {
                buf[end++] = '0';
                buf[end++] = '\r';
                buf[end++] = '\n';
                buf[end++] = '\r';
                buf[end++] = '\n';
            }
        }
        count = 0;
        if (head != null) {
            int len = end - start;
            if (head.length + len <= buf.length) {
                System.arraycopy(buf, start, buf, head.length, len);
                System.arraycopy(head, 0, buf, 0, head.length);
                write(buf, 0, head.length + len);
            } else {
                write(head, 0, head.length);
                if (len > 0) {
                    write(buf, start, len);
                }
            }
        } else if (end > start) {
            write(buf, start, end - start);
        }
    }

    void write(byte[] b, int off, int len) throws IOException {
        try {
            conn.write(b, off, len);
        } catch (IOException e) {
            broken = true;
            conn.mustClose = true;
            throw e;
        }
    }

    /** Sends bytes past the output buffer (sendFile): commits first, counts them against the declared length. */
    void writeDirect(byte[] b, int off, int len) throws IOException {
        if (!committed || count > 0) {
            flushOut(false);
        }
        written += len;
        if (!discard) {
            write(b, off, len);
        }
    }

    boolean isHead() {
        return ex.isHead();
    }

    /** Ends the response normally: commits, sends the rest, ends chunked coding, checks the declared length. */
    void finish() throws IOException {
        if (finished || aborted) {
            return;
        }
        if (writer != null) {
            finishing = true;
            try {
                writer.flush();
            } finally {
                finishing = false;
            }
        }
        flushOut(true);
        finished = true;
        if (!discard && contentLength >= 0 && written < contentLength) {
            conn.mustClose = true; // short body: only closing tells the client
        }
    }

    /** Cancels the transport without touching owner-thread buffers or waiting behind a socket writer. */
    // @req FR-HTTP-003
    @Override
    public void abort() {
        conn.abortResponse(this);
    }

    private void checkAborted() throws IOException {
        if (aborted) {
            throw new IOException("response aborted");
        }
    }

    /** Commits now with unknown length for streaming responses (event streams). */
    void commitStreaming() throws IOException {
        flushOut(false);
    }

    /** The response body stream. */
    final class Body extends OutputStream {

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            checkAborted();
            if (ignoreWrites) {
                return;
            }
            if (finished) {
                throw new IOException("response body is closed");
            }
            if (broken) {
                throw new IOException("connection broken");
            }
            if (contentLength >= 0 && !bodyless()) {
                long room = contentLength - written;
                if (len > room) {
                    if (room > 0) {
                        append(b, off, (int) room);
                    }
                    conn.mustClose = true;
                    throw new ContentLengthExceededException("declared " + contentLength + " bytes, wrote more");
                }
            }
            append(b, off, len);
        }

        private void append(byte[] b, int off, int len) throws IOException {
            ensureBuffer();
            written += len;
            int cap = capacity();
            while (len > 0) {
                if (count == cap) {
                    flushOut(false);
                }
                int n = Math.min(cap - count, len);
                System.arraycopy(b, off, buf, HEAD_RESERVE + count, n);
                count += n;
                off += n;
                len -= n;
            }
        }

        @Override
        public void flush() throws IOException {
            checkAborted();
            if (finishing || finished || ignoreWrites) {
                return;
            }
            flushOut(false);
        }

        @Override
        public void close() throws IOException {
            if (!ignoreWrites) {
                finish();
            }
        }
    }
}
