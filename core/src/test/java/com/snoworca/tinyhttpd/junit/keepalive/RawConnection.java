package com.snoworca.tinyhttpd.junit.keepalive;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.1 client over a raw socket, so probes control exactly which bytes go on the wire
 * and can keep using the same connection.
 */
final class RawConnection implements Closeable {

    static final Charset ASCII = Charset.forName("US-ASCII");

    static final class RawResponse {

        String statusLine;

        final Map<String, String> headers = new LinkedHashMap<String, String>();

        String body = "";

        int status() {
            String[] parts = statusLine.split(" ");
            return Integer.parseInt(parts[1]);
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }

    private final Socket socket;

    private final InputStream in;

    private final OutputStream out;

    RawConnection(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3000);
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    void send(String raw) throws IOException {
        send(raw.getBytes(ASCII));
    }

    void send(byte[] raw) throws IOException {
        out.write(raw);
        out.flush();
    }

    /** Reads one response. For a HEAD request no body is read, as RFC 7230 §3.3.3 requires. */
    RawResponse read(boolean headRequest) throws IOException {
        RawResponse r = new RawResponse();
        r.statusLine = readLine();
        String line;
        while (!(line = readLine()).isEmpty()) {
            int p = line.indexOf(':');
            r.headers.put(line.substring(0, p).trim().toLowerCase(Locale.US), line.substring(p + 1).trim());
        }
        if (headRequest) {
            return r;
        }
        String te = r.header("transfer-encoding");
        String cl = r.header("content-length");
        if (te != null && te.contains("chunked")) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int size;
            while ((size = Integer.parseInt(readLine().trim(), 16)) > 0) {
                body.write(readExactly(size));
                readLine();
            }
            readLine();
            r.body = new String(body.toByteArray(), ASCII);
        } else if (cl != null) {
            r.body = new String(readExactly(Integer.parseInt(cl)), ASCII);
        }
        return r;
    }

    RawResponse read() throws IOException {
        return read(false);
    }

    /**
     * Waits up to {@code millis} for the peer to close the connection.
     *
     * @return {@code "closed"} on EOF, {@code "reset"} on a connection reset, {@code "open"} if the connection is
     *         still open after the wait, or {@code "data"} if unexpected bytes arrived.
     */
    String awaitClose(int millis) throws IOException {
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

    byte[] readExactly(int n) throws IOException {
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

    private String readLine() throws IOException {
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
