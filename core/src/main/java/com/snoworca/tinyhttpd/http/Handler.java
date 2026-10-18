package com.snoworca.tinyhttpd.http;

import java.io.IOException;

/**
 * Handles one request. When it returns, the server finishes the response and discards what is left of the request
 * body. Called on a worker thread; one handler instance serves all requests concurrently, so it must be thread safe.
 */
public interface Handler {

    /**
     * Handles a request.
     *
     * @param request the request
     * @param response the response to fill
     * @throws IOException when reading the request or writing the response fails
     * @throws HttpException to answer with an error status (only effective before the response is committed)
     */
    void handle(Request request, Response response) throws IOException, HttpException;
}
