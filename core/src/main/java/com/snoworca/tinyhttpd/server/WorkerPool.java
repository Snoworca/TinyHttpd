package com.snoworca.tinyhttpd.server;

import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadFactory;

import com.snoworca.tinyhttpd.ServerConfig;

/**
 * Bounded worker pool. Up to {@code maxWorkers} connections run at once (idle keep-alive connections included);
 * up to {@code maxConnections - maxWorkers} more wait in a queue while an idle connection is evicted to free a
 * worker. Workers are created on demand and exit after {@code workerKeepAlive} without work. A worker whose
 * connection became long-lived (event stream, upgrade) leaves the budget and exits when that connection ends.
 */
final class WorkerPool {

    final class Worker implements Runnable {

        Socket next;

        ServerConfig.Listener nextListener;

        boolean detached;

        Thread thread;

        Worker(Socket first, ServerConfig.Listener listener) {
            this.next = first;
            this.nextListener = listener;
        }

        @Override
        public void run() {
            try {
                Object[] job = take();
                while (job != null) {
                    server.runConnection((Socket) job[0], (ServerConfig.Listener) job[1], this);
                    job = afterConnection(this);
                }
            } finally {
                synchronized (lock) {
                    threads.remove(this);
                    idle.remove(this);
                    lock.notifyAll();
                }
            }
        }

        private Object[] take() {
            synchronized (lock) {
                Object[] job = {next, nextListener};
                next = null;
                nextListener = null;
                return job[0] == null ? null : job;
            }
        }
    }

    private static final class Queued {

        final Socket socket;

        final ServerConfig.Listener listener;

        final long since;

        Queued(Socket socket, ServerConfig.Listener listener, long since) {
            this.socket = socket;
            this.listener = listener;
            this.since = since;
        }
    }

    private final HttpServer server;

    private final ThreadFactory factory;

    private final Object lock = new Object();

    private final ArrayDeque<Queued> queue = new ArrayDeque<Queued>();

    private final ArrayDeque<Worker> idle = new ArrayDeque<Worker>();

    private final Set<Worker> threads = new HashSet<Worker>();

    /** Connections counted against maxWorkers. */
    private int active;

    private boolean stopping;

    WorkerPool(HttpServer server, ThreadFactory factory) {
        this.server = server;
        this.factory = factory;
    }

    void submit(Socket s, ServerConfig.Listener listener) {
        boolean evict = false;
        boolean reject = false;
        synchronized (lock) {
            if (stopping) {
                reject = true;
            } else if (active < server.config.getMaxWorkers()) {
                active++;
                dispatch(s, listener);
            } else if (active + queue.size() < server.config.getMaxConnections()) {
                queue.add(new Queued(s, listener, System.currentTimeMillis()));
                evict = true;
            } else {
                reject = true;
            }
        }
        if (reject) {
            server.rejectedOverCapacity.incrementAndGet();
            server.reject(s, listener, true);
        } else if (evict) {
            server.evictOneIdle();
        }
    }

    /** Starts queued connections a raised limit allows; wakes idle workers so a lowered limit retires them. */
    void limitsChanged() {
        synchronized (lock) {
            lock.notifyAll();
            Queued q;
            while (!stopping && active < server.config.getMaxWorkers() && (q = queue.poll()) != null) {
                active++;
                dispatch(q.socket, q.listener);
            }
        }
    }

    /** Holding the lock: hands the socket to an idle worker or starts a new one. */
    private void dispatch(Socket s, ServerConfig.Listener listener) {
        Worker w = idle.pollLast();
        if (w != null) {
            w.next = s;
            w.nextListener = listener;
            lock.notifyAll();
            return;
        }
        startWorker(s, listener);
    }

    private void startWorker(Socket s, ServerConfig.Listener listener) {
        Worker w = new Worker(s, listener);
        Thread t = factory.newThread(w);
        w.thread = t;
        threads.add(w);
        t.start();
    }

    /** Called by a worker after its connection ended. @return the next socket and listener, or null to exit */
    Object[] afterConnection(Worker w) {
        synchronized (lock) {
            if (w.detached) {
                return null;
            }
            if (active > server.config.getMaxWorkers()) { // the limit was lowered: retire this worker
                active--;
                return null;
            }
            Queued q = queue.poll();
            if (q != null) {
                return new Object[] {q.socket, q.listener};
            }
            active--;
            if (stopping) {
                return null;
            }
            idle.addLast(w);
            long deadline = System.currentTimeMillis() + server.config.getWorkerKeepAlive();
            // An idle worker above a lowered limit exits now instead of after workerKeepAlive.
            while (w.next == null && !stopping && active + idle.size() <= server.config.getMaxWorkers()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
            idle.remove(w);
            Socket s = w.next;
            Object[] job = {s, w.nextListener};
            w.next = null;
            w.nextListener = null;
            return s == null ? null : job;
        }
    }

    /** The worker's connection became long-lived: release its slot and start a worker for a queued socket. */
    void detach(Worker w) {
        if (w == null) {
            return;
        }
        synchronized (lock) {
            if (w.detached) {
                return;
            }
            w.detached = true;
            Queued q = queue.poll();
            if (q != null) {
                startWorker(q.socket, q.listener);
            } else {
                active--;
            }
        }
    }

    /** Rejects queued sockets that waited longer than {@code queueWaitTimeout}. */
    void expireQueued(long now) {
        List<Queued> expired = null;
        synchronized (lock) {
            for (Iterator<Queued> it = queue.iterator(); it.hasNext();) {
                Queued q = it.next();
                if (now - q.since > server.config.getQueueWaitTimeout()) {
                    it.remove();
                    if (expired == null) {
                        expired = new ArrayList<Queued>();
                    }
                    expired.add(q);
                }
            }
        }
        if (expired != null) {
            for (Queued q : expired) {
                server.rejectedOverCapacity.incrementAndGet();
                server.reject(q.socket, q.listener, true);
            }
        }
    }

    int queued() {
        synchronized (lock) {
            return queue.size();
        }
    }

    int threadCount() {
        synchronized (lock) {
            return threads.size();
        }
    }

    void shutdown() {
        List<Queued> dropped;
        synchronized (lock) {
            stopping = true;
            dropped = new ArrayList<Queued>(queue);
            queue.clear();
            lock.notifyAll();
        }
        for (Queued q : dropped) {
            server.reject(q.socket, q.listener, true); // 503 with FIN and drain, not a bare close
        }
    }

    void interruptAll() {
        synchronized (lock) {
            for (Worker w : threads) {
                if (w.thread != null) {
                    w.thread.interrupt();
                }
            }
        }
    }

    /** Waits for worker threads to exit. @return how many are still alive */
    int awaitWorkers(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        synchronized (lock) {
            while (!threads.isEmpty()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return threads.size();
        }
    }
}
