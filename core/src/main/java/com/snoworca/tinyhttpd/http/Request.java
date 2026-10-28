package com.snoworca.tinyhttpd.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An HTTP request. Methods with the same name as in {@code HttpServletRequest} have the same meaning; where the
 * meaning differs the name differs too ({@link #getQueryParameter} reads only the query, never the body).
 * <p>
 * Not thread safe: use it from the thread that handles the request.
 */
public interface Request {

    /** @return the method, case-sensitive ({@code GET}) */
    String getMethod();

    /** @return the raw, undecoded path of the request target, without the query; {@code *} for asterisk-form */
    String getRequestURI();

    /** @return the raw query after {@code ?}, or null */
    String getQueryString();

    /** @return {@code HTTP/1.1} or {@code HTTP/1.0} */
    String getProtocol();

    /** @return {@code http} or {@code https} */
    String getScheme();

    /** @return true for TLS connections */
    boolean isSecure();

    /**
     * The strictly decoded, normalized path; empty segments are merged and {@code +} stays {@code +}. With the
     * default STRICT request-target policy, invalid paths are rejected with 400 before dispatch. With the opt-in
     * RAW policy, raw accessors can expose such a path, but this decoded accessor still rejects dot segments,
     * encoded slashes, NUL, malformed escapes and invalid UTF-8. The engine answers 400 if its validation exception
     * escapes the handler before response commitment.
     *
     * @return the decoded path, starting with {@code /}
     * @throws IllegalArgumentException when a RAW request target cannot be decoded safely
     */
    String getPath();

    /**
     * @return the host from an absolute-form target, otherwise from the {@code Host} header, without port; may be
     *         null for HTTP/1.0 without {@code Host}
     */
    String getServerName();

    /** @return the port from the target or {@code Host}, otherwise the default port of the scheme */
    int getServerPort();

    /**
     * @param name header name, case-insensitive
     * @return the first value, or null
     */
    String getHeader(String name);

    /**
     * @param name header name, case-insensitive
     * @return all values in order; empty if none
     */
    List<String> getHeaders(String name);

    /** @return distinct header names in order of first appearance */
    Collection<String> getHeaderNames();

    /**
     * Every header occurrence once, in the order received: a name that appears twice appears twice, each at its own
     * position, spelled as the client sent it. Unlike {@link #getHeaders} and {@link #getHeaderNames}, which group by
     * name, this keeps the interleaving that a proxy or gateway must forward.
     * <p>
     * An implementation that does not override this method answers with the occurrences grouped by
     * {@link #getHeaderNames()} order, because it has no other order to give.
     *
     * @return a read-only list of read-only entries; never null
     */
    default List<Map.Entry<String, String>> getHeaderEntries() {
        List<Map.Entry<String, String>> out = new ArrayList<Map.Entry<String, String>>();
        for (String name : getHeaderNames()) {
            for (String value : getHeaders(name)) {
                out.add(new AbstractMap.SimpleImmutableEntry<String, String>(name, value));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * @param name header name
     * @return the date in milliseconds, or -1 if absent
     * @throws IllegalArgumentException if the value is not an HTTP date
     */
    long getDateHeader(String name);

    /** @return the {@code Content-Type} header, or null */
    String getContentType();

    /** @return the body length, 0 without body, -1 if unknown (chunked) */
    long getContentLengthLong();

    /** @return the {@code charset} parameter of {@code Content-Type}, or null */
    String getCharacterEncoding();

    /** @return cookies from the {@code Cookie} headers; malformed pairs are skipped */
    List<Cookie> getCookies();

    /**
     * @param name parameter name
     * @return the first value from the query string, or null
     */
    String getQueryParameter(String name);

    /**
     * @param name parameter name
     * @return all values from the query string; empty if none
     */
    List<String> getQueryParameterValues(String name);

    /** @return all query parameters, decoded leniently ({@code +} is a space, bad escapes stay as they are) */
    Map<String, List<String>> getQueryParameterMap();

    /**
     * The request body, bounded by its framing: reads end with -1 at the end of the body and never past it. With
     * {@code Expect: 100-continue} the server sends {@code 100 Continue} on the first read. {@code close()} does not
     * close the connection. Only one of this, {@link #readForm} and {@link #readMultipart} may be used.
     *
     * @return the body stream
     * @throws IOException on I/O failure
     */
    InputStream getInputStream() throws IOException;

    /**
     * Reads an {@code application/x-www-form-urlencoded} body.
     *
     * @param maxBytes largest body accepted, {@link com.snoworca.tinyhttpd.TinyHttpd#UNLIMITED} for none (the body is
     *        held in memory)
     * @return the decoded fields
     * @throws IOException on I/O failure
     * @throws HttpException 415 for another content type, 413 if the body is larger than {@code maxBytes}
     */
    Map<String, List<String>> readForm(int maxBytes) throws IOException, HttpException;

    /**
     * Reads a {@code multipart/form-data} body part by part without buffering it.
     *
     * @param limits size limits
     * @return the reader
     * @throws IOException on I/O failure
     * @throws HttpException 415 for another content type, 400 for a missing boundary
     */
    MultipartReader readMultipart(MultipartLimits limits) throws IOException, HttpException;

    /** @return the client address */
    String getRemoteAddr();

    /** @return the client port */
    int getRemotePort();

    /** @return the local address the connection arrived on */
    String getLocalAddr();

    /** @return the local port */
    int getLocalPort();

    /**
     * @param name attribute name
     * @return the attribute, or null
     */
    Object getAttribute(String name);

    /**
     * @param name attribute name
     * @param value the value; null removes the attribute
     */
    void setAttribute(String name, Object value);

    /** @param name attribute name */
    void removeAttribute(String name);
}
