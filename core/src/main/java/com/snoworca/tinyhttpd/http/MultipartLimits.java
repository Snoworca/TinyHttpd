package com.snoworca.tinyhttpd.http;

/** Limits for {@link Request#readMultipart}. Immutable; the {@code with} methods return copies. */
public final class MultipartLimits {

    private final long maxTotalBytes;

    private final int maxParts;

    private final long maxPartBytes;

    private final int maxPartHeaderBytes;

    private final int bufferBytes;

    private MultipartLimits(long maxTotalBytes, int maxParts, long maxPartBytes, int maxPartHeaderBytes, int bufferBytes) {
        if (maxTotalBytes <= 0 || maxParts <= 0 || maxPartBytes <= 0 || maxPartHeaderBytes <= 0 || bufferBytes < 1024) {
            throw new IllegalArgumentException("multipart limits must be positive, bufferBytes >= 1024");
        }
        this.maxTotalBytes = maxTotalBytes;
        this.maxParts = maxParts;
        this.maxPartBytes = maxPartBytes;
        this.maxPartHeaderBytes = maxPartHeaderBytes;
        this.bufferBytes = bufferBytes;
    }

    /**
     * @param maxTotalBytes largest body, {@link com.snoworca.tinyhttpd.TinyHttpd#UNLIMITED_LONG} for none
     * @param maxParts most parts, {@link com.snoworca.tinyhttpd.TinyHttpd#UNLIMITED} for none
     * @return limits with each part bounded by {@code maxTotalBytes}, 8 KB part headers and a 16 KB buffer
     */
    public static MultipartLimits of(long maxTotalBytes, int maxParts) {
        return new MultipartLimits(maxTotalBytes, maxParts, maxTotalBytes, 8 * 1024, 16 * 1024);
    }

    /**
     * @param bytes largest single part, {@link com.snoworca.tinyhttpd.TinyHttpd#UNLIMITED_LONG} for none
     * @return a copy
     */
    public MultipartLimits withMaxPartBytes(long bytes) {
        return new MultipartLimits(maxTotalBytes, maxParts, bytes, maxPartHeaderBytes, bufferBytes);
    }

    /**
     * @param bytes largest header block of a part, {@link com.snoworca.tinyhttpd.TinyHttpd#UNLIMITED} for none
     * @return a copy
     */
    public MultipartLimits withMaxPartHeaderBytes(int bytes) {
        return new MultipartLimits(maxTotalBytes, maxParts, maxPartBytes, bytes, bufferBytes);
    }

    /**
     * @param bytes parser buffer size; allocated, so not {@link com.snoworca.tinyhttpd.TinyHttpd#UNLIMITED}
     * @return a copy
     */
    public MultipartLimits withBufferBytes(int bytes) {
        return new MultipartLimits(maxTotalBytes, maxParts, maxPartBytes, maxPartHeaderBytes, bytes);
    }

    /** @return largest body */
    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }

    /** @return most parts */
    public int getMaxParts() {
        return maxParts;
    }

    /** @return largest part */
    public long getMaxPartBytes() {
        return maxPartBytes;
    }

    /** @return largest part header block */
    public int getMaxPartHeaderBytes() {
        return maxPartHeaderBytes;
    }

    /** @return parser buffer size */
    public int getBufferBytes() {
        return bufferBytes;
    }
}
