package com.snoworca.tinyhttpd.http;

import java.io.IOException;

/** The client closed the connection before sending the whole request body. The connection is closed afterwards. */
public class TruncatedBodyException extends IOException {

    private static final long serialVersionUID = 1L;

    /** @param message description */
    public TruncatedBodyException(String message) {
        super(message);
    }
}
