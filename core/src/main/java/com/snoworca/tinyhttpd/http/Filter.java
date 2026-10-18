package com.snoworca.tinyhttpd.http;

import java.io.IOException;

/** Same shape as a Servlet filter: if {@link FilterChain#doFilter} is not called, the chain stops here. */
public interface Filter {

    /**
     * Filters a request.
     *
     * @param request the request
     * @param response the response
     * @param chain the rest of the chain, ending with the handler
     * @throws IOException on I/O failure
     * @throws HttpException to answer with an error status
     */
    void doFilter(Request request, Response response, FilterChain chain) throws IOException, HttpException;
}
