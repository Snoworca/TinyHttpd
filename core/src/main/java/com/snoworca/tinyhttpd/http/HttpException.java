package com.snoworca.tinyhttpd.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thrown by a handler or filter to answer with an error status. Before the response is committed the server replaces
 * the response with an error response of this status and these headers; after the commit it closes the connection.
 */
public class HttpException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;

    private List<String> headers;

    /**
     * @param status the HTTP status, 400 to 599
     * @param message a short message for the error body; may be shown to the client
     */
    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * @param status the HTTP status
     * @param message a short message
     * @param cause the cause
     */
    public HttpException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** @return the HTTP status */
    public int getStatus() {
        return status;
    }

    /**
     * Adds a header to the error response, for example {@code Retry-After} or {@code Allow}.
     *
     * @param name header name
     * @param value header value
     * @return this exception
     */
    public HttpException withHeader(String name, String value) {
        if (headers == null) {
            headers = new ArrayList<String>(4);
        }
        headers.add(name);
        headers.add(value);
        return this;
    }

    /** @return name and value pairs, flattened: name0, value0, name1, value1, ... */
    public List<String> getHeaders() {
        return headers == null ? Collections.<String> emptyList() : Collections.unmodifiableList(headers);
    }
}
