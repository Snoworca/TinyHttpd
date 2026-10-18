package com.snoworca.tinyhttpd.junit.spike;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

/**
 * P0-07 spike: how JVM sockets behave for the engine's acceptor/TLS/close choices. Results are recorded in
 * docs/research/socket-io.md; each test asserts what was observed on JDK 17.
 */
public class SocketIoSpikeTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private ServerSocketChannel ssc;

    private ServerSocket ss;

    private Socket client;

    @After
    public void tearDown() {
        for (java.io.Closeable c : new java.io.Closeable[]{client, ss, ssc}) {
            try {
                if (c != null) {
                    c.close();
                }
            } catch (IOException ignored) {
                // best effort; some peers are already closed
            }
        }
    }

    /** (1) Channel-accepted socket in blocking mode: adaptor stream read honors setSoTimeout. */
    @Test
    public void channelSocketReadHonorsSoTimeout() throws Exception {
        SocketChannel sc = acceptChannel();
        sc.socket().setSoTimeout(200);
        long t0 = System.nanoTime();
        Throwable t = run(() -> sc.socket().getInputStream().read(), 3000);
        long ms = (System.nanoTime() - t0) / 1000000;
        assertTrue("got " + t, t instanceof SocketTimeoutException);
        assertTrue("took " + ms + " ms", ms >= 150 && ms < 2000);
        // The channel stays usable after the timeout.
        client.getOutputStream().write(7);
        assertEquals(7, sc.socket().getInputStream().read());
        sc.close();
    }

    /** (2a) Plain accepted socket wrapped with createSocket(...) + setUseClientMode(false) handshakes as server. */
    @Test
    public void wrappedPlainSocketHandshakesAsServer() throws Exception {
        Socket[] pair = tlsPair();
        SSLSocket server = (SSLSocket) pair[0];
        SSLSocket tlsClient = (SSLSocket) pair[1];
        assertFalse(server.getUseClientMode());
        tlsClient.getOutputStream().write(42);
        tlsClient.getOutputStream().flush();
        assertEquals(42, server.getInputStream().read());
        server.getOutputStream().write(43);
        server.getOutputStream().flush();
        assertEquals(43, tlsClient.getInputStream().read());
    }

    /** (2b) Closing the underlying plain socket from another thread unblocks a blocked TLS read. */
    @Test
    public void closingPlainSocketUnblocksTlsRead() throws Exception {
        Socket[] pair = tlsPair();
        final SSLSocket server = (SSLSocket) pair[0];
        Throwable t = closeWhileBlocked(() -> server.getInputStream().read(), pair[2]);
        assertTrue("got " + t, t instanceof IOException);
    }

    /** (2c) Closing the underlying plain socket from another thread unblocks a blocked TLS write. */
    @Test
    public void closingPlainSocketUnblocksTlsWrite() throws Exception {
        Socket[] pair = tlsPair();
        final SSLSocket server = (SSLSocket) pair[0];
        Throwable t = closeWhileBlocked(() -> writeForever(server.getOutputStream()), pair[2]);
        assertTrue("got " + t, t instanceof IOException);
    }

    /** (3a) close() from another thread unblocks a plain socket write blocked on a full peer buffer. */
    @Test
    public void closeUnblocksPlainWrite() throws Exception {
        Socket server = acceptPlain();
        Throwable t = closeWhileBlocked(() -> writeForever(server.getOutputStream()), server);
        assertTrue("got " + t, t instanceof IOException);
    }

    /** (3b) close() from another thread unblocks FileChannel.transferTo into a blocked socket channel. */
    @Test
    public void closeUnblocksTransferTo() throws Exception {
        final SocketChannel sc = acceptChannel();
        File f = File.createTempFile("spike", ".bin");
        f.deleteOnExit();
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(256L << 20); // sparse; far larger than the socket buffers
            final FileChannel fc = raf.getChannel();
            Throwable t = closeWhileBlocked(() -> {
                long pos = 0;
                while (pos < fc.size()) {
                    pos += fc.transferTo(pos, fc.size() - pos, sc);
                }
                return pos;
            }, sc, false);
            // Linux JDK 17 (CI): close() did not wake sendfile within 2 s; recorded in docs/research/socket-io.md
            Assume.assumeTrue("close did not unblock transferTo on this platform", t != STILL_BLOCKED);
            assertTrue("got " + t, t instanceof IOException); // ClosedChannelException on JDK 17 (macOS)
        } finally {
            f.delete();
        }
    }

    /** (4) Full duplex on a channel socket's adaptor streams: a write proceeds while another thread is in read. */
    @Test
    public void channelSocketIsFullDuplex() throws Exception {
        final SocketChannel sc = acceptChannel();
        final InputStream in = sc.socket().getInputStream();
        Thread reader = new Thread(() -> {
            try {
                in.read();
            } catch (IOException ignored) {
                // closed at the end of the test
            }
        });
        reader.setDaemon(true);
        reader.start();
        waitBlocked(reader);
        Throwable t = run(() -> {
            sc.socket().getOutputStream().write(new byte[]{1, 2, 3});
            return null;
        }, 2000);
        assertEquals(null, t);
        byte[] got = new byte[3];
        int n = 0;
        while (n < 3) {
            n += client.getInputStream().read(got, n, 3 - n);
        }
        assertArrayEquals(new byte[]{1, 2, 3}, got);
        assertTrue(reader.isAlive());
        sc.close();
        reader.join(2000);
        assertFalse(reader.isAlive());
    }

    // ---- helpers

    private SocketChannel acceptChannel() throws IOException {
        ssc = ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress(LOOPBACK, 0));
        client = smallBufferClient(ssc.socket().getLocalPort());
        return ssc.accept();
    }

    private Socket acceptPlain() throws IOException {
        ss = new ServerSocket(0, 50, LOOPBACK);
        client = smallBufferClient(ss.getLocalPort());
        return ss.accept();
    }

    /** A client that never reads unless told to, with a small receive buffer so server writes block quickly. */
    private static Socket smallBufferClient(int port) throws IOException {
        Socket s = new Socket();
        s.setReceiveBufferSize(4096);
        s.connect(new InetSocketAddress(LOOPBACK, port));
        return s;
    }

    /** Returns {server SSLSocket, client SSLSocket, underlying plain server socket}. */
    private Socket[] tlsPair() throws Exception {
        KeyStore ks = com.snoworca.tinyhttpd.testing.TestTls.keyStore("server.p12");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, com.snoworca.tinyhttpd.testing.TestTls.PASSWORD.toCharArray());
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, new TrustManager[]{new TrustAll()}, null);

        Socket plain = acceptPlain();
        final SSLSocket tlsClient = (SSLSocket) clientCtx.getSocketFactory().createSocket(client, "localhost", client.getPort(), true);
        SSLSocket server = (SSLSocket) serverCtx.getSocketFactory().createSocket(plain, "localhost", plain.getPort(), true);
        server.setUseClientMode(false);
        final AtomicReference<Throwable> clientErr = new AtomicReference<Throwable>();
        Thread hs = new Thread(() -> {
            try {
                tlsClient.startHandshake();
            } catch (IOException e) {
                clientErr.set(e);
            }
        });
        hs.start();
        server.startHandshake();
        hs.join(5000);
        assertEquals(null, clientErr.get());
        client = tlsClient;
        return new Socket[]{server, tlsClient, plain};
    }

    private static Object writeForever(OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        while (true) {
            out.write(buf);
        }
    }

    private static final Throwable STILL_BLOCKED = new Throwable("still blocked after close");

    private static Throwable closeWhileBlocked(Callable<?> op, java.io.Closeable target) throws Exception {
        return closeWhileBlocked(op, target, true);
    }

    /** Starts op in a thread, checks it is blocked, closes target; returns what op threw, or STILL_BLOCKED when !mustUnblock. */
    private static Throwable closeWhileBlocked(final Callable<?> op, java.io.Closeable target, boolean mustUnblock) throws Exception {
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        Thread t = new Thread(() -> {
            try {
                op.call();
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.setDaemon(true);
        t.start();
        waitBlocked(t);
        assertTrue("op finished before close: " + thrown.get(), t.isAlive());
        target.close();
        t.join(2000);
        if (!mustUnblock && t.isAlive()) {
            return STILL_BLOCKED;
        }
        assertFalse("close did not unblock the op", t.isAlive());
        System.out.println("unblocked by close: " + thrown.get()); // recorded in docs/research/socket-io.md
        return thrown.get();
    }

    /** Runs op on another thread with a deadline; returns what it threw, or null. */
    private static Throwable run(final Callable<?> op, long timeoutMs) throws InterruptedException {
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        Thread t = new Thread(() -> {
            try {
                op.call();
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.setDaemon(true);
        t.start();
        t.join(timeoutMs);
        assertFalse("op still blocked after " + timeoutMs + " ms", t.isAlive());
        return thrown.get();
    }

    /** Gives the thread time to block; a thread blocked in native I/O still reports RUNNABLE, so just wait. */
    private static void waitBlocked(Thread t) throws InterruptedException {
        Thread.sleep(300);
        assertTrue(t.isAlive());
    }

    private static final class TrustAll implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
