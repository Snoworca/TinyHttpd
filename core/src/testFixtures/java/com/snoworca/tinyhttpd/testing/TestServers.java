package com.snoworca.tinyhttpd.testing;

import java.net.InetAddress;

import com.snoworca.tinyhttpd.TinyHttpd;

/** Builders for test servers. */
public final class TestServers {

    private TestServers() {
    }

    /**
     * A builder bound to 127.0.0.1. On macOS a wildcard bind is IPv6 dual-stack and the kernel may hand out a port
     * that another process already listens on at 127.0.0.1; loopback connections then reach that process.
     *
     * @return a builder
     */
    public static TinyHttpd.Builder builder() {
        return TinyHttpd.builder().bindAddress(InetAddress.getLoopbackAddress());
    }
}
