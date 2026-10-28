package com.snoworca.tinyhttpd.junit.engine;

/** FR-HTTP-004 AC-7: the same request-target contracts over actual TLS sockets. */
public class RequestTargetPolicyTlsTest extends RequestTargetPolicyTest {
    public RequestTargetPolicyTlsTest() { tls = true; }
}
