package com.snoworca.tinyhttpd.testing;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal HTTP/1.1 client over a raw socket, so tests control exactly which bytes go on the wire and can keep using
 * the same connection.
 */
public final class RawConnection implements Closeable {

    public static final Charset ASCII = Charset.forName("US-ASCII");

    public static final Charset UTF8 = Charset.forName("UTF-8");

    /** One parsed response. */
    public static final class RawResponse {

        public String statusLine;

        /** Lower-case name to the last value. */
        public final Map<String, String> headers = new LinkedHashMap<String, String>();

        /** Every header line in order, as {@code name: value}. */
        public final List<String> headerLines = new ArrayList<String>();

        public byte[] bodyBytes = new byte[0];

        public String body = "";

        public boolean chunked;

        public int status() {
            String[] parts = statusLine.split(" ");
            return Integer.parseInt(parts[1]);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        public int count(String name) {
            int n = 0;
            for (String l : headerLines) {
                if (l.toLowerCase(Locale.US).startsWith(name.toLowerCase(Locale.US) + ":")) {
                    n++;
                }
            }
            return n;
        }
    }

    private final Socket socket;

    private final InputStream in;

    private final OutputStream out;

    public RawConnection(int port) throws IOException {
        this(port, 0);
    }

    /**
     * @param port server port
     * @param receiveBufferBytes socket receive buffer, 0 for the default; small values let tests stall the server's
     *        writes
     */
    public RawConnection(int port, int receiveBufferBytes) throws IOException {
        this(port, receiveBufferBytes, null);
    }

    /**
     * @param port server port
     * @param receiveBufferBytes socket receive buffer, 0 for the default
     * @param tls null for plain HTTP, else the TLS client socket factory; the handshake completes in the constructor
     */
    public RawConnection(int port, int receiveBufferBytes, SSLSocketFactory tls) throws IOException {
        Socket plain = new Socket();
        if (receiveBufferBytes > 0) {
            plain.setReceiveBufferSize(receiveBufferBytes);
        }
        plain.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        plain.setSoTimeout(3000);
        if (tls != null) {
            SSLSocket s = (SSLSocket) tls.createSocket(plain, "localhost", port, true);
            s.startHandshake();
            socket = s;
        } else {
            socket = plain;
        }
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /** @return a TLS connection trusting the test certificate ({@link TestTls}) */
    public static RawConnection tls(int port) throws IOException {
        return new RawConnection(port, 0, TestTls.socketFactory());
    }

    public Socket socket() {
        return socket;
    }

    public void setReadTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    public void send(String raw) throws IOException {
        send(raw.getBytes(UTF8));
    }

    public void send(byte[] raw) throws IOException {
        out.write(raw);
        out.flush();
    }

    /** Sends {@code raw} in pieces of {@code chunk} bytes with {@code delayMs} between them (slowloris, split headers). */
    public void sendSlowly(byte[] raw, int chunk, long delayMs) throws IOException {
        for (int off = 0; off < raw.length; off += chunk) {
            out.write(raw, off, Math.min(chunk, raw.length - off));
            out.flush();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        }
    }

    /** Half-closes: the server sees EOF, the response can still be read. */
    public void shutdownOutput() throws IOException {
        socket.shutdownOutput();
    }

    /** Reads one response. For a HEAD request (or a 1xx/204/304) no body is read. */
    public RawResponse read(boolean headRequest) throws IOException {
        RawResponse r = new RawResponse();
        r.statusLine = readLine();
        String line;
        while (!(line = readLine()).isEmpty()) {
            int p = line.indexOf(':');
            r.headerLines.add(line);
            r.headers.put(line.substring(0, p).trim().toLowerCase(Locale.US), line.substring(p + 1).trim());
        }
        int status = r.status();
        if (headRequest || status < 200 || status == 204 || status == 304) {
            return r;
        }
        String te = r.header("transfer-encoding");
        String cl = r.header("content-length");
        if (te != null && te.contains("chunked")) {
            r.chunked = true;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int size;
            while ((size = Integer.parseInt(readLine().trim(), 16)) > 0) {
                body.write(readExactly(size));
                readLine();
            }
            readLine();
            r.bodyBytes = body.toByteArray();
        } else if (cl != null) {
            r.bodyBytes = readExactly(Integer.parseInt(cl));
        } else if ("close".equalsIgnoreCase(r.header("connection"))) {
            r.bodyBytes = readToEof();
        }
        r.body = new String(r.bodyBytes, UTF8);
        return r;
    }

    public RawResponse read() throws IOException {
        return read(false);
    }

    /**
     * Waits up to {@code millis} for the peer to close the connection.
     *
     * @return {@code "closed"} on EOF, {@code "reset"} on a connection reset, {@code "open"} if still open after the
     *         wait, or {@code "data"} if unexpected bytes arrived.
     */
    public String awaitClose(int millis) throws IOException {
        int old = socket.getSoTimeout();
        socket.setSoTimeout(millis);
        try {
            return in.read() == -1 ? "closed" : "data";
        } catch (SocketTimeoutException e) {
            return "open";
        } catch (IOException e) {
            return "reset";
        } finally {
            if (!socket.isClosed()) {
                socket.setSoTimeout(old);
            }
        }
    }

    public byte[] readExactly(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r == -1) {
                throw new EOFException("expected " + n + " bytes, got " + off);
            }
            off += r;
        }
        return b;
    }

    public byte[] readToEof() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    public String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        throw new EOFException("connection closed while reading line: '" + sb + "'");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
