package com.snoworca.tinyhttpd.testing;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Collection;

/** CPU time of threads, to detect spinning workers (KA-06/07). */
public final class ThreadCpu {

    private ThreadCpu() {
    }

    /** @return comma-separated CPU milliseconds of the live threads */
    public static String millis(Collection<Thread> threads) {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        StringBuilder sb = new StringBuilder();
        synchronized (threads) {
            for (Thread t : threads) {
                if (t.isAlive()) {
                    long nanos = mx.getThreadCpuTime(t.getId());
                    sb.append(sb.length() == 0 ? "" : ", ").append(t.getName()).append('=').append(nanos / 1000000);
                }
            }
        }
        return sb.toString();
    }

    /** @return total CPU milliseconds of the live threads */
    public static long totalMillis(Collection<Thread> threads) {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long total = 0;
        synchronized (threads) {
            for (Thread t : threads) {
                if (t.isAlive()) {
                    total += Math.max(0, mx.getThreadCpuTime(t.getId()));
                }
            }
        }
        return total / 1000000;
    }
}
