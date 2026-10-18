package com.snoworca.tinyhttpd.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.testing.Clients;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.RawConnection.RawResponse;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okio.ByteString;

/** P8-03 WebSocket on the new API (MOD-13..19, MOD-26..29). */
public class WebSocketTest {

    @Rule
    public Timeout timeout = Timeout.seconds(60);

    private TinyHttpd server;

    private int port;

    private final BlockingQueue<String> serverEvents = new ArrayBlockingQueue<String>(10000);

    private volatile WebSocket last;

    private final CountDownLatch opened = new CountDownLatch(1);

    @After
    public void stop() {
        if (server != null) {
            server.stop(1000);
        }
    }

    private WebSocketHandler echoHandler() {
        return new WebSocketHandler(new WebSocketHandler.Factory() {

            @Override
            public WebSocketListener create(Request handshake) {
                return new WebSocketAdapter() {

                    @Override
                    public void onOpen(WebSocket ws) {
                        last = ws;
                        opened.countDown();
                    }

                    @Override
                    public void onText(WebSocket ws, String text) {
                        try {
                            ws.sendText(text);
                        } catch (IOException e) {
                            serverEvents.add("send failed");
                        }
                    }

                    @Override
                    public void onBinary(WebSocket ws, byte[] data) {
                        try {
                            ws.sendBinary(data);
                        } catch (IOException e) {
                            serverEvents.add("send failed");
                        }
                    }

                    @Override
                    public void onClose(WebSocket ws, int code, String reason) {
                        serverEvents.add("close " + code);
                    }
                };
            }
        });
    }

    private void start(WebSocketHandler handler, TinyHttpd.Builder b) throws IOException {
        server = b.port(0).hostCheck(false).handler(handler).build();
        server.start();
        port = server.getPort();
    }

    private static final String HANDSHAKE = "GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
            + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n";

    private RawConnection rawOpen() throws IOException {
        RawConnection c = new RawConnection(port);
        c.send(HANDSHAKE + "\r\n");
        RawResponse r = c.read(true);
        assertEquals(101, r.status());
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", r.header("sec-websocket-accept"));
        return c;
    }

    static byte[] frame(int opcode, byte[] payload, boolean masked, boolean fin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0) | opcode);
        int len = payload.length;
        int maskBit = masked ? 0x80 : 0;
        if (len < 126) {
            out.write(maskBit | len);
        } else if (len <= 0xffff) {
            out.write(maskBit | 126);
            out.write(len >> 8);
            out.write(len);
        } else {
            out.write(maskBit | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((long) len >> (8 * i)));
            }
        }
        byte[] mask = {1, 2, 3, 4};
        if (masked) {
            out.write(mask, 0, 4);
        }
        for (int i = 0; i < len; i++) {
            out.write(masked ? payload[i] ^ mask[i & 3] : payload[i]);
        }
        return out.toByteArray();
    }

    /** Reads one server frame: {opcode, payload}. */
    static Object[] readFrame(RawConnection c) throws IOException {
        InputStream in = c.input();
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = in.read();
        long len = b1 & 0x7f;
        if (len == 126) {
            len = in.read() << 8 | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = len << 8 | in.read();
            }
        }
        return new Object[] {b0 & 0x0f, c.readExactly((int) len)};
    }

    static int closeCode(Object[] frame) {
        byte[] p = (byte[]) frame[1];
        return (p[0] & 0xff) << 8 | p[1] & 0xff;
    }

    @Test
    public void mod13_okHttpEcho() throws Exception {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder());
        OkHttpClient client = Clients.okHttp();
        final BlockingQueue<Object> got = new ArrayBlockingQueue<Object>(10);
        okhttp3.WebSocket ws = client.newWebSocket(new okhttp3.Request.Builder().url("ws://127.0.0.1:" + port + "/ws")
                .build(), new okhttp3.WebSocketListener() {

                    @Override
                    public void onMessage(okhttp3.WebSocket webSocket, String text) {
                        got.add(text);
                    }

                    @Override
                    public void onMessage(okhttp3.WebSocket webSocket, ByteString bytes) {
                        got.add(bytes);
                    }

                    @Override
                    public void onFailure(okhttp3.WebSocket webSocket, Throwable t, Response response) {
                        got.add(t);
                    }
                });
        try {
            ws.send("안녕");
            assertEquals("안녕", got.poll(3, TimeUnit.SECONDS));
            ws.send(ByteString.of(new byte[] {1, 2, 3}));
            assertEquals(ByteString.of(new byte[] {1, 2, 3}), got.poll(3, TimeUnit.SECONDS));
            byte[] big = new byte[200 * 1024];
            ws.send(ByteString.of(big));
            assertEquals(ByteString.of(big), got.poll(3, TimeUnit.SECONDS));
            ws.close(1000, "bye");
            assertEquals("close 1000", serverEvents.poll(3, TimeUnit.SECONDS));
        } finally {
            Clients.shutdown(client);
        }
    }

    @Test
    public void mod14_wrongVersion() throws IOException {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder());
        RawConnection c = new RawConnection(port);
        try {
            c.send(HANDSHAKE.replace("Version: 13", "Version: 8") + "\r\n");
            RawResponse r = c.read();
            assertEquals(426, r.status());
            assertEquals("13", r.header("sec-websocket-version"));
        } finally {
            c.close();
        }
    }

    @Test
    public void mod15_unmaskedFrame() throws IOException {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder());
        RawConnection c = rawOpen();
        try {
            c.send(frame(1, "hi".getBytes("UTF-8"), false, true));
            Object[] f = readFrame(c);
            assertEquals(8, f[0]);
            assertEquals(1002, closeCode(f));
        } finally {
            c.close();
        }
    }

    /** RFC 6455 7.4: codes that must not appear on the wire are a protocol error, not echoed. */
    @Test
    public void mod15_invalidCloseCode() throws IOException {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder());
        for (int code : new int[] {999, 1004, 1005, 1006, 1015, 1016, 2999, 5000}) {
            RawConnection c = rawOpen();
            try {
                c.send(frame(8, new byte[] {(byte) (code >> 8), (byte) code}, true, true));
                Object[] f = readFrame(c);
                assertEquals(8, f[0]);
                assertEquals("code " + code, 1002, closeCode(f));
            } finally {
                c.close();
            }
        }
        for (int code : new int[] {1000, 1001, 1003, 1007, 1011, 1014, 3000, 4999}) {
            RawConnection c = rawOpen();
            try {
                c.send(frame(8, new byte[] {(byte) (code >> 8), (byte) code}, true, true));
                assertEquals("code " + code, code, closeCode(readFrame(c)));
            } finally {
                c.close();
            }
        }
    }

    @Test
    public void mod16_tooLargeMessage() throws Exception {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder());
        RawConnection c = rawOpen();
        try {
            byte[] head = frame(2, new byte[0], true, true);
            // Claim 2 MB but send only the header: the server must refuse before allocating.
            byte[] claim = {(byte) 0x82, (byte) (0x80 | 127), 0, 0, 0, 0, 0, 0x20, 0, 0, 1, 2, 3, 4};
            assertTrue(head.length > 0);
            c.send(claim);
            Object[] f = readFrame(c);
            assertEquals(8, f[0]);
            assertEquals(1009, closeCode(f));
        } finally {
            c.close();
        }
    }

    @Test
    public void mod17_invalidUtf8() throws IOException {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder());
        RawConnection c = rawOpen();
        try {
            c.send(frame(1, new byte[] {(byte) 0xc3, 0x28}, true, true));
            Object[] f = readFrame(c);
            assertEquals(1007, closeCode(f));
        } finally {
            c.close();
        }
    }

    @Test
    public void mod18_silentClientIsDropped() throws Exception {
        start(echoHandler().pingInterval(300), com.snoworca.tinyhttpd.testing.TestServers.builder());
        RawConnection c = rawOpen();
        try {
            Object[] ping = readFrame(c);
            assertEquals(9, ping[0]);
            Object[] close = readFrame(c);
            assertEquals(8, close[0]);
            assertEquals(1001, closeCode(close));
        } finally {
            c.close();
        }
    }

    @Test
    public void mod19_upgradeLimitChangedWhileRunning() throws IOException {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder());
        server.setMaxUpgradedConnections(1);
        RawConnection a = rawOpen();
        RawConnection b = new RawConnection(port);
        RawConnection c = null;
        try {
            b.send(HANDSHAKE + "\r\n");
            assertEquals(503, b.read().status());
            server.setMaxUpgradedConnections(2);
            c = rawOpen();
        } finally {
            a.close();
            b.close();
            if (c != null) {
                c.close();
            }
        }
    }

    @Test
    public void mod19_upgradeLimit() throws IOException {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder().maxUpgradedConnections(1));
        RawConnection a = rawOpen();
        RawConnection b = new RawConnection(port);
        try {
            b.send(HANDSHAKE + "\r\n");
            assertEquals(503, b.read().status());
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    public void mod26_originCheck() throws IOException {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder());
        RawConnection c = new RawConnection(port);
        try {
            c.send(HANDSHAKE + "Origin: http://evil.example\r\n\r\n");
            assertEquals(403, c.read().status());
        } finally {
            c.close();
        }
        c = new RawConnection(port);
        try {
            c.send(HANDSHAKE.replace("Host: 127.0.0.1", "Host: 127.0.0.1:" + port) + "Origin: http://127.0.0.1:" + port
                    + "\r\n\r\n");
            assertEquals(101, c.read(true).status());
        } finally {
            c.close();
        }
        rawOpen().close();
    }

    @Test
    public void mod27_concurrentSendsDoNotInterleave() throws Exception {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder());
        final RawConnection c = rawOpen();
        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            final WebSocket ws = last;
            final int n = 1000;
            Thread sender = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        for (int i = 0; i < n; i++) {
                            ws.sendText("app-" + i);
                        }
                    } catch (IOException e) {
                        serverEvents.add("send failed " + e);
                    }
                }
            });
            sender.start();
            for (int i = 0; i < n; i++) {
                c.send(frame(9, ("p" + i).getBytes("UTF-8"), true, true));
            }
            int texts = 0;
            int pongs = 0;
            while (texts < n || pongs < n) {
                Object[] f = readFrame(c);
                String payload = new String((byte[]) f[1], "UTF-8");
                if ((Integer) f[0] == 1) {
                    assertEquals("app-" + texts++, payload);
                } else {
                    assertEquals(10, f[0]);
                    assertEquals("p" + pongs++, payload);
                }
            }
            sender.join();
        } finally {
            c.close();
        }
    }

    @Test
    public void mod28_stalledFrameHeaderClosesConnection() throws Exception {
        start(echoHandler().pingInterval(500), com.snoworca.tinyhttpd.testing.TestServers.builder());
        RawConnection c = rawOpen();
        try {
            c.send(new byte[] {(byte) 0x81, (byte) 0x85});
            c.setReadTimeout(3000);
            String state = c.awaitClose(3000);
            assertTrue(state, !"open".equals(state));
        } finally {
            c.close();
        }
    }

    @Test
    public void mod29_stopSendsGoingAway() throws Exception {
        start(echoHandler(), com.snoworca.tinyhttpd.testing.TestServers.builder());
        final RawConnection c = rawOpen();
        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            final List<Object[]> frames = new ArrayList<Object[]>();
            Thread reader = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        Object[] f = readFrame(c);
                        frames.add(f);
                        c.send(frame(8, (byte[]) f[1], true, true));
                    } catch (IOException e) {
                        frames.add(new Object[] {-1, new byte[0]});
                    }
                }
            });
            reader.start();
            server.stop(1000);
            reader.join(2000);
            assertEquals(8, frames.get(0)[0]);
            assertEquals(1001, closeCode(frames.get(0)));
        } finally {
            c.close();
        }
    }
}
