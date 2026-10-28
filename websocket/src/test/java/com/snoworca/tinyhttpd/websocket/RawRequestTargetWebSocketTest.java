package com.snoworca.tinyhttpd.websocket;

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

/** FR-HTTP-004 AC-6: path validation must precede the irreversible 101 upgrade. */
public class RawRequestTargetWebSocketTest {
    @Rule public Timeout timeout = Timeout.seconds(60);

    // FR-HTTP-004 AC-6, AC-7
    @Test
    public void fr_HTTP_004_AC6_bothPoliciesRetainActualWebSocketEcho() throws Exception {
        for (RequestTargetPolicy policy : RequestTargetPolicy.values()) {
            for (boolean tls : new boolean[] {false, true}) {
                TinyHttpd server = start(policy, tls, new AtomicInteger());
                try (RawConnection c = new RawConnection(server.getPort(), 0, tls ? TestTls.socketFactory() : null)) {
                    c.send(handshake("/ws/%61//b?x=%ZZ"));
                    assertEquals(101, c.read(true).status());
                    c.send(WebSocketTest.frame(1, "echo".getBytes("UTF-8"), true, true));
                    Object[] frame = WebSocketTest.readFrame(c);
                    assertNotNull(frame);
                    assertEquals(1, frame[0]);
                    assertEquals("echo", new String((byte[]) frame[1], "UTF-8"));
                    c.send(WebSocketTest.frame(8, new byte[] {3, (byte) 232}, true, true));
                    assertEquals(8, WebSocketTest.readFrame(c)[0]);
                } finally { server.stop(200); }
            }
        }
    }

    // FR-HTTP-004 AC-6: even a direct WebSocketHandler must reject before invoking its factory or emitting101.
    @Test
    public void fr_HTTP_004_AC6_invalidRawDecodedPathNeverUpgrades() throws Exception {
        for (boolean tls : new boolean[] {false, true}) {
            AtomicInteger factories = new AtomicInteger();
            TinyHttpd server = start(RequestTargetPolicy.RAW, tls, factories);
            try {
                for (String path : new String[] {"/ws/a%2Fb", "/ws/%00", "/ws/%2e", "/ws/%2e%2e", "/ws/%ZZ"}) {
                    try (RawConnection c = new RawConnection(server.getPort(), 0, tls ? TestTls.socketFactory() : null)) {
                        c.send(handshake(path));
                        RawConnection.RawResponse response = c.read();
                        assertEquals(path + " " + response.statusLine, 400, response.status());
                        assertNull(response.header("sec-websocket-accept"));
                        assertEquals(0, factories.get());
                    }
                }
            } finally { server.stop(200); }
        }
    }

    private static TinyHttpd start(RequestTargetPolicy policy, boolean tls, AtomicInteger factories) throws Exception {
        WebSocketHandler handler = new WebSocketHandler(req -> {
            factories.incrementAndGet();
            return new WebSocketAdapter() {
                @Override public void onText(WebSocket ws, String text) {
                    try { ws.sendText(text); } catch (java.io.IOException e) { throw new AssertionError(e); }
                }
            };
        });
        TinyHttpd.Builder builder = TestServers.builder().port(0).hostCheck(false)
                .requestTargetPolicy(policy).handler(handler);
        if (tls) builder.tls(TestTls.server().build());
        TinyHttpd server = builder.build();
        server.start();
        return server;
    }

    private static String handshake(String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";
    }
}
