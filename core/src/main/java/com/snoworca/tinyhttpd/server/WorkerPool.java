package com.snoworca.tinyhttpd.server;

import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadFactory;

/**
 * Bounded worker pool. Up to {@code maxWorkers} connections run at once (idle keep-alive connections included);
 * up to {@code maxConnections - maxWorkers} more wait in a queue while an idle connection is evicted to free a
 * worker. Workers are created on demand and exit after {@code workerKeepAlive} without work. A worker whose
 * connection became long-lived (event stream, upgrade) leaves the budget and exits when that connection ends.
 */
final class WorkerPool {

    final class Worker implements Runnable {

        Socket next;

        boolean detached;

        Thread thread;

        Worker(Socket first) {
            this.next = first;
        }

        @Override
        public void run() {
            try {
                Socket s = takeFirst();
                while (s != null) {
                    server.runConnection(s, this);
                    s = afterConnection(this);
                }
            } finally {
                synchronized (lock) {
                    threads.remove(this);
                    idle.remove(this);
                    lock.notifyAll();
                }
            }
        }

        private Socket takeFirst() {
            synchronized (lock) {
                Socket s = next;
                next = null;
                return s;
            }
        }
    }

    private static final class Queued {

        final Socket socket;

        final long since;

        Queued(Socket socket, long since) {
            this.socket = socket;
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

    void submit(Socket s) {
        boolean evict = false;
        boolean reject = false;
        synchronized (lock) {
            if (stopping) {
                reject = true;
            } else if (active < server.config.maxWorkers) {
                active++;
                dispatch(s);
            } else if (active + queue.size() < server.config.maxConnections) {
                queue.add(new Queued(s, System.currentTimeMillis()));
                evict = true;
            } else {
                reject = true;
            }
        }
        if (reject) {
            server.rejectedOverCapacity.incrementAndGet();
            server.reject(s, true);
        } else if (evict) {
            server.evictOneIdle();
        }
    }

    /** Holding the lock: hands the socket to an idle worker or starts a new one. */
    private void dispatch(Socket s) {
        Worker w = idle.pollLast();
        if (w != null) {
            w.next = s;
            lock.notifyAll();
            return;
        }
        startWorker(s);
    }

    private void startWorker(Socket s) {
        Worker w = new Worker(s);
        Thread t = factory.newThread(w);
        w.thread = t;
        threads.add(w);
        t.start();
    }

    /** Called by a worker after its connection ended. @return the next socket, or null to exit */
    Socket afterConnection(Worker w) {
        synchronized (lock) {
            if (w.detached) {
                return null;
            }
            Queued q = queue.poll();
            if (q != null) {
                return q.socket;
            }
            active--;
            if (stopping) {
                return null;
            }
            idle.addLast(w);
            long deadline = System.currentTimeMillis() + server.config.workerKeepAlive;
            while (w.next == null && !stopping) {
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
            w.next = null;
            if (s == null) {
                return null;
            }
            return s;
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
                startWorker(q.socket);
            } else {
                active--;
            }
        }
    }

    /** Rejects queued sockets that waited longer than {@code queueWaitTimeout}. */
    void expireQueued(long now) {
        List<Socket> expired = null;
        synchronized (lock) {
            for (Iterator<Queued> it = queue.iterator(); it.hasNext();) {
                Queued q = it.next();
                if (now - q.since > server.config.queueWaitTimeout) {
                    it.remove();
                    if (expired == null) {
                        expired = new ArrayList<Socket>();
                    }
                    expired.add(q.socket);
                }
            }
        }
        if (expired != null) {
            for (Socket s : expired) {
                server.rejectedOverCapacity.incrementAndGet();
                server.reject(s, true);
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
            server.releaseAddress(q.socket.getInetAddress());
            HttpServer.closeQuietly(q.socket);
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
