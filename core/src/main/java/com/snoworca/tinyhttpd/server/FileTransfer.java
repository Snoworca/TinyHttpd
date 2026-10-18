package com.snoworca.tinyhttpd.server;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;

/** sendFile (P5). */
final class FileTransfer {

    private FileTransfer() {
    }

    static void send(ExchangeResponse res, File file, long offset, long length) throws IOException {
        throw new UnsupportedOperationException("P5");
    }

    static void send(ExchangeResponse res, FileChannel ch, long offset, long length) throws IOException {
        throw new UnsupportedOperationException("P5");
    }
}
