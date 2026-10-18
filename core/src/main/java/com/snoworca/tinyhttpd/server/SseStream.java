package com.snoworca.tinyhttpd.server;

import java.io.IOException;

import com.snoworca.tinyhttpd.http.EventStream;
import com.snoworca.tinyhttpd.http.HttpException;

/** Server-Sent Events (P6). */
final class SseStream {

    static EventStream start(ExchangeResponse res) throws IOException, HttpException {
        throw new UnsupportedOperationException("P6");
    }

    void ownerReturned() {
    }

    void close() {
    }
}
