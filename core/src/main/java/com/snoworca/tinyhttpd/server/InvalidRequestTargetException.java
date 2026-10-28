package com.snoworca.tinyhttpd.server;

/** FR-HTTP-004: unchecked to preserve Request.getPath's existing public source signature. */
final class InvalidRequestTargetException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    InvalidRequestTargetException() {
        super("invalid decoded request path");
    }
}
