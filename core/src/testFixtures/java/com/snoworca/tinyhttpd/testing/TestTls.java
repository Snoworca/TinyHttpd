package com.snoworca.tinyhttpd.testing;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.snoworca.tinyhttpd.TlsConfig;

/**
 * Test-only TLS material: {@code tls/server.p12} (CN=localhost, SAN localhost and 127.0.0.1) and {@code tls/client.p12}
 * (CN=tinyhttpd-test-client), both self-signed, valid for 100 years, password {@link #PASSWORD}. Generated once with
 * {@code keytool -genkeypair -keyalg RSA -keysize 2048 -validity 36500 -storetype PKCS12}.
 */
public final class TestTls {

    public static final String PASSWORD = "tinyhttpd";

    private static volatile SSLContext defaultClient;

    private static volatile SSLSocketFactory defaultFactory;

    private TestTls() {
    }

    public static InputStream open(String name) {
        InputStream in = TestTls.class.getResourceAsStream("/tls/" + name);
        if (in == null) {
            throw new IllegalStateException("missing test resource tls/" + name);
        }
        return in;
    }

    public static KeyStore keyStore(String name) {
        try (InputStream in = open(name)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, PASSWORD.toCharArray());
            return ks;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return a server config builder with the test server key and a 3 s handshake timeout */
    public static TlsConfig.Builder server() {
        try (InputStream in = open("server.p12")) {
            return TlsConfig.builder().keyStore(in, "PKCS12", PASSWORD.toCharArray()).handshakeTimeout(3000);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return trust managers that accept exactly the certificate in {@code name} */
    public static TrustManager[] trusting(String name) {
        try {
            KeyStore src = keyStore(name);
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry("cert", src.getCertificate(src.aliases().nextElement()));
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            return tmf.getTrustManagers();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return a trust manager for the test server certificate */
    public static X509TrustManager serverTrust() {
        return (X509TrustManager) trusting("server.p12")[0];
    }

    /** @return a client context trusting the test server, presenting the test client certificate if asked */
    public static SSLContext client(boolean withClientCertificate) {
        try {
            KeyManager[] km = null;
            if (withClientCertificate) {
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore("client.p12"), PASSWORD.toCharArray());
                km = kmf.getKeyManagers();
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(km, new TrustManager[] {serverTrust()}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return a shared socket factory trusting the test server (one instance, so JDK keep-alive caches match) */
    public static SSLSocketFactory socketFactory() {
        SSLSocketFactory f = defaultFactory;
        if (f == null) {
            f = client().getSocketFactory();
            defaultFactory = f;
        }
        return f;
    }

    /** @return a shared client context trusting the test server */
    public static SSLContext client() {
        SSLContext c = defaultClient;
        if (c == null) {
            c = client(false);
            defaultClient = c;
        }
        return c;
    }
}
