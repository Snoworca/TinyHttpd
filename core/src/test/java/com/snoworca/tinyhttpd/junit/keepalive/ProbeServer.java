package com.snoworca.tinyhttpd.junit.keepalive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import com.snoworca.tinyhttpd.ServerStats;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/** Test server for the keep-alive probes on the new engine. Each path exercises one connection-handling path. */
public class ProbeServer {

    /** Every worker thread the server created. */
    final List<Thread> workerThreads = Collections.synchronizedList(new ArrayList<Thread>());

    final TinyHttpd server;

    ProbeServer() {
        this(TinyHttpd.builder());
    }

    ProbeServer(TinyHttpd.Builder builder) {
        server = builder.port(0).hostCheck(false).handler(HANDLER).threadFactory(new ThreadFactory() {

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "probe-worker-" + workerThreads.size());
                t.setDaemon(true);
                workerThreads.add(t);
                return t;
            }
        }).build();
    }

    static final Handler HANDLER = new Handler() {

        @Override
        public void handle(Request req, Response res) throws IOException {
            String uri = req.getPath();
            if ("/hello".equals(uri)) {
                text(res, "hello");
            } else if ("/second".equals(uri)) {
                text(res, "second");
            } else if ("/query".equals(uri)) {
                text(res, "query=" + req.getQueryString());
            } else if ("/ignore-body".equals(uri)) {
                text(res, "ignored");
            } else if ("/chunked".equals(uri)) {
                // No declared length and more than the 8 KB output buffer: chunked framing.
                byte[] block = new byte[1024];
                Arrays.fill(block, (byte) 'c');
                OutputStream out = res.getOutputStream();
                for (int i = 0; i < 16; i++) {
                    out.write(block);
                }
            } else if ("/short".equals(uri)) {
                res.setContentLengthLong(10);
                res.getOutputStream().write("12345".getBytes("US-ASCII"));
            } else if ("/read-all".equals(uri)) {
                long count = 0;
                try {
                    count = drain(req.getInputStream());
                    text(res, "read " + count);
                } catch (IOException e) {
                    text(res, "read failed: " + e);
                }
            } else if ("/parse".equals(uri)) {
                text(res, "parsed " + drain(req.getInputStream()));
            } else {
                res.setStatus(404);
                text(res, "not found");
            }
        }
    };

    static long drain(InputStream in) throws IOException {
        long count = 0;
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            count += n;
        }
        return count;
    }

    static void text(Response res, String s) throws IOException {
        res.setContentType("text/plain");
        res.getOutputStream().write(s.getBytes("UTF-8"));
    }

    void start() throws IOException {
        server.start();
    }

    void stop() {
        server.stop(500);
    }

    int port() {
        return server.getPort();
    }

    ServerStats stats() {
        return server.getStats();
    }

    int liveWorkerThreads() {
        int n = 0;
        synchronized (workerThreads) {
            for (Thread t : workerThreads) {
                if (t.isAlive()) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Waits until no worker is busy and no connection is open. Pooled workers stay alive between connections, so
     * "idle" means the connections were cleaned up, not that the threads ended.
     *
     * @return busy workers plus open connections still left after the wait
     */
    int awaitWorkersIdle(long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (true) {
            ServerStats s = stats();
            int left = s.busyWorkers + s.openConnections;
            if (left == 0 || System.currentTimeMillis() >= deadline) {
                return left;
            }
            Thread.sleep(20);
        }
    }
}
