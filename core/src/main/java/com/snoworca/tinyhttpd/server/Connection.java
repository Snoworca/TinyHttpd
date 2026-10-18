package com.snoworca.tinyhttpd.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import javax.net.ssl.SSLSocket;

import com.snoworca.tinyhttpd.ServerConfig;
import com.snoworca.tinyhttpd.http.BodyTooLargeException;
import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.FilterChain;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MalformedBodyException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.TruncatedBodyException;
import com.snoworca.tinyhttpd.http.UpgradedConnection;

/**
 * One client connection, run by one worker thread. The loop ends with a result, not an exception: EOF, timeout,
 * a parse error answered and closed, or a response after which the connection is not persistent.
 */
final class Connection {

    static final int IDLE = 0, READING_HEADERS = 1, HANDLING = 2, DRAINING = 3, LINGERING = 4, HANDSHAKING = 5,
            STREAMING = 6, UPGRADED = 7, CLOSING = 8;

    /** Busy phases hold a worker that is doing work (idle keep-alive and long-lived connections are not busy). */
    static boolean isBusy(int phase) {
        return phase >= READING_HEADERS && phase <= HANDSHAKING;
    }

    final HttpServer server;

    final ServerConfig cfg;

    /** The plain socket from accept; closing it unblocks I/O on a TLS socket layered on it. */
    final Socket raw;

    /** The socket used for I/O: {@link #raw} or a TLS socket over it. */
    Socket socket;

    final WorkerPool.Worker worker;

    final ServerConfig.Listener listener;

    InputBuffer in;

    private OutputStream out;

    /** Changed only by CAS when leaving IDLE, so a worker and an evicting thread cannot both win. */
    final AtomicInteger phase = new AtomicInteger(IDLE);

    volatile long phaseSince = System.currentTimeMillis();

    /** Start of the current socket write, 0 when not writing; the watchdog closes stalled writes. */
    volatile long writeSince;

    volatile boolean writeTimedOut;

    /** A socket write failed; nothing more is written, not even TLS close_notify (P2-05, P7-06). */
    private boolean writeFailed;

    volatile SseStream eventStream;

    volatile Upgraded upgraded;

    private long deadline;

    private int soTimeout = -1;

    int requestCount;

    /** The connection must close after the current response. */
    boolean mustClose;

    Connection(HttpServer server, Socket raw, ServerConfig.Listener listener, WorkerPool.Worker worker) {
        this.server = server;
        this.listener = listener;
        this.cfg = server.config;
        this.raw = raw;
        this.socket = raw;
        this.worker = worker;
    }

    boolean isSecure() {
        return socket instanceof SSLSocket;
    }

    void setPhase(int p) {
        phase.set(p);
        phaseSince = System.currentTimeMillis();
    }

    void run() {
        try {
            raw.setTcpNoDelay(true);
            if (listener.tls != null) {
                setPhase(HANDSHAKING);
                socket = Tls.handshake(this);
                if (socket == null) {
                    return;
                }
            }
            in = new InputBuffer(socket.getInputStream(), cfg.inputBufferBytes, this);
            out = socket.getOutputStream();
            loop();
        } catch (IOException e) {
            if (server.log.isLoggable(Level.FINE)) {
                server.log.log(Level.FINE, "connection ended: " + e, null);
            }
        } finally {
            close();
        }
    }

    private void loop() throws IOException {
        while (true) {
            if (!enterIdle()) {
                return;
            }
            if (!in.hasBuffered()) {
                int n;
                try {
                    n = in.fill();
                } catch (SocketTimeoutException e) {
                    server.idleTimeouts.incrementAndGet();
                    return;
                } catch (IOException e) {
                    return;
                }
                if (n < 0) {
                    return;
                }
            }
            if (!phase.compareAndSet(IDLE, READING_HEADERS)) {
                return; // evicted or stopping: the request was not processed, the client retries
            }
            long now = System.currentTimeMillis();
            phaseSince = now;
            deadline = now + cfg.headerTimeout;
            Exchange ex = new Exchange(this);
            if (socket != raw) {
                Tls.setAttributes(this, ex);
            }
            try {
                RequestParser.parse(this, ex);
            } catch (ParseError e) {
                if (server.log.isLoggable(Level.FINE)) {
                    server.log.log(Level.FINE, "bad request (" + e.status + "): " + e.getMessage(), null);
                }
                sendRawAndLinger(ErrorResponses.forStatus(e.status));
                return;
            } catch (SocketTimeoutException e) {
                server.headerTimeouts.incrementAndGet();
                sendRawAndLinger(ErrorResponses.forStatus(408));
                return;
            } catch (IOException e) {
                return; // EOF or reset inside the header: nothing to answer
            }
            requestCount++;
            server.requests.incrementAndGet();
            setPhase(HANDLING);
            ExchangeResponse res = new ExchangeResponse(this, ex);
            try {
                dispatch(ex, res);
                if (upgraded != null || eventStream != null) {
                    mustClose = true;
                    return;
                }
                setPhase(DRAINING);
                if (!mustClose && !ex.body.drain(cfg.maxDrainBytes)) {
                    mustClose = true;
                }
            } finally {
                res.releaseBuffer();
            }
            if (!res.keepAlive || mustClose || server.isStopping()) {
                if (!res.broken) {
                    lingerClose();
                }
                return;
            }
        }
    }

    private boolean enterIdle() {
        int p = phase.get();
        if (p == CLOSING || !phase.compareAndSet(p, IDLE)) {
            return false;
        }
        phaseSince = System.currentTimeMillis();
        if (server.isStopping()) {
            return false;
        }
        return true;
    }

    /** Sets the socket timeout for the read about to block, from the current phase. */
    void beforeRead() throws IOException {
        int t;
        switch (phase.get()) {
            case IDLE:
                t = server.isUnderLoad() ? cfg.idleTimeoutUnderLoad : cfg.idleTimeout;
                break;
            case READING_HEADERS:
            case LINGERING:
            case HANDSHAKING:
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new SocketTimeoutException("deadline passed");
                }
                t = (int) Math.min(Integer.MAX_VALUE, left);
                break;
            case UPGRADED:
                Upgraded u = upgraded;
                t = u != null ? u.readTimeout : 0;
                break;
            default:
                t = cfg.bodyReadTimeout;
        }
        if (t != soTimeout) {
            socket.setSoTimeout(t);
            soTimeout = t;
        }
    }

    void setDeadline(long deadline) {
        this.deadline = deadline;
    }

    /** Writes to the socket under the watchdog's write timeout. */
    void write(byte[] b, int off, int len) throws IOException {
        if (writeFailed) {
            throw new IOException("an earlier write failed");
        }
        writeSince = System.currentTimeMillis();
        try {
            out.write(b, off, len);
            server.socketWrites.incrementAndGet();
        } catch (IOException e) {
            writeFailed = true;
            if (writeTimedOut) {
                throw new SocketTimeoutException("write timed out");
            }
            throw e;
        } finally {
            writeSince = 0;
        }
    }

    void flushSocket() throws IOException {
        writeSince = System.currentTimeMillis();
        try {
            out.flush();
        } catch (IOException e) {
            writeFailed = true;
            throw e;
        } finally {
            writeSince = 0;
        }
    }

    // ------------------------------------------------------------------ handling

    private void dispatch(Exchange ex, ExchangeResponse res) {
        try {
            new Chain(cfg.filters, cfg.handler).doFilter(ex, res);
            if (upgraded == null) {
                res.finish();
            }
        } catch (HttpException e) {
            error(res, e.getStatus(), e.getMessage(), e.getHeaders(), null);
        } catch (IOException e) {
            ioFailure(ex, res, e);
        } catch (RuntimeException e) {
            error(res, 500, null, null, e);
        } finally {
            SseStream es = eventStream;
            if (es != null) {
                es.ownerReturned();
            }
            Upgraded u = upgraded;
            if (u != null) {
                u.ended();
            }
        }
    }

    private void ioFailure(Exchange ex, ExchangeResponse res, IOException e) {
        mustClose = true;
        if (res.broken || writeTimedOut) {
            if (server.log.isLoggable(Level.FINE)) {
                server.log.log(Level.FINE, "client gone: " + e, null);
            }
            res.abort();
            return;
        }
        if (ex.body.failure == e || e instanceof BodyTooLargeException || e instanceof MalformedBodyException
                || e instanceof TruncatedBodyException) {
            int status = e instanceof BodyTooLargeException ? 413 : e instanceof SocketTimeoutException ? 408 : 400;
            if (server.log.isLoggable(Level.FINE)) {
                server.log.log(Level.FINE, "request body failed: " + e, null);
            }
            error(res, status, null, null, null);
            return;
        }
        error(res, 500, null, null, e);
    }

    private void error(ExchangeResponse res, int status, String message, List<String> headers, Throwable cause) {
        if (cause != null) {
            server.log.log(Level.SEVERE, "handler failed: " + res.ex, cause);
        }
        if (res.committed || upgraded != null) {
            res.abort();
            return;
        }
        res.reset();
        if (headers != null) {
            for (int i = 0; i + 1 < headers.size(); i += 2) {
                res.addHeader(headers.get(i), headers.get(i + 1));
            }
        }
        try {
            res.sendErrorPage(status, message);
        } catch (IOException | RuntimeException e) {
            res.abort();
        }
    }

    /** Persistence as far as it is known when the header is written (keep-alive.md §4.6). */
    boolean persistentAtCommit(ExchangeResponse res) {
        Exchange ex = res.ex;
        if (!ex.wantsKeepAlive() || res.closeRequested || res.closeDelimited || mustClose || server.isStopping()
                || requestCount >= cfg.maxRequestsPerConnection) {
            return false;
        }
        BodyInput body = ex.body;
        if (!body.complete) {
            if (!ex.mayReadBody() || body.failure != null || body.remaining() > cfg.maxDrainBytes) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ closing

    private void sendRawAndLinger(byte[] response) {
        try {
            write(response, 0, response.length);
        } catch (IOException e) {
            return;
        }
        lingerClose();
    }

    /**
     * Server-initiated close: send FIN, then read and drop input until EOF or {@code lingerTimeout}, so unread request
     * bytes do not make the kernel reset the response.
     */
    void lingerClose() {
        setPhase(LINGERING);
        deadline = System.currentTimeMillis() + cfg.lingerTimeout;
        try {
            if (isSecure()) {
                Tls.closeOutbound(this);
            } else {
                socket.shutdownOutput();
            }
            while (in.skip(Long.MAX_VALUE) >= 0) {
                // discard
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // EOF, timeout or reset: close now
        }
    }

    private void close() {
        phase.set(CLOSING);
        if (socket != raw && !writeFailed) {
            writeSince = System.currentTimeMillis(); // close_notify is a write the watchdog may need to abort
            HttpServer.closeQuietly(socket);
        }
        HttpServer.closeQuietly(raw);
    }

    /** Closes the plain socket from another thread; never blocks (no TLS close_notify). */
    void abortSocket() {
        HttpServer.closeQuietly(raw);
    }

    void onServerStopping() {
        if (phase.get() == IDLE && phase.compareAndSet(IDLE, CLOSING)) {
            abortSocket();
            return;
        }
        SseStream es = eventStream;
        if (es != null) {
            es.close();
        }
        Upgraded u = upgraded;
        if (u != null) {
            u.fireStop();
        }
    }

    // ------------------------------------------------------------------ upgrade

    UpgradedConnection upgrade(ExchangeResponse res, String protocol) throws IOException, HttpException {
        return Upgraded.start(this, res, protocol);
    }

    /** Runs filters in order, then the handler. */
    static final class Chain implements FilterChain {

        private final List<Filter> filters;

        private final com.snoworca.tinyhttpd.http.Handler handler;

        private int index;

        Chain(List<Filter> filters, com.snoworca.tinyhttpd.http.Handler handler) {
            this.filters = filters;
            this.handler = handler;
        }

        @Override
        public void doFilter(Request request, Response response) throws IOException, HttpException {
            if (index < filters.size()) {
                filters.get(index++).doFilter(request, response, this);
            } else {
                handler.handle(request, response);
            }
        }
    }
}
