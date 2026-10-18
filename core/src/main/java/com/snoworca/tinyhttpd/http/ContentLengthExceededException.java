package com.snoworca.tinyhttpd.http;

import java.io.IOException;

/** The handler wrote more response bytes than it declared with {@link Response#setContentLengthLong(long)}. */
public class ContentLengthExceededException extends IOException {

    private static final long serialVersionUID = 1L;

    /** @param message description */
    public ContentLengthExceededException(String message) {
        super(message);
    }
}
