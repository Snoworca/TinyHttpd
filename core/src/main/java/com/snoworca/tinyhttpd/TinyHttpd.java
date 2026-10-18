package com.snoworca.tinyhttpd;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import com.snoworca.tinyhttpd.http.ErrorHandler;
import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.log.TinyLog;
import com.snoworca.tinyhttpd.server.HttpServer;

/**
 * The server. Build one with {@link #builder()}, then {@link #start()} and {@link #stop()}. An instance cannot be
 * restarted; build a new one.
 *
 * <pre>
 * TinyHttpd server = TinyHttpd.builder().port(8080).handler((req, res) -&gt; res.getWriter().write("hi")).build();
 * server.start();
 * </pre>
 */
public final class TinyHttpd {

    private final ServerConfig config;

    private final HttpServer server;

    private TinyHttpd(ServerConfig config) {
        this.config = config;
        this.server = new HttpServer(config);
    }

    /** @return a builder with the default settings */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Binds the listening socket on the calling thread and starts accepting.
     *
     * @throws IOException if binding fails, for example {@link java.net.BindException}
     * @throws IllegalStateException if already started
     */
    public void start() throws IOException {
        server.start();
    }

    /** Stops with the configured {@code shutdownGrace}. */
    public void stop() {
        stop(config.shutdownGrace);
    }

    /**
     * Stops accepting, closes idle connections and event streams at once, waits up to {@code graceMillis} for
     * requests in progress, then closes the rest and interrupts their workers.
     *
     * @param graceMillis grace period
     */
    public void stop(long graceMillis) {
        server.stop(graceMillis);
    }

    /**
     * Waits until the server has stopped.
     *
     * @param millis how long to wait
     * @return true if stopped
     * @throws InterruptedException if interrupted
     */
    public boolean awaitTermination(long millis) throws InterruptedException {
        return server.awaitTermination(millis);
    }

    /** @return the bound port after {@link #start()}, else the configured port */
    public int getPort() {
        return server.getPort();
    }

    /** @return a snapshot of the counters */
    public ServerStats getStats() {
        return server.getStats();
    }

    /** @return the settings */
    public ServerConfig getConfig() {
        return config;
    }

    /** @return true between {@link #start()} and the end of {@link #stop()} */
    public boolean isAlive() {
        return server.isAlive();
    }

    /** Builder; every option has the default from docs/next/README.md §5. */
    public static final class Builder {

        int port;
        InetAddress bindAddress;
        Handler handler;
        final List<Filter> filters = new ArrayList<Filter>();
        ErrorHandler errorHandler = ErrorHandler.PLAIN;
        TinyLog logger = TinyLog.jul("com.snoworca.tinyhttpd");
        TlsConfig tls;
        ThreadFactory threadFactory;
        boolean daemon = true;
        String serverHeader;
        boolean hostCheck = true;
        final List<String> allowedHosts = new ArrayList<String>();
        int inputBufferBytes = 8192;
        int outputBufferBytes = 8192;
        int maxRequestLineBytes = 8190;
        int maxHeaderBytes = 16 * 1024;
        int maxHeaderCount = 100;
        long maxRequestBodyBytes = -1;
        long maxDrainBytes = 64 * 1024;
        int maxChunkSizeLineBytes = 1024;
        int maxTrailerBytes = 8 * 1024;
        int maxRequestsPerConnection = 1000;
        int lingerTimeout = 2000;
        int idleTimeout = 15000;
        int idleTimeoutUnderLoad = 2000;
        int headerTimeout = 10000;
        int bodyReadTimeout = 30000;
        int writeTimeout = 30000;
        int queueWaitTimeout = 5000;
        int workerKeepAlive = 60000;
        int shutdownGrace = 5000;
        int maxWorkers = 64;
        int maxConnections = 128;
        int maxConnectionsPerAddress = 32;
        int workerStackBytes = 256 * 1024;
        int sendFileBufferBytes = 64 * 1024;
        int sendFileBuffers = 8;
        int maxEventStreams = 64;
        int eventQueueEvents = 64;
        int eventQueueBytes = 64 * 1024;
        int maxUpgradedConnections = 16;

        Builder() {
        }

        /**
         * @param port listening port; 0 picks a free one
         * @return this builder
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * @param address local address to bind; null for all
         * @return this builder
         */
        public Builder bindAddress(InetAddress address) {
            this.bindAddress = address;
            return this;
        }

        /**
         * @param handler the request handler (required)
         * @return this builder
         */
        public Builder handler(Handler handler) {
            this.handler = handler;
            return this;
        }

        /**
         * @param filter added after the filters added before
         * @return this builder
         */
        public Builder filter(Filter filter) {
            if (filter == null) {
                throw new IllegalArgumentException("filter");
            }
            filters.add(filter);
            return this;
        }

        /**
         * @param errorHandler renders error bodies
         * @return this builder
         */
        public Builder errorHandler(ErrorHandler errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * @param logger log sink
         * @return this builder
         */
        public Builder logger(TinyLog logger) {
            this.logger = logger;
            return this;
        }

        /**
         * @param tls TLS settings; null for plain HTTP
         * @return this builder
         */
        public Builder tls(TlsConfig tls) {
            this.tls = tls;
            return this;
        }

        /**
         * @param factory creates worker threads; null for the default ({@code tinyhttpd-worker-n})
         * @return this builder
         */
        public Builder threadFactory(ThreadFactory factory) {
            this.threadFactory = factory;
            return this;
        }

        /**
         * @param daemon whether default worker threads are daemon threads
         * @return this builder
         */
        public Builder daemon(boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        /**
         * DNS rebinding defense, on by default: requests whose host is not an IP literal, {@code localhost} or an
         * allowed name get 421 ({@link com.snoworca.tinyhttpd.http.filter.HostAllowlistFilter}).
         *
         * @param enabled false to accept any host
         * @return this builder
         */
        public Builder hostCheck(boolean enabled) {
            this.hostCheck = enabled;
            return this;
        }

        /**
         * @param names host names allowed besides IP literals and {@code localhost}, for example {@code nas.local}
         * @return this builder
         */
        public Builder allowedHosts(String... names) {
            for (String n : names) {
                allowedHosts.add(n);
            }
            return this;
        }

        /**
         * @param value {@code Server} header value; null (default) sends none
         * @return this builder
         */
        public Builder serverHeader(String value) {
            this.serverHeader = value;
            return this;
        }

        /**
         * @param value per-connection input buffer; also bounds one header line; default 8192
         * @return this builder
         */
        public Builder inputBufferBytes(int value) {
            this.inputBufferBytes = value;
            return this;
        }
        /**
         * @param value response buffer; bodies that fit get a Content-Length; default 8192
         * @return this builder
         */
        public Builder outputBufferBytes(int value) {
            this.outputBufferBytes = value;
            return this;
        }
        /**
         * @param value request line limit (414); default 8190
         * @return this builder
         */
        public Builder maxRequestLineBytes(int value) {
            this.maxRequestLineBytes = value;
            return this;
        }
        /**
         * @param value total header bytes (431); default 16*1024
         * @return this builder
         */
        public Builder maxHeaderBytes(int value) {
            this.maxHeaderBytes = value;
            return this;
        }
        /**
         * @param value header lines (431); default 100
         * @return this builder
         */
        public Builder maxHeaderCount(int value) {
            this.maxHeaderCount = value;
            return this;
        }
        /**
         * @param value request body limit (413), -1 for none; default -1
         * @return this builder
         */
        public Builder maxRequestBodyBytes(long value) {
            this.maxRequestBodyBytes = value;
            return this;
        }
        /**
         * @param value unread body bytes discarded to keep a connection; default 64*1024
         * @return this builder
         */
        public Builder maxDrainBytes(long value) {
            this.maxDrainBytes = value;
            return this;
        }
        /**
         * @param value chunk size line limit; default 1024
         * @return this builder
         */
        public Builder maxChunkSizeLineBytes(int value) {
            this.maxChunkSizeLineBytes = value;
            return this;
        }
        /**
         * @param value chunked trailer limit; default 8*1024
         * @return this builder
         */
        public Builder maxTrailerBytes(int value) {
            this.maxTrailerBytes = value;
            return this;
        }
        /**
         * @param value requests before the server closes a connection; default 1000
         * @return this builder
         */
        public Builder maxRequestsPerConnection(int value) {
            this.maxRequestsPerConnection = value;
            return this;
        }
        /**
         * @param value how long input is discarded before a server-initiated close; default 2000
         * @return this builder
         */
        public Builder lingerTimeout(int value) {
            this.lingerTimeout = value;
            return this;
        }
        /**
         * @param value keep-alive idle timeout; default 15000
         * @return this builder
         */
        public Builder idleTimeout(int value) {
            this.idleTimeout = value;
            return this;
        }
        /**
         * @param value idle timeout while more than 75% of workers are busy; default 2000
         * @return this builder
         */
        public Builder idleTimeoutUnderLoad(int value) {
            this.idleTimeoutUnderLoad = value;
            return this;
        }
        /**
         * @param value deadline for the whole request header (408); default 10000
         * @return this builder
         */
        public Builder headerTimeout(int value) {
            this.headerTimeout = value;
            return this;
        }
        /**
         * @param value timeout of one request body read; default 30000
         * @return this builder
         */
        public Builder bodyReadTimeout(int value) {
            this.bodyReadTimeout = value;
            return this;
        }
        /**
         * @param value timeout of one socket write, enforced by the watchdog; default 30000
         * @return this builder
         */
        public Builder writeTimeout(int value) {
            this.writeTimeout = value;
            return this;
        }
        /**
         * @param value how long an accepted socket may wait for a worker (503); default 5000
         * @return this builder
         */
        public Builder queueWaitTimeout(int value) {
            this.queueWaitTimeout = value;
            return this;
        }
        /**
         * @param value how long an idle worker thread lives; default 60000
         * @return this builder
         */
        public Builder workerKeepAlive(int value) {
            this.workerKeepAlive = value;
            return this;
        }
        /**
         * @param value default grace period of stop(); default 5000
         * @return this builder
         */
        public Builder shutdownGrace(int value) {
            this.shutdownGrace = value;
            return this;
        }
        /**
         * @param value worker threads for ordinary connections; default 64
         * @return this builder
         */
        public Builder maxWorkers(int value) {
            this.maxWorkers = value;
            return this;
        }
        /**
         * @param value open plus queued ordinary connections; default 128
         * @return this builder
         */
        public Builder maxConnections(int value) {
            this.maxConnections = value;
            return this;
        }
        /**
         * @param value connections from one client address; default 32
         * @return this builder
         */
        public Builder maxConnectionsPerAddress(int value) {
            this.maxConnectionsPerAddress = value;
            return this;
        }
        /**
         * @param value stack size requested for worker threads (0 = platform default); default 256*1024
         * @return this builder
         */
        public Builder workerStackBytes(int value) {
            this.workerStackBytes = value;
            return this;
        }
        /**
         * @param value size of one sendFile copy buffer; default 64*1024
         * @return this builder
         */
        public Builder sendFileBufferBytes(int value) {
            this.sendFileBufferBytes = value;
            return this;
        }
        /**
         * @param value sendFile copy buffers in the shared pool; default 8
         * @return this builder
         */
        public Builder sendFileBuffers(int value) {
            this.sendFileBuffers = value;
            return this;
        }
        /**
         * @param value open Server-Sent Events streams; default 64
         * @return this builder
         */
        public Builder maxEventStreams(int value) {
            this.maxEventStreams = value;
            return this;
        }
        /**
         * @param value queued events per stream before it is closed; default 64
         * @return this builder
         */
        public Builder eventQueueEvents(int value) {
            this.eventQueueEvents = value;
            return this;
        }
        /**
         * @param value queued bytes per stream before it is closed; default 64*1024
         * @return this builder
         */
        public Builder eventQueueBytes(int value) {
            this.eventQueueBytes = value;
            return this;
        }
        /**
         * @param value upgraded (WebSocket) connections; default 16
         * @return this builder
         */
        public Builder maxUpgradedConnections(int value) {
            this.maxUpgradedConnections = value;
            return this;
        }

        /**
         * @return the server
         * @throws IllegalArgumentException naming the invalid option
         */
        public TinyHttpd build() {
            if (handler == null) {
                throw new IllegalArgumentException("handler is required");
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            positive("inputBufferBytes", inputBufferBytes);
            positive("outputBufferBytes", outputBufferBytes);
            positive("maxRequestLineBytes", maxRequestLineBytes);
            positive("maxHeaderBytes", maxHeaderBytes);
            positive("maxHeaderCount", maxHeaderCount);
            nonNegative("maxDrainBytes", maxDrainBytes);
            positive("maxChunkSizeLineBytes", maxChunkSizeLineBytes);
            positive("maxTrailerBytes", maxTrailerBytes);
            positive("maxRequestsPerConnection", maxRequestsPerConnection);
            nonNegative("lingerTimeout", lingerTimeout);
            positive("idleTimeout", idleTimeout);
            positive("idleTimeoutUnderLoad", idleTimeoutUnderLoad);
            positive("headerTimeout", headerTimeout);
            positive("bodyReadTimeout", bodyReadTimeout);
            positive("writeTimeout", writeTimeout);
            positive("queueWaitTimeout", queueWaitTimeout);
            positive("workerKeepAlive", workerKeepAlive);
            nonNegative("shutdownGrace", shutdownGrace);
            positive("maxWorkers", maxWorkers);
            positive("maxConnections", maxConnections);
            positive("maxConnectionsPerAddress", maxConnectionsPerAddress);
            nonNegative("workerStackBytes", workerStackBytes);
            positive("sendFileBufferBytes", sendFileBufferBytes);
            nonNegative("sendFileBuffers", sendFileBuffers);
            nonNegative("maxEventStreams", maxEventStreams);
            positive("eventQueueEvents", eventQueueEvents);
            positive("eventQueueBytes", eventQueueBytes);
            nonNegative("maxUpgradedConnections", maxUpgradedConnections);
            if (inputBufferBytes < maxRequestLineBytes + 2) {
                throw new IllegalArgumentException("inputBufferBytes must be >= maxRequestLineBytes + 2");
            }
            if (maxConnections < maxWorkers) {
                throw new IllegalArgumentException("maxConnections must be >= maxWorkers");
            }
            if (errorHandler == null || logger == null) {
                throw new IllegalArgumentException(errorHandler == null ? "errorHandler" : "logger");
            }
            return new TinyHttpd(new ServerConfig(this));
        }

        private static void positive(String name, long value) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive: " + value);
            }
        }

        private static void nonNegative(String name, long value) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative: " + value);
            }
        }
    }
}
