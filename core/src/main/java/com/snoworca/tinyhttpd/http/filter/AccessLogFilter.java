package com.snoworca.tinyhttpd.http.filter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;

import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.FilterChain;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.ResponseWrapper;
import com.snoworca.tinyhttpd.log.TinyLog;

/** Logs one line per request: remote address, request line, status, body bytes written and milliseconds. */
public final class AccessLogFilter implements Filter {

    private final TinyLog log;

    private final Level level;

    /** Logs at INFO to the {@code com.snoworca.tinyhttpd.access} logger. */
    public AccessLogFilter() {
        this(TinyLog.jul("com.snoworca.tinyhttpd.access"), Level.INFO);
    }

    /**
     * @param log sink
     * @param level level of each line
     */
    public AccessLogFilter(TinyLog log, Level level) {
        this.log = log;
        this.level = level;
    }

    @Override
    public void doFilter(Request request, Response response, FilterChain chain) throws IOException, HttpException {
        if (!log.isLoggable(level)) {
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        final long[] bytes = new long[1];
        final OutputStream[] counting = new OutputStream[1];
        Response counted = new ResponseWrapper(response) {

            @Override
            public OutputStream getOutputStream() throws IOException {
                if (counting[0] == null) {
                    final OutputStream out = super.getOutputStream();
                    counting[0] = new OutputStream() {

                        @Override
                        public void write(int b) throws IOException {
                            out.write(b);
                            bytes[0]++;
                        }

                        @Override
                        public void write(byte[] b, int off, int len) throws IOException {
                            out.write(b, off, len);
                            bytes[0] += len;
                        }

                        @Override
                        public void flush() throws IOException {
                            out.flush();
                        }

                        @Override
                        public void close() throws IOException {
                            out.close();
                        }
                    };
                }
                return counting[0];
            }
        };
        String status = "-";
        try {
            chain.doFilter(request, counted);
            status = Integer.toString(response.getStatus());
        } catch (HttpException e) {
            status = Integer.toString(e.getStatus());
            throw e;
        } finally {
            String q = request.getQueryString();
            log.log(level, request.getRemoteAddr() + " \"" + request.getMethod() + " " + request.getRequestURI()
                    + (q != null ? "?" + q : "") + " " + request.getProtocol() + "\" " + status + " " + bytes[0] + " "
                    + (System.nanoTime() - start) / 1000000 + "ms", null);
        }
    }
}
