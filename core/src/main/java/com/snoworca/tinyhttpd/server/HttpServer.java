package com.snoworca.tinyhttpd.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import com.snoworca.tinyhttpd.ServerConfig;
import com.snoworca.tinyhttpd.ServerStats;
import com.snoworca.tinyhttpd.log.TinyLog;

/**
 * Internal API, may change without notice. The engine behind {@code TinyHttpd}: an acceptor per listener, a bounded
 * worker pool with a wait queue, and a watchdog that enforces write timeouts and evicts idle connections.
 */
public final class HttpServer {

    static final int NEW = 0, STARTED = 1, STOPPING = 2, STOPPED = 3;

    final ServerConfig config;

    final TinyLog log;

    final WorkerPool pool;

    final BufferPool outputBuffers;

    final BufferPool sendFileBuffers;

    /** Every live connection, for the watchdog, eviction and shutdown. */
    final Set<Connection> registry = Collections.newSetFromMap(new ConcurrentHashMap<Connection, Boolean>());

    private final Map<InetAddress, int[]> perAddress = new HashMap<InetAddress, int[]>();

    private final List<Lingering> lingering = new ArrayList<Lingering>();

    private final Object stateLock = new Object();

    private volatile int state = NEW;

    private volatile boolean underLoad;

    /** A bound listener with its acceptor thread. */
    final class Bound {

        final ServerConfig.Listener listener;

        final ServerSocket socket;

        Thread acceptor;

        Bound(ServerConfig.Listener listener, ServerSocket socket) {
            this.listener = listener;
            this.socket = socket;
        }
    }

    private final List<Bound> bound = new ArrayList<Bound>();

    private Thread watchdog;

    private final ServerSocketSource socketSource;

    final AtomicLong accepted = new AtomicLong(), requests = new AtomicLong(), rejectedOverCapacity = new AtomicLong(),
            rejectedPerAddress = new AtomicLong(), idleTimeouts = new AtomicLong(), headerTimeouts = new AtomicLong(),
            bodyReadTimeouts = new AtomicLong(), writeTimeouts = new AtomicLong(), evictions = new AtomicLong(),
            slowConsumerCloses = new AtomicLong(), socketWrites = new AtomicLong();

    final AtomicInteger eventStreams = new AtomicInteger(), upgraded = new AtomicInteger();

    private volatile int workersLeftAfterStop;

    /** Opens the listening socket; replaced in tests to simulate accept failures. */
    interface ServerSocketSource {

        ServerSocket open(ServerConfig.Listener listener) throws IOException;
    }

    /** @param config settings */
    public HttpServer(ServerConfig config) {
        this(config, null);
    }

    HttpServer(ServerConfig config, ServerSocketSource source) {
        this.config = config;
        this.log = config.logger;
        this.socketSource = source != null ? source : new ServerSocketSource() {

            @Override
            public ServerSocket open(ServerConfig.Listener l) throws IOException {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                try {
                    ss.bind(new InetSocketAddress(l.bindAddress, l.port), 128);
                } catch (IOException e) {
                    ss.close();
                    throw e;
                }
                return ss;
            }
        };
        ThreadFactory tf = config.threadFactory != null ? config.threadFactory : new DefaultThreadFactory(config, log);
        this.pool = new WorkerPool(this, tf);
        this.outputBuffers = new BufferPool(config.outputBufferBytes,
                config.maxWorkers + config.maxEventStreams + config.maxUpgradedConnections);
        this.sendFileBuffers = new BufferPool(config.sendFileBufferBytes, config.sendFileBuffers);
    }

    /**
     * Binds on the calling thread, then starts the acceptor and watchdog.
     *
     * @throws IOException if binding fails
     */
    public void start() throws IOException {
        synchronized (stateLock) {
            if (state != NEW) {
                throw new IllegalStateException("a server can be started once");
            }
            for (ServerConfig.Listener l : config.listeners) {
                if (l.tls != null) {
                    Tls.validate(l.tls, log);
                }
            }
            try {
                for (ServerConfig.Listener l : config.listeners) {
                    bound.add(new Bound(l, socketSource.open(l)));
                }
            } catch (IOException | RuntimeException e) {
                for (Bound b : bound) {
                    closeQuietly(b.socket);
                }
                bound.clear();
                throw e;
            }
            state = STARTED;
        }
        for (final Bound b : bound) {
            b.acceptor = new Thread(new Runnable() {

                @Override
                public void run() {
                    acceptLoop(b);
                }
            }, "tinyhttpd-acceptor-" + b.socket.getLocalPort());
            b.acceptor.setDaemon(config.daemon);
        }
        watchdog = new Thread(new Runnable() {

            @Override
            public void run() {
                watchdogLoop();
            }
        }, "tinyhttpd-watchdog");
        watchdog.setDaemon(true);
        for (Bound b : bound) {
            b.acceptor.start();
        }
        watchdog.start();
    }

    /**
     * @param index listener index
     * @return the bound port, or the configured one before start
     */
    public int getPort(int index) {
        synchronized (stateLock) {
            return index < bound.size() ? bound.get(index).socket.getLocalPort() : config.listeners.get(index).port;
        }
    }

    /** @return true between start and the end of stop */
    public boolean isAlive() {
        return state == STARTED || state == STOPPING;
    }

    boolean isStopping() {
        return state >= STOPPING;
    }

    boolean isUnderLoad() {
        return underLoad;
    }

    // ---------------------------------------------------------------- accept

    void acceptLoop(Bound b) {
        int failures = 0;
        while (state == STARTED) {
            Socket s;
            try {
                s = b.socket.accept();
                failures = 0;
            } catch (IOException e) {
                if (state != STARTED) {
                    break;
                }
                failures++;
                if (failures == 5 && log.isLoggable(Level.WARNING)) {
                    log.log(Level.WARNING, "accept keeps failing, backing off", e);
                }
                sleepQuietly(acceptBackoff(failures));
                continue;
            }
            accepted.incrementAndGet();
            if (!acquireAddress(s.getInetAddress())) {
                rejectedPerAddress.incrementAndGet();
                reject(s, b.listener, false);
                continue;
            }
            pool.submit(s, b.listener);
        }
    }

    /** Backoff after {@code failures} consecutive accept failures: 10 ms doubling up to 1 s. */
    static long acceptBackoff(int failures) {
        return Math.min(1000, 10L << Math.min(failures - 1, 7));
    }

    boolean acquireAddress(InetAddress address) {
        synchronized (perAddress) {
            int[] n = perAddress.get(address);
            if (n == null) {
                perAddress.put(address, new int[] {1});
                return true;
            }
            if (n[0] >= config.maxConnectionsPerAddress) {
                return false;
            }
            n[0]++;
            return true;
        }
    }

    void releaseAddress(InetAddress address) {
        synchronized (perAddress) {
            int[] n = perAddress.get(address);
            if (n != null && --n[0] <= 0) {
                perAddress.remove(address);
            }
        }
    }

    /**
     * Refuses a socket without a worker: plain listeners get a prebuilt 503, TLS listeners a bare close (the acceptor
     * must not block on a handshake). The socket then lingers on the watchdog so the 503 is not reset.
     */
    void reject(Socket s, ServerConfig.Listener listener, boolean addressCounted) {
        if (addressCounted) {
            releaseAddress(s.getInetAddress());
        }
        if (listener.tls != null) {
            closeQuietly(s);
            return;
        }
        try {
            OutputStream out = s.getOutputStream();
            out.write(ErrorResponses.serviceUnavailable());
            socketWrites.incrementAndGet();
            s.shutdownOutput();
        } catch (IOException e) {
            closeQuietly(s);
            return;
        }
        synchronized (lingering) {
            lingering.add(new Lingering(s, System.currentTimeMillis() + config.lingerTimeout));
        }
    }

    // ---------------------------------------------------------------- connections

    /** Runs one connection on the current worker thread. */
    void runConnection(Socket socket, ServerConfig.Listener listener, WorkerPool.Worker worker) {
        Connection c = new Connection(this, socket, listener, worker);
        registry.add(c);
        try {
            c.run();
        } finally {
            registry.remove(c);
            releaseAddress(socket.getInetAddress());
        }
    }

    /** Closes the idle connection that has waited longest, to free its worker for a queued socket. */
    boolean evictOneIdle() {
        Connection oldest = null;
        for (Connection c : registry) {
            if (c.phase.get() == Connection.IDLE && (oldest == null || c.phaseSince < oldest.phaseSince)) {
                oldest = c;
            }
        }
        if (oldest != null && oldest.phase.compareAndSet(Connection.IDLE, Connection.CLOSING)) {
            evictions.incrementAndGet();
            oldest.abortSocket();
            return true;
        }
        return false;
    }

    int busyWorkers() {
        int n = 0;
        for (Connection c : registry) {
            if (Connection.isBusy(c.phase.get())) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- watchdog

    void watchdogLoop() {
        while (state != STOPPED) {
            try {
                watchdogTick(System.currentTimeMillis());
            } catch (RuntimeException e) {
                if (log.isLoggable(Level.WARNING)) {
                    log.log(Level.WARNING, "watchdog error", e);
                }
            }
            synchronized (stateLock) {
                if (state == STOPPED) {
                    break;
                }
                try {
                    stateLock.wait(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        synchronized (lingering) {
            for (Lingering l : lingering) {
                closeQuietly(l.socket);
            }
            lingering.clear();
        }
    }

    void watchdogTick(long now) {
        int busy = 0;
        int reducedIdle = config.idleTimeoutUnderLoad;
        for (Connection c : registry) {
            int p = c.phase.get();
            if (Connection.isBusy(p)) {
                busy++;
            }
            long ws = c.writeSince;
            if (ws != 0 && now - ws > config.writeTimeout) {
                writeTimeouts.incrementAndGet();
                c.writeTimedOut = true;
                c.abortSocket();
            } else if (p == Connection.READING_HEADERS && now - c.phaseSince > config.headerTimeout + 2000L
                    || p == Connection.HANDSHAKING && now - c.phaseSince > c.listener.tls.getHandshakeTimeout() + 2000L) {
                c.abortSocket();
            } else if (p == Connection.IDLE && underLoad && now - c.phaseSince > reducedIdle
                    && c.phase.compareAndSet(Connection.IDLE, Connection.CLOSING)) {
                idleTimeouts.incrementAndGet();
                c.abortSocket();
            }
        }
        // Adaptive idle timeout with hysteresis: above 75% busy shorten, below 60% restore.
        if (!underLoad && busy * 4 > config.maxWorkers * 3) {
            underLoad = true;
        } else if (underLoad && busy * 5 < config.maxWorkers * 3) {
            underLoad = false;
        }
        pool.expireQueued(now);
        if (pool.queued() > 0) {
            evictOneIdle();
        }
        synchronized (lingering) {
            for (Iterator<Lingering> it = lingering.iterator(); it.hasNext();) {
                Lingering l = it.next();
                if (now >= l.deadline || !l.discardAvailable()) {
                    closeQuietly(l.socket);
                    it.remove();
                }
            }
        }
    }

    private static final class Lingering {

        final Socket socket;

        final long deadline;

        Lingering(Socket socket, long deadline) {
            this.socket = socket;
            this.deadline = deadline;
        }

        /** Reads only what is available, so the watchdog never blocks. @return false on error */
        boolean discardAvailable() {
            try {
                InputStream in = socket.getInputStream();
                int n;
                byte[] scratch = null;
                while ((n = in.available()) > 0) {
                    if (scratch == null) {
                        scratch = new byte[Math.min(n, 4096)];
                    }
                    if (in.read(scratch, 0, Math.min(n, scratch.length)) < 0) {
                        return false;
                    }
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    // ---------------------------------------------------------------- stop

    /**
     * Stops: no new connections, idle connections and event streams closed at once, in-flight requests get
     * {@code graceMillis}, then remaining sockets are closed and workers interrupted.
     *
     * @param graceMillis grace period
     */
    public void stop(long graceMillis) {
        synchronized (stateLock) {
            if (state != STARTED) {
                return;
            }
            state = STOPPING;
        }
        for (Bound b : bound) {
            closeQuietly(b.socket);
        }
        pool.shutdown();
        long deadline = System.currentTimeMillis() + graceMillis;
        for (Connection c : registry) {
            c.onServerStopping();
        }
        while (!registry.isEmpty() && System.currentTimeMillis() < deadline) {
            sleepQuietly(10);
            for (Connection c : registry) {
                if (c.phase.get() == Connection.IDLE) {
                    c.onServerStopping();
                }
            }
        }
        for (Connection c : registry) {
            c.abortSocket();
        }
        pool.interruptAll();
        try {
            for (Bound b : bound) {
                if (b.acceptor != null) {
                    b.acceptor.join(1000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        workersLeftAfterStop = pool.awaitWorkers(500);
        if (workersLeftAfterStop > 0 && log.isLoggable(Level.WARNING)) {
            log.log(Level.WARNING, workersLeftAfterStop + " worker threads still running after stop", null);
        }
        synchronized (stateLock) {
            state = STOPPED;
            stateLock.notifyAll();
        }
    }

    /**
     * @param millis how long to wait
     * @return true once stopped
     * @throws InterruptedException if interrupted
     */
    public boolean awaitTermination(long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        synchronized (stateLock) {
            while (state != STOPPED) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                stateLock.wait(left);
            }
            return true;
        }
    }

    /** @return a snapshot of the counters */
    public ServerStats getStats() {
        return new ServerStats(accepted.get(), registry.size(), pool.queued(), busyWorkers(), pool.threadCount(),
                requests.get(), rejectedOverCapacity.get(), rejectedPerAddress.get(), idleTimeouts.get(),
                headerTimeouts.get(), bodyReadTimeouts.get(), writeTimeouts.get(), evictions.get(),
                workersLeftAfterStop, eventStreams.get(), slowConsumerCloses.get(), upgraded.get(), socketWrites.get());
    }

    // ---------------------------------------------------------------- helpers

    static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing left to do
            }
        }
    }

    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Names worker threads {@code tinyhttpd-worker-n} and passes the configured stack size. */
    static final class DefaultThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        private final ServerConfig config;

        private final TinyLog log;

        DefaultThreadFactory(ServerConfig config, TinyLog log) {
            this.config = config;
            this.log = log;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(null, r, "tinyhttpd-worker-" + counter.incrementAndGet(), config.workerStackBytes);
            t.setDaemon(config.daemon);
            t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {

                @Override
                public void uncaughtException(Thread thread, Throwable e) {
                    log.log(Level.SEVERE, "uncaught exception in " + thread.getName(), e);
                }
            });
            return t;
        }
    }
}
