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
        stop(config.getShutdownGrace());
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

    /** @return the first listener's bound port after {@link #start()}, else the configured port */
    public int getPort() {
        return server.getPort(0);
    }

    /**
     * @param listener index in the order the listeners were added
     * @return that listener's bound port
     */
    public int getPort(int listener) {
        return server.getPort(listener);
    }

    /** @return a snapshot of the counters */
    public ServerStats getStats() {
        return server.getStats();
    }

    /** @return the settings */
    public ServerConfig getConfig() {
        return config;
    }

    /**
     * Changes the request body limit while running; requests whose body has not started apply the new value.
     *
     * @param value request body limit (413), -1 for none
     */
    public void setMaxRequestBodyBytes(long value) {
        config.setMaxRequestBodyBytes(value);
    }

    /**
     * @param millis request line and headers (408); takes effect for timers started after the call
     */
    public void setHeaderTimeout(int millis) {
        set("headerTimeout", millis, 1);
    }

    /**
     * @param millis request body stall; takes effect for timers started after the call
     */
    public void setBodyReadTimeout(int millis) {
        set("bodyReadTimeout", millis, 1);
    }

    /**
     * @param bytesPerSecond minimum request body rate, 0 for none; applies to bodies read after the call
     */
    public void setMinRequestBodyRate(int bytesPerSecond) {
        set("minRequestBodyRate", bytesPerSecond, 0);
    }

    /**
     * @param millis blocked response write; takes effect for timers started after the call
     */
    public void setWriteTimeout(int millis) {
        set("writeTimeout", millis, 1);
    }

    /**
     * @param millis idle keep-alive connection; takes effect for timers started after the call
     */
    public void setIdleTimeout(int millis) {
        set("idleTimeout", millis, 1);
    }

    /**
     * @param millis idle keep-alive connection while busy; takes effect for timers started after the call
     */
    public void setIdleTimeoutUnderLoad(int millis) {
        set("idleTimeoutUnderLoad", millis, 1);
    }

    /**
     * @param millis wait for a worker (503); takes effect for timers started after the call
     */
    public void setQueueWaitTimeout(int millis) {
        set("queueWaitTimeout", millis, 1);
    }

    /**
     * @param millis lingering close; takes effect for timers started after the call
     */
    public void setLingerTimeout(int millis) {
        set("lingerTimeout", millis, 0);
    }

    /**
     * @param millis idle worker thread lifetime; takes effect for timers started after the call
     */
    public void setWorkerKeepAlive(int millis) {
        set("workerKeepAlive", millis, 1);
    }

    /**
     * @param millis default grace of {@link #stop()}; takes effect for timers started after the call
     */
    public void setShutdownGrace(int millis) {
        set("shutdownGrace", millis, 0);
    }

    /**
     * Changes the worker limits while running. Raising them starts queued connections at once; lowering them lets
     * busy workers finish their connection and then exit.
     *
     * @param maxWorkers worker threads for ordinary connections
     * @param maxConnections open plus queued ordinary connections, at least {@code maxWorkers}
     */
    public void setWorkerLimits(int maxWorkers, int maxConnections) {
        if (maxWorkers <= 0 || maxConnections < maxWorkers) {
            throw new IllegalArgumentException("need 0 < maxWorkers <= maxConnections");
        }
        synchronized (config) {
            // Order keeps maxConnections >= maxWorkers visible to readers at every step.
            if (maxWorkers > config.getMaxWorkers()) {
                config.set("maxConnections", maxConnections);
                config.set("maxWorkers", maxWorkers);
            } else {
                config.set("maxWorkers", maxWorkers);
                config.set("maxConnections", maxConnections);
            }
        }
        server.limitsChanged();
    }

    /** @param value open Server-Sent Events streams; existing streams above it are kept */
    public void setMaxEventStreams(int value) {
        set("maxEventStreams", value, 0);
    }

    /** @param value upgraded (WebSocket) connections; existing connections above it are kept */
    public void setMaxUpgradedConnections(int value) {
        set("maxUpgradedConnections", value, 0);
    }

    private void set(String name, int value, int min) {
        if (value < min) {
            throw new IllegalArgumentException(name + " must be >= " + min + ": " + value);
        }
        synchronized (config) {
            config.set(name, value);
        }
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
        final List<ServerConfig.Listener> listeners = new ArrayList<ServerConfig.Listener>();
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
        int minRequestBodyRate = 240;
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
         * Adds a listener. With listeners, {@link #port}, {@link #bindAddress} and {@link #tls} are ignored; all
         * listeners share workers and limits.
         *
         * @param address local address, null for all
         * @param port port, 0 for a free one
         * @param tls TLS settings, null for plain HTTP
         * @return this builder
         */
        public Builder listen(InetAddress address, int port, TlsConfig tls) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
            listeners.add(new ServerConfig.Listener(address, port, tls));
            return this;
        }

        /**
         * @param port plain HTTP port
         * @return this builder
         */
        public Builder listen(int port) {
            return listen(bindAddress, port, null);
        }

        /**
         * @param port HTTPS port
         * @param tls TLS settings
         * @return this builder
         */
        public Builder listen(int port, TlsConfig tls) {
            return listen(bindAddress, port, tls);
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
         * Slow-body guard: once the server has waited 5 s in total for request body bytes, a body arriving slower than
         * this is answered with 408. Only time spent waiting on the client counts, not the handler's own pace.
         *
         * @param value minimum request body rate in bytes per second, 0 for none; default 240
         * @return this builder
         */
        public Builder minRequestBodyRate(int value) {
            this.minRequestBodyRate = value;
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
            nonNegative("minRequestBodyRate", minRequestBodyRate);
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
            if (inputBufferBytes < maxChunkSizeLineBytes + 2) {
                throw new IllegalArgumentException("inputBufferBytes must be >= maxChunkSizeLineBytes + 2");
            }
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
