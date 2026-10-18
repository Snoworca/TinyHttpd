package com.snoworca.tinyhttpd.server;

import java.io.IOException;
import java.net.Socket;

/** TLS on accepted plain sockets (P7). */
final class Tls {

    private Tls() {
    }

    /** Fails {@code start()} for settings no provider supports. */
    static void validate(com.snoworca.tinyhttpd.TlsConfig tls) {
        throw new UnsupportedOperationException("P7");
    }

    static Socket handshake(Connection c) throws IOException {
        throw new UnsupportedOperationException("P7");
    }

    static void closeOutbound(Connection c) throws IOException {
        throw new UnsupportedOperationException("P7");
    }
}
