package com.snoworca.tinyhttpd.bench;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import com.snoworca.tinyhttpd.protocols.http.IHTTPSession;
import com.snoworca.tinyhttpd.protocols.http.NanoHTTPD;
import com.snoworca.tinyhttpd.protocols.http.request.Method;
import com.snoworca.tinyhttpd.protocols.http.response.Response;
import com.snoworca.tinyhttpd.protocols.http.response.Status;

/** The inherited NanoHttpd engine (one thread per connection). */
final class OldEngine implements Engine {

    private NanoHTTPD server;

    @Override
    @SuppressWarnings("deprecation") // the old engine's serve(IHTTPSession) is deprecated
    public int start() throws IOException {
        server = new NanoHTTPD("127.0.0.1", 0) {

            @Override
            public Response serve(IHTTPSession session) {
                String uri = session.getUri();
                if (session.getMethod() == Method.POST && uri.equals("/upload")) {
                    long left = Long.parseLong(session.getHeaders().get("content-length"));
                    byte[] buf = new byte[64 * 1024];
                    try {
                        InputStream in = session.getInputStream();
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) {
                                break;
                            }
                            left -= n;
                        }
                    } catch (IOException e) {
                        return Response.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "io");
                    }
                    return Response.newFixedLengthResponse(Status.OK, "text/plain", "ok");
                }
                if (uri.equals("/download")) {
                    long size = Long.parseLong(session.getParameters().get("size").get(0));
                    return Response.newFixedLengthResponse(Status.OK, "application/octet-stream", new ZeroStream(size), size);
                }
                return Response.newFixedLengthResponse(Status.OK, "text/plain", "ok");
            }
        };
        // Long read timeout so idle connections stay open while we measure.
        server.start(60000, true);
        return server.getListeningPort();
    }

    @Override
    public void stop() {
        server.stop();
    }

    /** {@code size} zero bytes without holding them in memory. */
    static final class ZeroStream extends InputStream {

        private long left;

        ZeroStream(long size) {
            left = size;
        }

        @Override
        public int read() {
            if (left <= 0) {
                return -1;
            }
            left--;
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (left <= 0) {
                return -1;
            }
            int n = (int) Math.min(len, left);
            Arrays.fill(b, off, off + n, (byte) 0);
            left -= n;
            return n;
        }
    }
}
