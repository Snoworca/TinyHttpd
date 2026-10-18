package com.snoworca.tinyhttpd.server;

import java.util.ArrayDeque;

/** A small pool of equal-sized buffers, allocated on first use. Empty pool means a fresh buffer (or null if capped). */
final class BufferPool {

    private final int size;

    private final int capacity;

    private final ArrayDeque<byte[]> free = new ArrayDeque<byte[]>();

    private int outstanding;

    BufferPool(int size, int capacity) {
        this.size = size;
        this.capacity = capacity;
    }

    /** @return a pooled or new buffer; never null */
    synchronized byte[] borrow() {
        outstanding++;
        byte[] b = free.pollLast();
        return b != null ? b : new byte[size];
    }

    /** @return a pooled buffer, or null when {@code capacity} buffers are already out */
    synchronized byte[] tryBorrow() {
        if (outstanding >= capacity) {
            return null;
        }
        return borrow();
    }

    synchronized void release(byte[] b) {
        outstanding--;
        if (b != null && b.length == size && free.size() < capacity) {
            free.addLast(b);
        }
    }

    int bufferSize() {
        return size;
    }
}
