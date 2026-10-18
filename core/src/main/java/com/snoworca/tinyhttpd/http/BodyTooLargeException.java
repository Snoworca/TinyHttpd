package com.snoworca.tinyhttpd.http;

import java.io.IOException;

/** The request body, or a part of it, is larger than the configured limit. The server answers 413 if it still can. */
public class BodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    /** @param message description */
    public BodyTooLargeException(String message) {
        super(message);
    }
}
