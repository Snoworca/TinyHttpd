package com.snoworca.tinyhttpd.http;

import java.io.Closeable;
import java.io.IOException;

/** Reads {@code multipart/form-data} one part at a time without buffering the body. */
public interface MultipartReader extends Closeable {

    /**
     * Moves to the next part, skipping what is left of the current one.
     *
     * @return the next part, or null after the closing boundary
     * @throws IOException on I/O failure or a malformed body
     * @throws HttpException 413 when a limit is exceeded
     */
    Part next() throws IOException, HttpException;
}
