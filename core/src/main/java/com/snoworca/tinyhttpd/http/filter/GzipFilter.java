package com.snoworca.tinyhttpd.http.filter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.FilterChain;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.ResponseWrapper;

/**
 * Opt-in gzip for compressible responses. Compresses only when the client accepts gzip, the type is text-like, the
 * response is not 204/206/304, HEAD, an event stream or a {@code sendFile}, has no {@code Content-Encoding}, and its
 * first 1 KB fills up. Compressed responses drop {@code Content-Length}, weaken a strong {@code ETag} and add
 * {@code Vary: Accept-Encoding}. At most {@code maxConcurrentGzip} responses compress at once, since each
 * {@link Deflater} holds about 256 KB of native memory.
 */
public final class GzipFilter implements Filter {

    private static final int THRESHOLD = 1024;

    private final int maxConcurrent;

    private final AtomicInteger active = new AtomicInteger();

    /** At most 8 concurrent compressions. */
    public GzipFilter() {
        this(8);
    }

    /** @param maxConcurrentGzip concurrent compressions; others are sent uncompressed */
    public GzipFilter(int maxConcurrentGzip) {
        this.maxConcurrent = maxConcurrentGzip;
    }

    @Override
    public void doFilter(Request request, Response response, FilterChain chain) throws IOException, HttpException {
        if ("HEAD".equals(request.getMethod()) || !acceptsGzip(request.getHeader("Accept-Encoding"))) {
            chain.doFilter(request, response);
            return;
        }
        GzipResponse wrapped = new GzipResponse(response);
        try {
            chain.doFilter(request, wrapped);
            wrapped.finish();
        } finally {
            wrapped.release();
        }
    }

    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        for (String part : acceptEncoding.split(",")) {
            String[] p = part.trim().split(";");
            if (p[0].trim().equalsIgnoreCase("gzip")) {
                for (int i = 1; i < p.length; i++) {
                    String q = p[i].trim();
                    if (q.startsWith("q=")) {
                        try {
                            return Double.parseDouble(q.substring(2)) > 0;
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    static boolean compressible(String contentType) {
        if (contentType == null) {
            return false;
        }
        String t = contentType.toLowerCase(Locale.US);
        int semi = t.indexOf(';');
        t = (semi < 0 ? t : t.substring(0, semi)).trim();
        if (t.equals("text/event-stream")) {
            return false;
        }
        return t.startsWith("text/") || t.equals("application/json") || t.equals("application/javascript")
                || t.equals("image/svg+xml");
    }

    /** Buffers the first 1 KB, then decides once whether to compress. */
    private final class GzipResponse extends ResponseWrapper {

        private final ByteArrayOutputStream head = new ByteArrayOutputStream(THRESHOLD);

        private OutputStream target;

        private Gzip gzip;

        private boolean holdsSlot;

        private boolean passThrough;

        private long declaredLength = -1;

        private Writer writer;

        private OutputStream stream;

        GzipResponse(Response wrapped) {
            super(wrapped);
        }

        @Override
        public void setContentLengthLong(long length) {
            declaredLength = length;
            if (passThrough) {
                super.setContentLengthLong(length);
            }
        }

        @Override
        public void setHeader(String name, String value) {
            if ("Content-Length".equalsIgnoreCase(name) && value != null) {
                setContentLengthLong(Long.parseLong(value.trim()));
                return;
            }
            super.setHeader(name, value);
        }

        @Override
        public void sendFile(java.io.File file, long offset, long length) throws IOException {
            passThrough = true;
            super.sendFile(file, offset, length);
        }

        @Override
        public void sendFile(java.nio.channels.FileChannel channel, long offset, long length) throws IOException {
            passThrough = true;
            super.sendFile(channel, offset, length);
        }

        @Override
        public com.snoworca.tinyhttpd.http.EventStream startEventStream() throws IOException, HttpException {
            passThrough = true;
            return super.startEventStream();
        }

        @Override
        public void resetBuffer() {
            super.resetBuffer();
            head.reset();
        }

        @Override
        public void reset() {
            super.reset();
            head.reset();
            declaredLength = -1;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if (stream == null) {
                stream = new OutputStream() {

                    @Override
                    public void write(int b) throws IOException {
                        write(new byte[] {(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        if (target == null) {
                            if (head.size() + len < THRESHOLD) {
                                head.write(b, off, len);
                                return;
                            }
                            decide(true);
                        }
                        target.write(b, off, len);
                    }

                    @Override
                    public void flush() throws IOException {
                        if (target == null) {
                            decide(false);
                        }
                        target.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        finish();
                    }
                };
            }
            return stream;
        }

        @Override
        public Writer getWriter() throws IOException {
            if (writer == null) {
                String type = getContentType();
                String cs = null;
                if (type != null) {
                    int i = type.toLowerCase(Locale.US).indexOf("charset=");
                    if (i >= 0) {
                        cs = type.substring(i + 8).trim().replace("\"", "");
                    }
                }
                if (cs == null) {
                    setContentType((type == null ? "text/plain" : type) + "; charset=UTF-8");
                    cs = "UTF-8";
                }
                writer = new OutputStreamWriter(getOutputStream(), Charset.forName(cs));
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            getOutputStream().flush();
        }

        /** Chooses compression once, then writes the buffered head. */
        private void decide(boolean large) throws IOException {
            Response r = getWrapped();
            int status = r.getStatus();
            boolean compress = large && !passThrough && !isCommitted() && status != 204 && status != 206
                    && status != 304 && r.getHeader("Content-Encoding") == null && compressible(r.getContentType());
            if (compress) {
                if (active.incrementAndGet() > maxConcurrent) {
                    active.decrementAndGet();
                    compress = false;
                } else {
                    holdsSlot = true;
                }
            }
            if (compress) {
                r.setHeader("Content-Encoding", "gzip");
                r.addHeader("Vary", "Accept-Encoding");
                String etag = r.getHeader("ETag");
                if (etag != null && etag.startsWith("\"")) {
                    r.setHeader("ETag", "W/" + etag);
                }
                gzip = new Gzip(r.getOutputStream());
                target = gzip;
            } else {
                if (declaredLength >= 0) {
                    r.setContentLengthLong(declaredLength);
                }
                target = r.getOutputStream();
            }
            head.writeTo(target);
            head.reset();
        }

        void finish() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            if (passThrough && target == null) {
                return;
            }
            if (target == null) {
                decide(false);
            }
            if (gzip != null) {
                gzip.finish();
            }
            getWrapped().getOutputStream().close();
        }

        void release() {
            if (gzip != null) {
                gzip.end(); // the Deflater holds native memory: free it even after a failure
            }
            if (holdsSlot) {
                holdsSlot = false;
                active.decrementAndGet();
            }
        }
    }

    /** GZIPOutputStream whose Deflater can be ended without closing the response stream. */
    private static final class Gzip extends GZIPOutputStream {

        Gzip(OutputStream out) throws IOException {
            super(out, 8192);
        }

        void end() {
            def.end();
        }
    }
}
