package com.snoworca.tinyhttpd.http;

import java.io.IOException;

/** The request body violates its framing (chunked coding, multipart). The server answers 400 if it still can. */
public class MalformedBodyException extends IOException {

    private static final long serialVersionUID = 1L;

    /** @param message description */
    public MalformedBodyException(String message) {
        super(message);
    }
}
