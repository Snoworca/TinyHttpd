package com.snoworca.tinyhttpd;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManager;

/**
 * TLS settings for a listener, built with {@link #builder()}. Defaults follow docs/research/tls.md: TLS 1.3 and 1.2,
 * an explicit AEAD cipher allow-list, no client authentication, a 10 s handshake timeout. Protocols and cipher suites
 * are intersected with what the platform supports; an empty intersection makes {@code start()} fail.
 *
 * <pre>
 * TlsConfig tls = TlsConfig.builder().keyStore(new File("server.p12"), "PKCS12", password).build();
 * TinyHttpd.builder().listen(8443, tls);
 * </pre>
 */
public final class TlsConfig {

    /** Client certificate policy. */
    public enum ClientAuth {
        /** Do not ask for a certificate. */
        NONE,
        /** Ask; the handshake succeeds without one. */
        WANT,
        /** Require a certificate the trust managers accept. */
        NEED
    }

    /** Default protocols, best first. */
    public static final List<String> DEFAULT_PROTOCOLS =
            Collections.unmodifiableList(Arrays.asList("TLSv1.3", "TLSv1.2"));

    /** Default cipher suites: TLS 1.3 suites and TLS 1.2 ECDHE with AES-GCM or ChaCha20-Poly1305. */
    public static final List<String> DEFAULT_CIPHER_SUITES = Collections.unmodifiableList(Arrays.asList(
            "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"));

    private static final List<String> WEAK_PROTOCOLS = Arrays.asList("SSLv2Hello", "SSLv3", "TLSv1", "TLSv1.1");

    private final SSLContext context;

    private final String[] requestedProtocols;

    private final String[] protocols;

    private final String[] cipherSuites;

    private final ClientAuth clientAuth;

    private final int handshakeTimeout;

    private TlsConfig(Builder b, SSLContext context) {
        this.context = context;
        SSLParameters supported = context.getSupportedSSLParameters();
        this.requestedProtocols = b.protocols.toArray(new String[0]);
        this.protocols = intersect(b.protocols, supported.getProtocols());
        this.cipherSuites = intersect(b.cipherSuites, supported.getCipherSuites());
        this.clientAuth = b.clientAuth;
        this.handshakeTimeout = b.handshakeTimeout;
    }

    private static String[] intersect(List<String> wanted, String[] supported) {
        List<String> s = Arrays.asList(supported);
        List<String> out = new ArrayList<String>();
        for (String w : wanted) {
            if (s.contains(w)) {
                out.add(w);
            }
        }
        return out.toArray(new String[0]);
    }

    /** @return a builder with the defaults */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the context whose socket factory wraps accepted sockets */
    public SSLContext getSslContext() {
        return context;
    }

    /** @return requested protocols the platform supports, in the requested order; empty means start() fails */
    public String[] getProtocols() {
        return protocols.clone();
    }

    /** @return requested cipher suites the platform supports; empty means start() fails */
    public String[] getCipherSuites() {
        return cipherSuites.clone();
    }

    /** @return the client certificate policy */
    public ClientAuth getClientAuth() {
        return clientAuth;
    }

    /** @return milliseconds a client has to complete the handshake */
    public int getHandshakeTimeout() {
        return handshakeTimeout;
    }

    /** @return requested protocols that are obsolete (SSLv3, TLS 1.0, TLS 1.1) */
    public List<String> getWeakProtocols() {
        List<String> weak = new ArrayList<String>();
        for (String p : requestedProtocols) {
            if (WEAK_PROTOCOLS.contains(p)) {
                weak.add(p);
            }
        }
        return weak;
    }

    /** Builder for {@link TlsConfig}. Key material is required. */
    public static final class Builder {

        private KeyManager[] keyManagers;

        private TrustManager[] trustManagers;

        private List<String> protocols = DEFAULT_PROTOCOLS;

        private List<String> cipherSuites = DEFAULT_CIPHER_SUITES;

        private ClientAuth clientAuth = ClientAuth.NONE;

        private int handshakeTimeout = 10000;

        private int sessionCacheSize = 128;

        private int sessionTimeout = 3600;

        Builder() {
        }

        /**
         * @param keyManagers server key material
         * @return this builder
         */
        public Builder keyManagers(KeyManager[] keyManagers) {
            this.keyManagers = keyManagers;
            return this;
        }

        /**
         * Uses the keys in a loaded key store.
         *
         * @param keyStore key store with the server key and its chain
         * @param keyPassword password of the key entry
         * @return this builder
         * @throws GeneralSecurityException if the key cannot be read, for example a wrong password
         */
        public Builder keyStore(KeyStore keyStore, char[] keyPassword) throws GeneralSecurityException {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyPassword);
            return keyManagers(kmf.getKeyManagers());
        }

        /**
         * Loads a key store whose store and key password are the same.
         *
         * @param in key store bytes; not closed
         * @param type key store type, null for {@code PKCS12} ({@code BKS} on old Android, see docs/research/tls.md)
         * @param password store and key password
         * @return this builder
         * @throws IOException if loading fails; the cause is the original exception
         */
        public Builder keyStore(InputStream in, String type, char[] password) throws IOException {
            try {
                KeyStore ks = KeyStore.getInstance(type != null ? type : "PKCS12");
                ks.load(in, password);
                return keyStore(ks, password);
            } catch (IOException | GeneralSecurityException e) {
                throw new IOException("cannot load key store: " + e.getMessage(), e);
            }
        }

        /**
         * @param file key store file
         * @param type key store type, null for {@code PKCS12}
         * @param password store and key password
         * @return this builder
         * @throws IOException if reading or loading fails; the cause is the original exception
         */
        public Builder keyStore(File file, String type, char[] password) throws IOException {
            InputStream in = new FileInputStream(file);
            try {
                return keyStore(in, type, password);
            } finally {
                in.close();
            }
        }

        /**
         * @param trustManagers verify client certificates; required with {@link ClientAuth#WANT} or {@link ClientAuth#NEED}
         * @return this builder
         */
        public Builder trustManagers(TrustManager[] trustManagers) {
            this.trustManagers = trustManagers;
            return this;
        }

        /**
         * Protocols to enable, intersected with the supported ones. SSLv3, TLS 1.0 and 1.1 are logged as a warning.
         *
         * @param protocols names such as {@code TLSv1.2}
         * @return this builder
         */
        public Builder protocols(String... protocols) {
            this.protocols = Arrays.asList(protocols.clone());
            return this;
        }

        /**
         * Cipher suite allow-list, intersected with the supported ones (#361).
         *
         * @param cipherSuites standard names
         * @return this builder
         */
        public Builder cipherSuites(String... cipherSuites) {
            this.cipherSuites = Arrays.asList(cipherSuites.clone());
            return this;
        }

        /**
         * @param clientAuth client certificate policy; default {@link ClientAuth#NONE}
         * @return this builder
         */
        public Builder clientAuth(ClientAuth clientAuth) {
            if (clientAuth == null) {
                throw new IllegalArgumentException("clientAuth");
            }
            this.clientAuth = clientAuth;
            return this;
        }

        /**
         * @param millis time a client has to finish the handshake; default 10 s
         * @return this builder
         */
        public Builder handshakeTimeout(int millis) {
            if (millis <= 0) {
                throw new IllegalArgumentException("handshakeTimeout must be positive");
            }
            this.handshakeTimeout = millis;
            return this;
        }

        /**
         * @param entries server session cache size; default 128, 0 for unlimited
         * @return this builder
         */
        public Builder sessionCacheSize(int entries) {
            this.sessionCacheSize = entries;
            return this;
        }

        /**
         * @param seconds session lifetime for resumption; default 3600, 0 for unlimited
         * @return this builder
         */
        public Builder sessionTimeout(int seconds) {
            this.sessionTimeout = seconds;
            return this;
        }

        /**
         * @return the settings
         * @throws IllegalStateException if no key material was given
         * @throws IllegalArgumentException if the TLS context cannot be created; the cause is the original exception
         */
        public TlsConfig build() {
            if (keyManagers == null) {
                throw new IllegalStateException("TLS needs key material: keyStore(...) or keyManagers(...)");
            }
            if (clientAuth != ClientAuth.NONE && trustManagers == null) {
                // the platform default trusts public CAs, so any publicly issued client certificate would pass
                throw new IllegalStateException("client authentication needs trustManagers(...)");
            }
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(keyManagers, trustManagers, null);
                SSLSessionContext sessions = ctx.getServerSessionContext();
                if (sessions != null) {
                    sessions.setSessionCacheSize(sessionCacheSize);
                    sessions.setSessionTimeout(sessionTimeout);
                }
                return new TlsConfig(this, ctx);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("cannot create TLS context: " + e.getMessage(), e);
            }
        }
    }
}
