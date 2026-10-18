package com.snoworca.tinyhttpd;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadFactory;

import com.snoworca.tinyhttpd.http.ErrorHandler;
import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.log.TinyLog;

/**
 * Immutable server settings built by {@link TinyHttpd.Builder}. Sizes are bytes, times are milliseconds. Defaults are
 * the builder's defaults (docs/next/README.md §5).
 */
public final class ServerConfig {

    public final int port;
    public final InetAddress bindAddress;
    public final Handler handler;
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
    public final long maxRequestBodyBytes;
    public final long maxDrainBytes;
    public final int maxChunkSizeLineBytes;
    public final int maxTrailerBytes;
    public final int maxRequestsPerConnection;
    public final String serverHeader;

    public final int lingerTimeout;
    public final int idleTimeout;
    public final int idleTimeoutUnderLoad;
    public final int headerTimeout;
    public final int bodyReadTimeout;
    public final int writeTimeout;
    public final int queueWaitTimeout;
    public final int workerKeepAlive;
    public final int shutdownGrace;

    public final int maxWorkers;
    public final int maxConnections;
    public final int maxConnectionsPerAddress;

    public final int sendFileBufferBytes;
    public final int sendFileBuffers;
    public final int maxEventStreams;
    public final int eventQueueEvents;
    public final int eventQueueBytes;
    public final int maxUpgradedConnections;

    ServerConfig(TinyHttpd.Builder b) {
        port = b.port;
        bindAddress = b.bindAddress;
        handler = b.handler;
        filters = Collections.unmodifiableList(new ArrayList<Filter>(b.filters));
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
}
