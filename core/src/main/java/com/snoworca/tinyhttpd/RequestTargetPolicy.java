package com.snoworca.tinyhttpd;

/**
 * Controls whether strict decoded-path validation occurs before the handler. Transport syntax, Host and body
 * framing validation remain enabled in both modes.
 *
 * @since 3.1.1
 */
public enum RequestTargetPolicy {
    /** Default: reject invalid decoded paths before invoking any filter or handler. */
    STRICT,

    /**
     * Let a framework validate the original path/query obtained through Request.getRequestURI/getQueryString.
     * Request.getPath still validates strictly and throws IllegalArgumentException for an invalid decoded path;
     * the engine answers 400 if that validation failure escapes the handler before response commitment, or aborts
     * an already committed response. Built-in decoded-path consumers keep
     * their strict behavior. Applications using raw accessors must validate before routing or filesystem access.
     */
    RAW
}
