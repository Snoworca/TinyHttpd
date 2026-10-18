package com.snoworca.tinyhttpd;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import com.snoworca.tinyhttpd.http.ErrorHandler;
import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.filter.HostAllowlistFilter;
import com.snoworca.tinyhttpd.log.TinyLog;

/**
 * Immutable server settings built by {@link TinyHttpd.Builder}. Sizes are bytes, times are milliseconds. Defaults are
 * the builder's defaults (docs/next/README.md §5).
 */
public final class ServerConfig {

    /** A listening socket: plain HTTP when {@code tls} is null. */
    public static final class Listener {

        public final InetAddress bindAddress;
        public final int port;
        public final TlsConfig tls;

        Listener(InetAddress bindAddress, int port, TlsConfig tls) {
            this.bindAddress = bindAddress;
            this.port = port;
            this.tls = tls;
        }
    }

    /** Listeners sharing the worker pool, registry, watchdog and limits. */
    public final List<Listener> listeners;
    public final int port;
    public final InetAddress bindAddress;
    public final Handler handler;
    /** Filters in order; the host check comes first when enabled. */
    public final List<Filter> filters;
    public final ErrorHandler errorHandler;
    public final TinyLog logger;
    public final TlsConfig tls;
    public final ThreadFactory threadFactory;
    public final boolean daemon;
    public final int workerStackBytes;

    public final int inputBufferBytes;
    public final int outputBufferBytes;
    public final int maxRequestLineBytes;
    public final int maxHeaderBytes;
    public final int maxHeaderCount;
    /** Changed at runtime through {@link TinyHttpd#setMaxRequestBodyBytes}. */
    private volatile long maxRequestBodyBytes;
    public final long maxDrainBytes;
    public final int maxChunkSizeLineBytes;
    public final int maxTrailerBytes;
    public final int maxRequestsPerConnection;
    public final String serverHeader;

    private volatile int lingerTimeout;
    private volatile int idleTimeout;
    private volatile int idleTimeoutUnderLoad;
    private volatile int headerTimeout;
    private volatile int bodyReadTimeout;
    private volatile int minRequestBodyRate;
    private volatile int writeTimeout;
    private volatile int queueWaitTimeout;
    private volatile int workerKeepAlive;
    private volatile int shutdownGrace;

    private volatile int maxWorkers;
    private volatile int maxConnections;
    public final int maxConnectionsPerAddress;

    public final int sendFileBufferBytes;
    public final int sendFileBuffers;
    private volatile int maxEventStreams;
    public final int eventQueueEvents;
    public final int eventQueueBytes;
    private volatile int maxUpgradedConnections;

    ServerConfig(TinyHttpd.Builder b) {
        List<Listener> ls = new ArrayList<Listener>();
        for (Listener l : b.listeners) {
            ls.add(l);
        }
        if (ls.isEmpty()) {
            ls.add(new Listener(b.bindAddress, b.port, b.tls));
        }
        listeners = Collections.unmodifiableList(ls);
        port = b.port;
        bindAddress = b.bindAddress;
        handler = b.handler;
        List<Filter> all = new ArrayList<Filter>(b.filters.size() + 1);
        if (b.hostCheck) {
            all.add(new HostAllowlistFilter(b.allowedHosts.toArray(new String[0])));
        }
        all.addAll(b.filters);
        filters = Collections.unmodifiableList(all);
        errorHandler = b.errorHandler;
        logger = b.logger;
        tls = b.tls;
        daemon = b.daemon;
        workerStackBytes = b.workerStackBytes;
        threadFactory = b.threadFactory;
        inputBufferBytes = b.inputBufferBytes;
        outputBufferBytes = b.outputBufferBytes;
        maxRequestLineBytes = b.maxRequestLineBytes;
        maxHeaderBytes = b.maxHeaderBytes;
        maxHeaderCount = b.maxHeaderCount;
        maxRequestBodyBytes = b.maxRequestBodyBytes;
        maxDrainBytes = b.maxDrainBytes;
        maxChunkSizeLineBytes = b.maxChunkSizeLineBytes;
        maxTrailerBytes = b.maxTrailerBytes;
        maxRequestsPerConnection = b.maxRequestsPerConnection;
        serverHeader = b.serverHeader;
        lingerTimeout = b.lingerTimeout;
        idleTimeout = b.idleTimeout;
        idleTimeoutUnderLoad = b.idleTimeoutUnderLoad;
        headerTimeout = b.headerTimeout;
        bodyReadTimeout = b.bodyReadTimeout;
        minRequestBodyRate = b.minRequestBodyRate;
        writeTimeout = b.writeTimeout;
        queueWaitTimeout = b.queueWaitTimeout;
        workerKeepAlive = b.workerKeepAlive;
        shutdownGrace = b.shutdownGrace;
        maxWorkers = b.maxWorkers;
        maxConnections = b.maxConnections;
        maxConnectionsPerAddress = b.maxConnectionsPerAddress;
        sendFileBufferBytes = b.sendFileBufferBytes;
        sendFileBuffers = b.sendFileBuffers;
        maxEventStreams = b.maxEventStreams;
        eventQueueEvents = b.eventQueueEvents;
        eventQueueBytes = b.eventQueueBytes;
        maxUpgradedConnections = b.maxUpgradedConnections;
    }

    /** @return request body limit (413), -1 for none */
    public long getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    void setMaxRequestBodyBytes(long value) {
        maxRequestBodyBytes = value;
    }

    /** @return the current {@code headerTimeout}; see {@link TinyHttpd.Builder#headerTimeout(int)} */
    public int getHeaderTimeout() {
        return headerTimeout;
    }

    /** @return the current {@code minRequestBodyRate}; see {@link TinyHttpd.Builder#minRequestBodyRate(int)} */
    public int getMinRequestBodyRate() {
        return minRequestBodyRate;
    }

    /** @return the current {@code bodyReadTimeout}; see {@link TinyHttpd.Builder#bodyReadTimeout(int)} */
    public int getBodyReadTimeout() {
        return bodyReadTimeout;
    }

    /** @return the current {@code writeTimeout}; see {@link TinyHttpd.Builder#writeTimeout(int)} */
    public int getWriteTimeout() {
        return writeTimeout;
    }

    /** @return the current {@code idleTimeout}; see {@link TinyHttpd.Builder#idleTimeout(int)} */
    public int getIdleTimeout() {
        return idleTimeout;
    }

    /** @return the current {@code idleTimeoutUnderLoad}; see {@link TinyHttpd.Builder#idleTimeoutUnderLoad(int)} */
    public int getIdleTimeoutUnderLoad() {
        return idleTimeoutUnderLoad;
    }

    /** @return the current {@code queueWaitTimeout}; see {@link TinyHttpd.Builder#queueWaitTimeout(int)} */
    public int getQueueWaitTimeout() {
        return queueWaitTimeout;
    }

    /** @return the current {@code lingerTimeout}; see {@link TinyHttpd.Builder#lingerTimeout(int)} */
    public int getLingerTimeout() {
        return lingerTimeout;
    }

    /** @return the current {@code workerKeepAlive}; see {@link TinyHttpd.Builder#workerKeepAlive(int)} */
    public int getWorkerKeepAlive() {
        return workerKeepAlive;
    }

    /** @return the current {@code shutdownGrace}; see {@link TinyHttpd.Builder#shutdownGrace(int)} */
    public int getShutdownGrace() {
        return shutdownGrace;
    }

    /** @return the current {@code maxWorkers}; see {@link TinyHttpd.Builder#maxWorkers(int)} */
    public int getMaxWorkers() {
        return maxWorkers;
    }

    /** @return the current {@code maxConnections}; see {@link TinyHttpd.Builder#maxConnections(int)} */
    public int getMaxConnections() {
        return maxConnections;
    }

    /** @return the current {@code maxEventStreams}; see {@link TinyHttpd.Builder#maxEventStreams(int)} */
    public int getMaxEventStreams() {
        return maxEventStreams;
    }

    /** @return the current {@code maxUpgradedConnections}; see {@link TinyHttpd.Builder#maxUpgradedConnections(int)} */
    public int getMaxUpgradedConnections() {
        return maxUpgradedConnections;
    }

    /** Applies a runtime change made through {@link TinyHttpd}; values are validated there. */
    void set(String name, int value) {
        if ("headerTimeout".equals(name)) {
            headerTimeout = value;
        } else if ("bodyReadTimeout".equals(name)) {
            bodyReadTimeout = value;
        } else if ("minRequestBodyRate".equals(name)) {
            minRequestBodyRate = value;
        } else if ("writeTimeout".equals(name)) {
            writeTimeout = value;
        } else if ("idleTimeout".equals(name)) {
            idleTimeout = value;
        } else if ("idleTimeoutUnderLoad".equals(name)) {
            idleTimeoutUnderLoad = value;
        } else if ("queueWaitTimeout".equals(name)) {
            queueWaitTimeout = value;
        } else if ("lingerTimeout".equals(name)) {
            lingerTimeout = value;
        } else if ("workerKeepAlive".equals(name)) {
            workerKeepAlive = value;
        } else if ("shutdownGrace".equals(name)) {
            shutdownGrace = value;
        } else if ("maxWorkers".equals(name)) {
            maxWorkers = value;
        } else if ("maxConnections".equals(name)) {
            maxConnections = value;
        } else if ("maxEventStreams".equals(name)) {
            maxEventStreams = value;
        } else if ("maxUpgradedConnections".equals(name)) {
            maxUpgradedConnections = value;
        } else {
            throw new IllegalArgumentException(name);
        }
    }
}
