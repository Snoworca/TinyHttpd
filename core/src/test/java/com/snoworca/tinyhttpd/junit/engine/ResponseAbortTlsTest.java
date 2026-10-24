package com.snoworca.tinyhttpd.junit.engine;

/** FR-HTTP-003 AC-6: run the same cancellation contracts over TLS. */
public class ResponseAbortTlsTest extends ResponseAbortTest {
    public ResponseAbortTlsTest() { tls = true; }
}
