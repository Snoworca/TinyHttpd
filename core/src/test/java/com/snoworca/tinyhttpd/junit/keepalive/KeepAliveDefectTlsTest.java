package com.snoworca.tinyhttpd.junit.keepalive;

import org.junit.Ignore;
import org.junit.Test;

/** KeepAliveDefectTest over TLS (P7-08). */
public class KeepAliveDefectTlsTest extends KeepAliveDefectTest {

    {
        tls = true;
    }

    @Override
    @Test
    @Ignore("expects a plain-text 503 for connections over the per-address limit; over capacity, a TLS listener closes without a 503 (P3-01; covered by TLS-14)")
    public void ka12_idleConnectionsDoNotEachHoldAThread() {
    }
}
