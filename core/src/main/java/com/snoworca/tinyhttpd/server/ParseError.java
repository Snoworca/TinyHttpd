package com.snoworca.tinyhttpd.server;

/** A request that cannot be parsed; the engine answers {@link #status} and closes. */
final class ParseError extends Exception {

    private static final long serialVersionUID = 1L;

    final int status;

    ParseError(int status, String message) {
        super(message);
        this.status = status;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this; // thrown for every malformed request: skip the stack walk
    }
}
