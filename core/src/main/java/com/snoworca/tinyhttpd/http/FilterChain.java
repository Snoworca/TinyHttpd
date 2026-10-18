package com.snoworca.tinyhttpd.http;

import java.io.IOException;

/** The remaining filters and the handler for one request. */
public interface FilterChain {

    /**
     * Runs the next filter, or the handler after the last filter.
     *
     * @param request the request, possibly wrapped
     * @param response the response, possibly wrapped
     * @throws IOException on I/O failure
     * @throws HttpException to answer with an error status
     */
    void doFilter(Request request, Response response) throws IOException, HttpException;
}
