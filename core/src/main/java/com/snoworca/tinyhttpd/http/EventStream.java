package com.snoworca.tinyhttpd.http;

import java.io.Closeable;
import java.io.IOException;

/**
 * A Server-Sent Events stream. Only the owner thread (the one that called {@link Response#startEventStream()})
 * writes to the socket. Other threads' {@link #send} calls enqueue and return at once; the owner writes them in
 * {@link #awaitClosed}. When the queue overflows (a slow client) the stream is closed and the client reconnects
 * with {@code Last-Event-ID}. Replaying missed events is the application's job, for example with a ring buffer
 * of recent events keyed by id.
 */
public interface EventStream extends Closeable {

    /**
     * Sends an event. On the owner thread it writes queued events first, then this one.
     *
     * @param event event type, or null
     * @param data data; line breaks split it into several {@code data:} lines
     * @param id event id, or null
     * @throws IOException if the stream is closed or the write fails
     * @throws IllegalArgumentException if event or id contain CR or LF, or id contains NUL
     */
    void send(String event, String data, String id) throws IOException;

    /**
     * @param text comment text, written as {@code : text}
     * @throws IOException if the stream is closed or the write fails
     */
    void comment(String text) throws IOException;

    /**
     * @param millis reconnection delay for the client
     * @throws IOException if the stream is closed or the write fails
     */
    void retry(long millis) throws IOException;

    /** @return false once closed or failed */
    boolean isOpen();

    /** @return the request's {@code Last-Event-ID}, or null */
    String getLastEventId();

    /**
     * Owner thread only: writes queued events until the stream closes, sending {@code : ping} when nothing was sent
     * for {@code heartbeatMillis}. A vanished client is noticed by a failed heartbeat or the write timeout, so it may
     * take up to {@code heartbeatMillis + writeTimeout}.
     *
     * @param heartbeatMillis heartbeat interval, {@link com.snoworca.tinyhttpd.TinyHttpd#UNLIMITED_LONG} for none
     * @throws InterruptedException if the owner thread is interrupted
     * @throws IllegalStateException on another thread
     */
    void awaitClosed(long heartbeatMillis) throws InterruptedException;

    /** Closes the stream from any thread; queued events are dropped and the owner thread wakes up. */
    @Override
    void close();
}
