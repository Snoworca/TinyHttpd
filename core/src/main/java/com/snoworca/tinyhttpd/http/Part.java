package com.snoworca.tinyhttpd.http;

import java.io.IOException;
import java.io.InputStream;

/**
 * One part of a multipart body.
 * <p>
 * Write uploads to a temporary name and rename them when the stream ended normally; a dropped upload throws
 * {@link TruncatedBodyException} and leaves the partial file to the application:
 *
 * <pre>
 * File tmp = new File(dir, name + ".part");
 * copy(part.getInputStream(), tmp);
 * tmp.renameTo(new File(dir, name));
 * </pre>
 */
public interface Part {

    /** @return the {@code name} of {@code Content-Disposition}, or null */
    String getName();

    /**
     * The submitted file name with directories, control characters and NUL removed. Never use it as a path as it
     * is; choosing where to store a file is the application's responsibility.
     *
     * @return the file name, or null if absent, empty, {@code .} or {@code ..}
     */
    String getSubmittedFileName();

    /** @return the part's {@code Content-Type}, or null */
    String getContentType();

    /**
     * @param name header name, case-insensitive
     * @return the first value, or null
     */
    String getHeader(String name);

    /** @return the part content, ending at the boundary */
    InputStream getInputStream();

    /**
     * Reads a small field as text in the part's charset, UTF-8 by default.
     *
     * @param maxBytes largest content accepted
     * @return the text
     * @throws IOException on failure
     * @throws HttpException 413 if the part is larger
     */
    String readString(int maxBytes) throws IOException, HttpException;
}
