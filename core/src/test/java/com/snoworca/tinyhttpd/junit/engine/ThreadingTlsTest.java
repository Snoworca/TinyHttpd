package com.snoworca.tinyhttpd.junit.engine;

import org.junit.Ignore;
import org.junit.Test;

/** ThreadingTest over TLS (P7-08). Excluded: tests that expect a plain-text 503 on rejection. */
public class ThreadingTlsTest extends ThreadingTest {

    private static final String NO_503 = "over capacity, a TLS listener closes without a 503 (P3-01; covered by TLS-14)";

    {
        tls = true;
    }

    @Override
    @Test
    @Ignore(NO_503)
    public void th01_idleConnectionsAreBoundedByMaxWorkers() {
    }

    @Override
    @Test
    @Ignore(NO_503)
    public void th02_overCapacityGets503() {
    }

    @Override
    @Test
    @Ignore(NO_503)
    public void th09_queuedConnectionTimesOutWith503() {
    }

    @Override
    @Test
    @Ignore(NO_503)
    public void th16_perAddressLimit() {
    }
}
