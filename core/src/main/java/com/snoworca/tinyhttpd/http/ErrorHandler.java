package com.snoworca.tinyhttpd.http;

import java.io.IOException;

/**
 * Renders error bodies for {@link HttpException}, {@link Response#sendError}, unhandled exceptions (500) and engine
 * errors after the request was parsed. Parse errors use fixed bytes and never reach it. The status and headers are
 * already set; write the body. If it throws, the default plain-text body is used.
 */
public interface ErrorHandler {

    /** Writes {@code "<status> <reason>"} or the message as {@code text/plain}. */
    ErrorHandler PLAIN = new ErrorHandler() {

        @Override
        public void render(int status, String message, Request request, Response response) throws IOException {
            String text = message != null && !message.isEmpty() ? message : status + " " + HttpStatus.reason(status);
            byte[] body = (text + "\n").getBytes("UTF-8");
            response.setContentType("text/plain; charset=UTF-8");
            response.setContentLengthLong(body.length);
            response.getOutputStream().write(body);
        }
    };

    /**
     * @param status the error status
     * @param message message, may be null; escape it before putting it into HTML
     * @param request the request
     * @param response the reset response with the status set
     * @throws IOException on write failure
     */
    void render(int status, String message, Request request, Response response) throws IOException;
}
