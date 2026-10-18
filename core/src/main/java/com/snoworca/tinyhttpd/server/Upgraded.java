package com.snoworca.tinyhttpd.server;

import java.io.IOException;

import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.UpgradedConnection;

/** Protocol upgrade (P8). */
final class Upgraded {

    volatile int readTimeout;

    volatile boolean serverStopping;

    static UpgradedConnection start(Connection c, ExchangeResponse res, String protocol)
            throws IOException, HttpException {
        throw new UnsupportedOperationException("P8");
    }
}
