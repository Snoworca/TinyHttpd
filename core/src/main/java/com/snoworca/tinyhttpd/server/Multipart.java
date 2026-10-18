package com.snoworca.tinyhttpd.server;

import java.io.IOException;

import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MultipartLimits;
import com.snoworca.tinyhttpd.http.MultipartReader;

/** Streaming multipart parser (P5). */
final class Multipart {

    private Multipart() {
    }

    static MultipartReader open(Exchange ex, MultipartLimits limits) throws IOException, HttpException {
        throw new UnsupportedOperationException("P5");
    }
}
