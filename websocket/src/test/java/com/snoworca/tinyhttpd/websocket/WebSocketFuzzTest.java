package com.snoworca.tinyhttpd.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.log.TinyLog;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.TestServers;

/** P9-05 (SEC-T03): mutated WebSocket frames end connections cleanly. {@code -Dfuzz.cases=10000} for the full run. */
public class WebSocketFuzzTest {

    @Rule
    public Timeout timeout = Timeout.seconds(300);

    @Test
    public void secT03_websocketFrames() throws Exception {
        final List<String> severe = Collections.synchronizedList(new ArrayList<String>());
        WebSocketHandler handler = new WebSocketHandler(new WebSocketHandler.Factory() {

            @Override
            public WebSocketListener create(Request handshake) {
                return new WebSocketAdapter() {

                    @Override
                    public void onText(WebSocket ws, String text) {
                        try {
                            ws.sendText(text);
                        } catch (IOException ignored) {
                            // peer gone
                        }
                    }
                };
            }
        }).maxMessageBytes(64 * 1024).maxFramePayloadBytes(64 * 1024).pingInterval(500);
        TinyHttpd server = TestServers.builder().port(0).hostCheck(false).handler(handler).maxUpgradedConnections(64)
                .logger(new TinyLog() {

                    @Override
                    public boolean isLoggable(Level level) {
                        return level.intValue() >= Level.SEVERE.intValue();
                    }

                    @Override
                    public void log(Level level, String message, Throwable thrown) {
                        severe.add(message + " " + thrown);
                    }
                }).build();
        server.start();
        try {
            ByteArrayOutputStream base = new ByteArrayOutputStream();
            base.write(WebSocketTest.frame(1, "hello".getBytes("UTF-8"), true, false));
            base.write(WebSocketTest.frame(0, " world".getBytes("UTF-8"), true, true));
            base.write(WebSocketTest.frame(9, "p".getBytes("UTF-8"), true, true));
            base.write(WebSocketTest.frame(2, new byte[300], true, true));
            base.write(WebSocketTest.frame(8, new byte[] {3, (byte) 0xe8}, true, true));
            byte[] frames = base.toByteArray();
            int cases = Integer.getInteger("fuzz.cases", 1000);
            Random random = new Random(Long.getLong("fuzz.seed", 20261018L));
            for (int i = 0; i < cases; i++) {
                byte[] input = mutate(frames, random);
                RawConnection c = new RawConnection(server.getPort());
                c.socket().setSoLinger(true, 0);
                try {
                    c.send("GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n");
                    assertEquals(101, c.read(true).status());
                    try {
                        c.send(input);
                        c.shutdownOutput();
                    } catch (IOException closedEarly) {
                        // the server already closed on a protocol error
                    }
                    c.setReadTimeout(4000);
                    InputStream in = c.input();
                    byte[] b = new byte[4096];
                    try {
                        while (in.read(b) != -1) {
                            // until the server closes
                        }
                    } catch (SocketTimeoutException e) {
                        throw new AssertionError("case " + i + " hung");
                    } catch (IOException reset) {
                        // fine
                    }
                } finally {
                    c.close();
                }
            }
            long deadline = System.currentTimeMillis() + 5000;
            while (server.getStats().openConnections > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(0, server.getStats().openConnections);
            assertEquals(0, server.getStats().upgradedConnections);
            assertTrue(severe.toString(), severe.isEmpty());
        } finally {
            server.stop(500);
        }
    }

    private static byte[] mutate(byte[] in, Random r) {
        byte[] b = in.clone();
        int edits = 1 + r.nextInt(4);
        for (int e = 0; e < edits; e++) {
            int at = r.nextInt(b.length);
            switch (r.nextInt(3)) {
                case 0:
                    b[at] = (byte) r.nextInt(256);
                    break;
                case 1:
                    b[at] ^= 1 << r.nextInt(8);
                    break;
                default:
                    b = java.util.Arrays.copyOf(b, Math.max(1, at));
            }
        }
        return b;
    }
}
