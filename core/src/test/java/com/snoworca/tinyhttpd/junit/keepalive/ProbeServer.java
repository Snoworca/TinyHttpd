package com.snoworca.tinyhttpd.junit.keepalive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.snoworca.tinyhttpd.protocols.http.ClientHandler;
import com.snoworca.tinyhttpd.protocols.http.IHTTPSession;
import com.snoworca.tinyhttpd.protocols.http.NanoHTTPD;
import com.snoworca.tinyhttpd.protocols.http.response.Response;
import com.snoworca.tinyhttpd.protocols.http.response.Status;
import com.snoworca.tinyhttpd.protocols.http.threading.DefaultAsyncRunner;

/**
 * Test server for the keep-alive probes. Each URI exercises one connection-handling path.
 */
class ProbeServer extends NanoHTTPD {

    final AtomicInteger acceptedConnections = new AtomicInteger();

    /** Every connection worker thread this server created. */
    final List<Thread> workerThreads = Collections.synchronizedList(new ArrayList<Thread>());

    ProbeServer() {
        super("127.0.0.1", 0);
        setAsyncRunner(new DefaultAsyncRunner() {

            @Override
            protected Thread createThread(ClientHandler clientHandler) {
                Thread t = super.createThread(clientHandler);
                workerThreads.add(t);
                return t;
            }
        });
    }

    @Override
    protected ClientHandler createClientHandler(Socket finalAccept, InputStream inputStream) {
        acceptedConnections.incrementAndGet();
        return super.createClientHandler(finalAccept, inputStream);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if ("/hello".equals(uri)) {
            return text("hello");
        }
        if ("/second".equals(uri)) {
            return text("second");
        }
        if ("/query".equals(uri)) {
            return text("query=" + session.getQueryParameterString());
        }
        if ("/ignore-body".equals(uri)) {
            // Handler that never touches the request body.
            return text("ignored");
        }
        if ("/chunked".equals(uri)) {
            return Response.newChunkedResponse(Status.OK, MIME_PLAINTEXT, new ByteArrayInputStream("hello".getBytes()));
        }
        if ("/short".equals(uri)) {
            // Declares 10 bytes but the stream only has 5.
            return Response.newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, new ByteArrayInputStream("12345".getBytes()), 10);
        }
        if ("/read-all".equals(uri)) {
            // Handler that reads the request body until EOF, as one would with a servlet.
            long count = 0;
            try {
                InputStream in = session.getInputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    count += n;
                }
                return text("read " + count);
            } catch (IOException e) {
                return text("read failed after " + count + " bytes: " + e);
            }
        }
        if ("/parse".equals(uri)) {
            Map<String, String> files = new HashMap<String, String>();
            try {
                session.parseBody(files);
            } catch (Exception e) {
                return text("parse failed: " + e);
            }
            String body = files.get("postData");
            return text("parsed " + (body == null ? 0 : body.length()));
        }
        return Response.newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "not found");
    }

    private static Response text(String s) {
        return Response.newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, s);
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

    /** Waits up to {@code millis} for all worker threads to finish and returns how many are still alive. */
    int awaitWorkersIdle(long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (liveWorkerThreads() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return liveWorkerThreads();
    }
}
