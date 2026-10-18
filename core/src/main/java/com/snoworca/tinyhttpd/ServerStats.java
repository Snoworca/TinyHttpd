package com.snoworca.tinyhttpd;

/** A snapshot of server counters. Counts are cumulative since start unless noted. */
public final class ServerStats {

    /** Connections accepted by the listener. */
    public final long acceptedConnections;
    /** Accepted connections not yet closed, including queued ones (current). */
    public final int openConnections;
    /** Accepted sockets waiting for a worker (current). */
    public final int queuedConnections;
    /** Workers on a connection that is not idle (current). */
    public final int busyWorkers;
    /** Live worker threads (current). */
    public final int workerThreads;
    /** Requests handled. */
    public final long requests;
    /** Connections refused because {@code maxConnections} was reached or the queue wait expired. */
    public final long rejectedOverCapacity;
    /** Connections refused because of {@code maxConnectionsPerAddress}. */
    public final long rejectedPerAddress;
    /** Idle connections closed by {@code idleTimeout}. */
    public final long idleTimeouts;
    /** Requests answered 408 because the header did not arrive in time. */
    public final long headerTimeouts;
    /** Connections closed because a body read timed out. */
    public final long bodyReadTimeouts;
    /** Connections closed by the watchdog because a write stalled. */
    public final long writeTimeouts;
    /** Idle connections closed to free a worker for a queued one. */
    public final long evictions;
    /** Worker threads still alive when {@code stop} returned. */
    public final int workersLeftAfterStop;
    /** Event streams open (current). */
    public final int eventStreams;
    /** Event streams closed because their queue overflowed. */
    public final long slowConsumerCloses;
    /** Upgraded connections open (current). */
    public final int upgradedConnections;
    /** Low-level socket write calls; tests use it to check write coalescing. */
    public final long socketWrites;

    /**
     * Internal: built by the server.
     *
     * @param v counter values in field order
     */
    public ServerStats(long... v) {
        acceptedConnections = v[0];
        openConnections = (int) v[1];
        queuedConnections = (int) v[2];
        busyWorkers = (int) v[3];
        workerThreads = (int) v[4];
        requests = v[5];
        rejectedOverCapacity = v[6];
        rejectedPerAddress = v[7];
        idleTimeouts = v[8];
        headerTimeouts = v[9];
        bodyReadTimeouts = v[10];
        writeTimeouts = v[11];
        evictions = v[12];
        workersLeftAfterStop = (int) v[13];
        eventStreams = (int) v[14];
        slowConsumerCloses = v[15];
        upgradedConnections = (int) v[16];
        socketWrites = v[17];
    }

    @Override
    public String toString() {
        return "ServerStats{accepted=" + acceptedConnections + ", open=" + openConnections + ", queued="
                + queuedConnections + ", busy=" + busyWorkers + ", workers=" + workerThreads + ", requests=" + requests
                + ", rejected=" + rejectedOverCapacity + "/" + rejectedPerAddress + ", timeouts(idle/header/body/write)="
                + idleTimeouts + "/" + headerTimeouts + "/" + bodyReadTimeouts + "/" + writeTimeouts + ", evictions="
                + evictions + ", eventStreams=" + eventStreams + ", upgraded=" + upgradedConnections + "}";
    }
}
