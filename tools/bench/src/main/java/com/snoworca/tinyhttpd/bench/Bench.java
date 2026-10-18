package com.snoworca.tinyhttpd.bench;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * In-process memory/throughput bench. Usage: {@code ./gradlew :bench:run --args='<scenario> [size]'}.
 * <p>
 * Scenarios: idle-0, idle-50, idle-100, idle-128, get-50, upload-1, download-4. Size (upload/download) defaults to
 * 64m; accepts k/m/g suffixes, e.g. {@code --args='download-4 1g'}. get-50 runs for {@code -Dbench.seconds} (3).
 * <p>
 * Prints one line of key=value. heapKB is {@code totalMemory - freeMemory} after GC, measured while the scenario's
 * connections are still open; the client runs in the same JVM but holds only sockets at that point.
 */
public final class Bench {

    private static final byte[] CRLFCRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static int port;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "idle-0";
        long size = args.length > 1 ? parseSize(args[1]) : 64L << 20;

        long baseHeap = heapAfterGc();
        int baseThreads = threads();

        Engine engine = new OldEngine();
        port = engine.start();
        List<Conn> open = new ArrayList<Conn>();
        long[] lat; // per-request (or per-transfer) latency in ns
        long bytes = 0;
        long t0 = System.nanoTime();
        if (scenario.startsWith("idle-")) {
            int n = Integer.parseInt(scenario.substring(5));
            for (int i = 0; i < n; i++) {
                Conn c = new Conn();
                c.get("/small");
                open.add(c); // stays open, keep-alive, idle
            }
            lat = new long[0];
        } else if (scenario.equals("get-50")) {
            lat = parallel(50, Long.getLong("bench.seconds", 3) * 1000L, 0, open);
        } else if (scenario.equals("upload-1")) {
            lat = parallel(1, 0, -size, open);
            bytes = size;
        } else if (scenario.equals("download-4")) {
            lat = parallel(4, 0, size, open);
            bytes = 4 * size;
        } else {
            throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        Thread.sleep(300); // let server threads settle
        Arrays.sort(lat);
        StringBuilder load = new StringBuilder();
        if (lat.length > 0) {
            load.append(" requests=").append(lat.length);
            load.append(" seconds=").append(fmt(secs));
            load.append(" reqPerSec=").append(fmt(lat.length / secs));
            if (bytes > 0) {
                load.append(" sizeMB=").append(size >> 20).append(" MBps=").append(fmt(bytes / 1048576.0 / secs));
            }
            load.append(" p50ms=").append(fmt(lat[lat.length / 2] / 1e6));
            load.append(" p99ms=").append(fmt(lat[(int) Math.min(lat.length - 1, (long) Math.ceil(lat.length * 0.99) - 1)] / 1e6));
        }
        lat = null; // keep latency samples out of heapKB

        long heap = heapAfterGc();
        int threads = threads();
        for (Conn c : open) {
            c.socket.close();
        }
        engine.stop();

        StringBuilder sb = new StringBuilder();
        sb.append("scenario=").append(scenario).append(" engine=old");
        sb.append(" heapKB=").append(heap / 1024).append(" threads=").append(threads);
        sb.append(" baseHeapKB=").append(baseHeap / 1024).append(" baseThreads=").append(baseThreads);
        sb.append(load);
        sb.append(" jdk=").append(System.getProperty("java.version"));
        sb.append(" maxHeapMB=").append(Runtime.getRuntime().maxMemory() >> 20);
        System.out.println(sb);
    }

    /**
     * Runs {@code n} client threads, each on its own connection. transfer &gt; 0: download that many bytes once;
     * transfer &lt; 0: upload that many bytes once; 0: GET /small until {@code millis} elapse.
     */
    private static long[] parallel(int n, final long millis, final long transfer, final List<Conn> open) throws Exception {
        final long[][] perThread = new long[n][];
        final Exception[] failure = new Exception[1];
        Thread[] ts = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final Conn c = new Conn();
            open.add(c);
            ts[i] = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        long[] l = new long[1024];
                        int k = 0;
                        long end = System.nanoTime() + millis * 1000000L;
                        do {
                            long s = System.nanoTime();
                            if (transfer > 0) {
                                c.get("/download?size=" + transfer);
                            } else if (transfer < 0) {
                                c.upload(-transfer);
                            } else {
                                c.get("/small");
                            }
                            if (k == l.length) {
                                l = Arrays.copyOf(l, k * 2);
                            }
                            l[k++] = System.nanoTime() - s;
                        } while (transfer == 0 && System.nanoTime() < end);
                        perThread[idx] = Arrays.copyOf(l, k);
                    } catch (Exception e) {
                        failure[0] = e;
                    }
                }
            });
        }
        for (Thread t : ts) {
            t.start();
        }
        for (Thread t : ts) {
            t.join();
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        int total = 0;
        for (long[] l : perThread) {
            total += l.length;
        }
        long[] all = new long[total];
        int p = 0;
        for (long[] l : perThread) {
            System.arraycopy(l, 0, all, p, l.length);
            p += l.length;
        }
        return all;
    }

    /** Minimal HTTP/1.1 keep-alive client over a plain socket. */
    private static final class Conn {

        final Socket socket = new Socket();

        final InputStream in;

        final OutputStream out;

        Conn() throws IOException {
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream(), 256); // small: idle clients must not skew heapKB
            out = socket.getOutputStream();
        }

        void get(String path) throws IOException {
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            readResponse();
        }

        void upload(long size) throws IOException {
            out.write(("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                    + size + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[64 * 1024];
            for (long left = size; left > 0; left -= Math.min(buf.length, left)) {
                out.write(buf, 0, (int) Math.min(buf.length, left));
            }
            out.flush();
            readResponse();
        }

        /** Reads status line and headers, then discards a Content-Length body. */
        private void readResponse() throws IOException {
            StringBuilder head = new StringBuilder();
            int matched = 0;
            while (matched < 4) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("connection closed in headers");
                }
                head.append((char) b);
                matched = b == CRLFCRLF[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            String h = head.toString();
            if (!h.startsWith("HTTP/1.1 200")) {
                throw new IOException("unexpected response: " + h);
            }
            long left = -1;
            for (String line : h.split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    left = Long.parseLong(line.substring(15).trim());
                }
            }
            if (left < 0) {
                throw new IOException("no content-length: " + h);
            }
            byte[] buf = new byte[(int) Math.min(64 * 1024, left)];
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new IOException("connection closed in body");
                }
                left -= n;
            }
        }
    }

    static long parseSize(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        char unit = s.charAt(s.length() - 1);
        int shift = unit == 'k' ? 10 : unit == 'm' ? 20 : unit == 'g' ? 30 : 0;
        return Long.parseLong(shift == 0 ? s : s.substring(0, s.length() - 1)) << shift;
    }

    private static long heapAfterGc() throws InterruptedException {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static int threads() {
        return ManagementFactory.getThreadMXBean().getThreadCount();
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
