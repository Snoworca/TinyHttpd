package com.snoworca.tinyhttpd.junit.keepalive;

/** KeepAliveBaselineTest over TLS (P7-08). */
public class KeepAliveBaselineTlsTest extends KeepAliveBaselineTest {

    {
        tls = true;
    }
}
