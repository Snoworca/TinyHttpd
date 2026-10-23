package com.snoworca.tinyhttpd.http;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.channels.FileChannel;

/**
 * An HTTP response. Methods with the same name as in {@code HttpServletResponse} have the same meaning. The server
 * owns the framing: it chooses {@code Content-Length}, chunked coding or close-delimited bodies and decides whether
 * the connection stays open.
 * <p>
 * Not thread safe: use it from the thread that handles the request.
 */
public interface Response {

    /**
     * Sets the status. Ignored after the commit, as in Servlet.
     *
     * @param status 200 to 599; 1xx statuses are sent by the server itself
     * @throws IllegalArgumentException for a 1xx or out-of-range status
     */
    void setStatus(int status);

    /** @return the status, 200 by default */
    int getStatus();

    /**
     * Replaces all values of a header. Ignored after the commit. {@code Content-Length} maps to
     * {@link #setContentLengthLong}, {@code Connection: close} to {@link #setCloseConnection}; {@code Transfer-Encoding}
     * is rejected and {@code Date}/{@code Keep-Alive} are managed by the server. {@code Connection: Upgrade} is sent
     * (as {@code Upgrade}, {@code Upgrade, close} or {@code Upgrade, keep-alive}) only when the same response also
     * has an {@code Upgrade} header and a status other than 101, for example a 426 offer; every other
     * {@code Connection} value is ignored.
     *
     * @param name header name (token characters)
     * @param value header value; null removes the header
     * @throws IllegalArgumentException for an invalid name or a value with CR, LF or NUL
     */
    void setHeader(String name, String value);

    /**
     * Adds a header line; repeated names produce repeated lines.
     *
     * @param name header name
     * @param value header value
     * @throws IllegalArgumentException for an invalid name or value
     */
    void addHeader(String name, String value);

    /**
     * @param name header name
     * @param millis the date as an IMF-fixdate
     */
    void setDateHeader(String name, long millis);

    /**
     * @param name header name
     * @return the first value, or null
     */
    String getHeader(String name);

    /**
     * @param name header name
     * @return true if set
     */
    boolean containsHeader(String name);

    /** @param type the {@code Content-Type} */
    void setContentType(String type);

    /** @return the {@code Content-Type}, or null */
    String getContentType();

    /**
     * Declares the body length. Writing more throws {@link ContentLengthExceededException}; writing less closes the
     * connection when the handler returns.
     *
     * @param length body length
     */
    void setContentLengthLong(long length);

    /** @param cookie added as a {@code Set-Cookie} header */
    void addCookie(Cookie cookie);

    /** @return true once the status line and headers were sent */
    boolean isCommitted();

    /**
     * Commits the headers and flushes buffered body bytes.
     *
     * @throws IOException on write failure
     */
    void flushBuffer() throws IOException;

    /** Discards the buffered body. @throws IllegalStateException after the commit */
    void resetBuffer();

    /** Discards status, headers and buffered body. @throws IllegalStateException after the commit */
    void reset();

    /**
     * Replaces the response with an error page from the server's {@link ErrorHandler}. Later writes are ignored.
     *
     * @param status error status
     * @param message message for the error page, may be null
     * @throws IOException on write failure
     * @throws IllegalStateException after the commit
     */
    void sendError(int status, String message) throws IOException;

    /**
     * Sends {@code 302 Found} with this {@code Location}.
     *
     * @param location absolute or relative URI
     * @throws IOException on write failure
     * @throws IllegalStateException after the commit
     */
    void sendRedirect(String location) throws IOException;

    /**
     * The body stream. Without a declared length a body that fits the output buffer gets a {@code Content-Length};
     * a larger one is chunked (HTTP/1.1) or ends with the connection (HTTP/1.0). For HEAD the bytes are counted and
     * dropped. {@code close()} ends the body, not the connection.
     *
     * @return the body stream
     * @throws IOException on failure
     * @throws IllegalStateException if {@link #getWriter()} was used
     */
    OutputStream getOutputStream() throws IOException;

    /**
     * A writer in the {@code charset} of the content type, or UTF-8 when none is given (Servlet uses ISO-8859-1). In
     * the UTF-8 case {@code ;charset=UTF-8} is appended to the content type.
     *
     * @return the writer
     * @throws IOException on failure
     * @throws IllegalStateException if {@link #getOutputStream()} was used
     */
    Writer getWriter() throws IOException;

    /** @param close true to close the connection after this response ({@code Connection: close}) */
    void setCloseConnection(boolean close);

    /**
     * Sends a region of a file as the whole body and ends the response. Status and headers such as
     * {@code Content-Range} are the caller's; this sets {@code Content-Length: length}.
     *
     * @param file the file
     * @param offset first byte
     * @param length number of bytes
     * @throws IOException on failure; if the file gets shorter the connection is closed
     * @throws IllegalStateException after the commit
     */
    void sendFile(File file, long offset, long length) throws IOException;

    /**
     * Like {@link #sendFile(File, long, long)} for an open channel, for example from an Android
     * {@code ParcelFileDescriptor}. The caller closes the channel.
     *
     * @param channel the channel
     * @param offset first byte
     * @param length number of bytes
     * @throws IOException on failure
     */
    void sendFile(FileChannel channel, long offset, long length) throws IOException;

    /**
     * Commits a {@code text/event-stream} response and returns the stream. The calling thread becomes the stream's
     * owner and leaves the worker budget for the long-lived budget.
     *
     * @return the event stream; already closed for HEAD
     * @throws IOException on failure
     * @throws HttpException 503 with {@code Retry-After} when {@code maxEventStreams} streams are open
     */
    EventStream startEventStream() throws IOException, HttpException;

    /**
     * Sends {@code 101 Switching Protocols} with {@code Upgrade: protocol} and the headers set so far, and hands over
     * the raw connection. HTTP processing ends for this connection.
     *
     * @param protocol the {@code Upgrade} token
     * @return the raw connection
     * @throws IOException on failure
     * @throws HttpException 503 when {@code maxUpgradedConnections} are in use
     */
    UpgradedConnection upgrade(String protocol) throws IOException, HttpException;
}
