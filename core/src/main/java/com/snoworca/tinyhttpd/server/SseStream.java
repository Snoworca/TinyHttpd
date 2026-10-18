package com.snoworca.tinyhttpd.server;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.logging.Level;

import com.snoworca.tinyhttpd.http.EventStream;
import com.snoworca.tinyhttpd.http.HttpException;

/**
 * Server-Sent Events. Only the owner thread writes to the socket; other threads enqueue. A full queue (slow
 * consumer) drops the connection at once, because writing a last chunk to it would block too.
 */
final class SseStream implements EventStream {

    private static final byte[] PING = ": ping\n\n".getBytes(Ascii.UTF8);

    private final Connection conn;

    private final ExchangeResponse res;

    private final Thread owner;

    private final String lastEventId;

    private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();

    private int queuedBytes;

    private boolean open;

    /** Counted against maxEventStreams until the owner returns. */
    private boolean counted;

    private long lastWrite = System.currentTimeMillis();

    private SseStream(Connection conn, ExchangeResponse res, boolean open) {
        this.conn = conn;
        this.res = res;
        this.owner = Thread.currentThread();
        this.lastEventId = res.ex.getHeader("Last-Event-ID");
        this.open = open;
    }

    static EventStream start(ExchangeResponse res) throws IOException, HttpException {
        if (res.isCommitted()) {
            throw new IllegalStateException("response already committed");
        }
        Connection conn = res.conn;
        HttpServer server = res.server;
        res.setStatus(200);
        res.setContentType("text/event-stream; charset=UTF-8");
        res.setHeader("Cache-Control", "no-cache");
        res.setHeader("X-Accel-Buffering", "no");
        res.contentLength = -1;
        if (res.isHead()) {
            res.finish();
            return new SseStream(conn, res, false);
        }
        if (server.eventStreams.incrementAndGet() > server.config.maxEventStreams) {
            server.eventStreams.decrementAndGet();
            throw new HttpException(503, "too many event streams").withHeader("Retry-After", "5");
        }
        SseStream s = new SseStream(conn, res, true);
        s.counted = true;
        conn.eventStream = s;
        conn.setPhase(Connection.STREAMING);
        server.pool.detach(conn.worker);
        if (server.isStopping()) {
            s.close();
        }
        res.commitStreaming();
        return s;
    }

    // ------------------------------------------------------------------ events

    static void checkField(String name, String value) {
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\r' || c == '\n' || (c == 0 && name.equals("id"))) {
                    throw new IllegalArgumentException("invalid character in event " + name);
                }
            }
        }
    }

    static byte[] serialize(String event, String data, String id) {
        checkField("event", event);
        checkField("id", id);
        StringBuilder sb = new StringBuilder(32 + (data == null ? 0 : data.length()));
        if (event != null) {
            sb.append("event: ").append(event).append('\n');
        }
        if (id != null) {
            sb.append("id: ").append(id).append('\n');
        }
        String d = data == null ? "" : data;
        int start = 0;
        for (int i = 0; i <= d.length(); i++) {
            char c = i < d.length() ? d.charAt(i) : '\n';
            if (c == '\r' || c == '\n') {
                sb.append("data: ").append(d, start, i).append('\n');
                if (c == '\r' && i + 1 < d.length() && d.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        return sb.append('\n').toString().getBytes(Ascii.UTF8);
    }

    @Override
    public void send(String event, String data, String id) throws IOException {
        offer(serialize(event, data, id));
    }

    @Override
    public void comment(String text) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : (text == null ? "" : text).split("\r\n|\r|\n", -1)) {
            sb.append(": ").append(line).append('\n');
        }
        offer(sb.append('\n').toString().getBytes(Ascii.UTF8));
    }

    @Override
    public void retry(long millis) throws IOException {
        offer(("retry: " + millis + "\n\n").getBytes(Ascii.UTF8));
    }

    private void offer(byte[] event) throws IOException {
        if (Thread.currentThread() == owner) {
            byte[] next;
            while ((next = poll()) != null) {
                write(next);
            }
            if (!isOpen()) {
                throw new IOException("event stream closed");
            }
            write(event);
            return;
        }
        boolean overflow;
        synchronized (this) {
            if (!open) {
                throw new IOException("event stream closed");
            }
            overflow = queue.size() + 1 > res.server.config.eventQueueEvents
                    || queuedBytes + event.length > res.server.config.eventQueueBytes;
            if (!overflow) {
                queue.addLast(event);
                queuedBytes += event.length;
                notifyAll();
            }
        }
        if (overflow) {
            res.server.slowConsumerCloses.incrementAndGet();
            if (res.server.log.isLoggable(Level.FINE)) {
                res.server.log.log(Level.FINE, "event stream closed: slow consumer " + res.ex, null);
            }
            close();
            conn.abortSocket();
            throw new IOException("event queue full; stream closed");
        }
    }

    private synchronized byte[] poll() {
        byte[] b = queue.pollFirst();
        if (b != null) {
            queuedBytes -= b.length;
        }
        return b;
    }

    private void write(byte[] event) throws IOException {
        try {
            res.body.write(event, 0, event.length);
            res.body.flush();
            lastWrite = System.currentTimeMillis();
        } catch (IOException e) {
            synchronized (this) {
                open = false;
                queue.clear();
            }
            throw e;
        }
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public String getLastEventId() {
        return lastEventId;
    }

    @Override
    public void awaitClosed(long heartbeatMillis) throws InterruptedException {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("awaitClosed must run on the thread that started the stream");
        }
        while (true) {
            byte[] next;
            synchronized (this) {
                while (open && queue.isEmpty()) {
                    long left = lastWrite + heartbeatMillis - System.currentTimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    wait(left);
                }
                if (!open) {
                    return;
                }
                next = queue.pollFirst();
                if (next != null) {
                    queuedBytes -= next.length;
                }
            }
            try {
                write(next != null ? next : PING);
            } catch (IOException e) {
                return;
            }
        }
    }

    @Override
    public synchronized void close() {
        open = false;
        queue.clear();
        queuedBytes = 0;
        notifyAll();
    }

    /** The handler returned: the response ends (last chunk written by the engine) and the slot is released. */
    void ownerReturned() {
        close();
        if (counted) {
            counted = false;
            res.server.eventStreams.decrementAndGet();
        }
    }
}
