package com.snoworca.tinyhttpd.server;

import java.io.IOException;
import java.net.Socket;

/** TLS on accepted plain sockets (P7). */
final class Tls {

    private Tls() {
    }

    static Socket handshake(Connection c) throws IOException {
        throw new UnsupportedOperationException("P7");
    }

    static void closeOutbound(Connection c) throws IOException {
        throw new UnsupportedOperationException("P7");
    }
}
