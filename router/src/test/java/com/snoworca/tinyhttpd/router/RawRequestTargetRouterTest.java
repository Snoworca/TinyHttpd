package com.snoworca.tinyhttpd.router;

import static org.junit.Assert.*;

import com.snoworca.tinyhttpd.RequestTargetPolicy;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.TestServers;
import com.snoworca.tinyhttpd.testing.TestTls;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/** FR-HTTP-004 AC-5: the built-in router keeps its strict decoded-path contract under RAW. */
public class RawRequestTargetRouterTest {
    @Rule public Timeout timeout = Timeout.seconds(60);

    // FR-HTTP-004 AC-3, AC-5, AC-7
    @Test
    public void fr_HTTP_004_AC5_invalidRawPathCannotSelectADecodedRoute() throws Exception {
        for (boolean tls : new boolean[] {false, true}) {
            AtomicInteger calls = new AtomicInteger();
            Router router = new Router().get("/x/{path*}", (req, res) -> {
                calls.incrementAndGet(); res.getWriter().write(req.getPath());
            });
            TinyHttpd.Builder builder = TestServers.builder().port(0).hostCheck(false)
                    .requestTargetPolicy(RequestTargetPolicy.RAW).handler(router);
            if (tls) builder.tls(TestTls.server().build());
            TinyHttpd server = builder.build();
            try {
                server.start();
                for (String path : new String[] {"/x/a%2Fb", "/x/%00", "/x/%2e", "/x/%2e%2e", "/x/%ZZ"}) {
                    assertEquals(path, 400, get(server, tls, path).status());
                }
                assertEquals(0, calls.get());
                assertEquals("/x/a/b", get(server, tls, "/x/a//b").body);
                assertEquals("/x/%2e%2e", get(server, tls, "/x/%252e%252e").body);
                assertEquals(2, calls.get());
            } finally { server.stop(200); }
        }
    }

    private static RawConnection.RawResponse get(TinyHttpd server, boolean tls, String path) throws Exception {
        try (RawConnection c = new RawConnection(server.getPort(), 0, tls ? TestTls.socketFactory() : null)) {
            c.send("GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            return c.read();
        }
    }
}
