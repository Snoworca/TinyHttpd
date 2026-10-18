package com.snoworca.tinyhttpd.server;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * sendFile by buffered copy (P5-04: the engine accepts with {@code ServerSocket}, which has no channel, and TLS
 * needs a copy anyway). Copy buffers come from a small server-wide pool; when it is empty the response's own output
 * buffer is used, so memory stays bounded by the pool size.
 */
final class FileTransfer {

    private FileTransfer() {
    }

    static void send(ExchangeResponse res, File file, long offset, long length) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            send(res, raf.getChannel(), offset, length);
        } finally {
            raf.close();
        }
    }

    static void send(ExchangeResponse res, FileChannel ch, long offset, long length) throws IOException {
        if (res.isCommitted() || res.written > 0) {
            // The file length becomes Content-Length, so bytes written before would break the framing.
            throw new IllegalStateException("response already committed or written to");
        }
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("negative offset or length");
        }
        res.setContentLengthLong(length);
        if (res.isHead() || length == 0) {
            res.finish();
            return;
        }
        HttpServer server = res.server;
        byte[] pooled = server.sendFileBuffers.tryBorrow();
        byte[] buf = pooled != null ? pooled : new byte[Math.min(server.config.outputBufferBytes, 8192)];
        try {
            long pos = offset;
            long left = length;
            ByteBuffer bb = ByteBuffer.wrap(buf);
            while (left > 0) {
                bb.clear();
                bb.limit((int) Math.min(buf.length, left));
                int n = ch.read(bb, pos);
                if (n <= 0) {
                    res.abort(); // the file got shorter: the declared length cannot be met
                    throw new IOException("file ended " + left + " bytes early");
                }
                res.writeDirect(buf, 0, n);
                pos += n;
                left -= n;
            }
        } finally {
            if (pooled != null) {
                server.sendFileBuffers.release(pooled);
            }
        }
        res.finish();
    }
}
