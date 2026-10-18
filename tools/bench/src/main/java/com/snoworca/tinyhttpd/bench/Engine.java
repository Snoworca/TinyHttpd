package com.snoworca.tinyhttpd.bench;

import java.io.IOException;

/**
 * A server under test. It must serve on 127.0.0.1:
 * <ul>
 * <li>{@code GET /small} - a 2-byte body</li>
 * <li>{@code GET /download?size=N} - N bytes, fixed length</li>
 * <li>{@code POST /upload} - reads and discards the body, replies 200</li>
 * </ul>
 */
interface Engine {

    /** Starts on an ephemeral port and returns it. */
    int start() throws IOException;

    void stop();
}
