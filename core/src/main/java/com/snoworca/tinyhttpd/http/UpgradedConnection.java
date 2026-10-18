package com.snoworca.tinyhttpd.http;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The raw connection after {@code 101 Switching Protocols}. HTTP processing has ended for it. */
public interface UpgradedConnection extends Closeable {

    /** @return input that starts with the bytes the server had already buffered */
    InputStream getInputStream();

    /** @return raw output; each write is watched by the server's write timeout */
    OutputStream getOutputStream();

    /**
     * Sets how long a read may block before it throws {@link java.net.SocketTimeoutException}.
     *
     * @param millis timeout, 0 for none
     * @throws IOException on socket failure
     */
    void setReadTimeout(int millis) throws IOException;

    /** @return true once the server is stopping; protocols should say goodbye and close */
    boolean isServerStopping();

    /**
     * Registers a callback run once, on the stopping thread, when the server starts to stop, so the protocol can
     * send its goodbye (WebSocket close 1001). It must not block for long; writes are still bounded by the write
     * timeout.
     *
     * @param callback the callback
     */
    void onServerStop(Runnable callback);

    /** Closes the connection at once (no protocol goodbye). */
    @Override
    void close();
}
