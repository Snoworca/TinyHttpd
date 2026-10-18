package com.snoworca.tinyhttpd.server;

import java.io.IOException;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.logging.Level;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import com.snoworca.tinyhttpd.TlsConfig;
import com.snoworca.tinyhttpd.log.TinyLog;

/**
 * TLS on accepted plain sockets (P7). The worker wraps the plain socket, so the acceptor never handshakes and the
 * watchdog can always unblock I/O by closing the plain socket (docs/research/tls.md).
 */
final class Tls {

    static final String PEER_CERTIFICATES = "tinyhttpd.tls.peerCertificates", PROTOCOL = "tinyhttpd.tls.protocol",
            CIPHER_SUITE = "tinyhttpd.tls.cipherSuite";

    private Tls() {
    }

    /** Fails {@code start()} for settings no provider supports; warns about obsolete protocols. */
    static void validate(TlsConfig tls, TinyLog log) {
        if (tls.getProtocols().length == 0) {
            throw new IllegalArgumentException("none of the configured TLS protocols is supported");
        }
        if (tls.getCipherSuites().length == 0) {
            throw new IllegalArgumentException("none of the configured cipher suites is supported");
        }
        if (!tls.getWeakProtocols().isEmpty() && log.isLoggable(Level.WARNING)) {
            log.log(Level.WARNING, "obsolete TLS protocols enabled: " + tls.getWeakProtocols(), null);
        }
    }

    /**
     * Wraps the plain socket in server mode and completes the handshake within the handshake timeout.
     *
     * @return the TLS socket, or null if the handshake failed (logged at FINE; nothing is written in plain text)
     */
    static Socket handshake(Connection c) throws IOException {
        TlsConfig tls = c.listener.tls;
        Socket raw = c.raw;
        c.setDeadline(System.currentTimeMillis() + tls.getHandshakeTimeout());
        c.beforeRead(); // the timeout set on the plain socket also bounds reads of the layered one
        SSLSocket s = (SSLSocket) tls.getSslContext().getSocketFactory().createSocket(raw,
                raw.getInetAddress().getHostAddress(), raw.getPort(), true);
        try {
            s.setUseClientMode(false);
            s.setEnabledProtocols(tls.getProtocols());
            s.setEnabledCipherSuites(tls.getCipherSuites());
            if (tls.getClientAuth() == TlsConfig.ClientAuth.NEED) {
                s.setNeedClientAuth(true);
            } else if (tls.getClientAuth() == TlsConfig.ClientAuth.WANT) {
                s.setWantClientAuth(true);
            }
            s.startHandshake();
            return s;
        } catch (IOException | RuntimeException e) {
            if (c.server.log.isLoggable(Level.FINE)) {
                c.server.log.log(Level.FINE, "TLS handshake failed from " + raw.getInetAddress() + ": " + e, null);
            }
            return null;
        }
    }

    /**
     * First step of a server-initiated TLS close; the caller then drains input through TLS until EOF or
     * {@code lingerTimeout} and closes. Where the provider supports TLS half-close (JDK 11+), close_notify goes out
     * now so a client reading to EOF is not kept waiting. Android's {@code SSLSocket} has no {@code shutdownOutput},
     * so there close_notify waits for {@code close()} (docs/research/tls.md).
     */
    static void closeOutbound(Connection c) throws IOException {
        c.flushSocket();
        try {
            c.socket.shutdownOutput();
        } catch (UnsupportedOperationException e) {
            // Conscrypt: drain first, close_notify on close()
        }
    }

    /** Sets the TLS request attributes from the connection's session. */
    static void setAttributes(Connection c, Exchange ex) {
        SSLSession session = ((SSLSocket) c.socket).getSession();
        ex.setAttribute(PROTOCOL, session.getProtocol());
        ex.setAttribute(CIPHER_SUITE, session.getCipherSuite());
        try {
            Certificate[] chain = session.getPeerCertificates();
            ex.setAttribute(PEER_CERTIFICATES, Arrays.copyOf(chain, chain.length, X509Certificate[].class));
        } catch (SSLPeerUnverifiedException | ArrayStoreException e) {
            // no client certificate, or not X.509
        }
    }
}
