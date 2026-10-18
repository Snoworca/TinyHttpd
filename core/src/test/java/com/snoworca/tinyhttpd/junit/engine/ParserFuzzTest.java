package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;

import org.junit.Test;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.log.TinyLog;

/**
 * P1-14: mutated requests must not cause unhandled exceptions, spinning workers or hanging connections. Runs 1,000
 * cases by default; {@code -Dfuzz.cases=10000} for the full run. A failing case prints its seed and bytes.
 */
public class ParserFuzzTest extends EngineTestBase {

    private static final String[] BASES = {
        "GET /a?b=c HTTP/1.1\r\nHost: x\r\nAccept: */*\r\n\r\n",
        "POST /p HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello",
        "POST /p HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n5;e=1\r\nhello\r\n0\r\nT: 1\r\n\r\n",
        "GET /a HTTP/1.0\r\nConnection: keep-alive\r\n\r\nGET /b HTTP/1.1\r\nHost: y\r\n\r\n",
        "PUT /u HTTP/1.1\r\nHost: x\r\nExpect: 100-continue\r\nContent-Length: 3\r\n\r\nabc",
    };

    private final List<String> severe = Collections.synchronizedList(new ArrayList<String>());

    @Test
    public void rq_fuzz_mutatedRequests() throws Exception {
        routes.put("*", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                InputStream in = req.getInputStream();
                byte[] b = new byte[512];
                while (in.read(b) != -1) {
                    // consume
                }
                text(res, "ok");
            }
        });
        start(TinyHttpd.builder().lingerTimeout(200).headerTimeout(1000).bodyReadTimeout(1000).logger(new TinyLog() {

            @Override
            public boolean isLoggable(Level level) {
                return level.intValue() >= Level.SEVERE.intValue();
            }

            @Override
            public void log(Level level, String message, Throwable thrown) {
                severe.add(message + " " + thrown);
            }
        }));
        int cases = Integer.getInteger("fuzz.cases", 1000);
        long seed = Long.getLong("fuzz.seed", 20261018L);
        Random random = new Random(seed);
        for (int i = 0; i < cases; i++) {
            byte[] input = mutate(BASES[random.nextInt(BASES.length)].getBytes("ISO-8859-1"), random);
            String outcome = send(input);
            assertTrue("case " + i + " seed " + seed + " hung: " + escape(input), !"hung".equals(outcome));
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (server.getStats().openConnections > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(server.getStats().toString(), 0, server.getStats().openConnections);
        assertEquals(severe.toString(), 0, severe.size());
    }

    private String send(byte[] input) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(4000);
            try {
                s.getOutputStream().write(input);
                s.shutdownOutput();
            } catch (IOException closedEarly) {
                return "closed";
            }
            InputStream in = s.getInputStream();
            byte[] b = new byte[4096];
            try {
                while (in.read(b) != -1) {
                    // read until the server closes
                }
            } catch (SocketTimeoutException e) {
                return "hung";
            } catch (IOException reset) {
                return "reset";
            }
            return "closed";
        } finally {
            s.close();
        }
    }

    private static byte[] mutate(byte[] in, Random r) {
        List<Byte> b = new ArrayList<Byte>();
        for (byte x : in) {
            b.add(x);
        }
        int edits = 1 + r.nextInt(4);
        for (int e = 0; e < edits && !b.isEmpty(); e++) {
            int at = r.nextInt(b.size());
            switch (r.nextInt(6)) {
                case 0:
                    b.remove(at);
                    break;
                case 1:
                    b.add(at, (byte) r.nextInt(256));
                    break;
                case 2:
                    b.set(at, (byte) r.nextInt(256));
                    break;
                case 3:
                    b.add(at, r.nextBoolean() ? (byte) '\r' : (byte) '\n');
                    break;
                case 4:
                    b.add(at, (byte) ('0' + r.nextInt(10)));
                    break;
                default:
                    for (int k = 0; k < 64; k++) {
                        b.add(at, (byte) 'A');
                    }
            }
        }
        byte[] out = new byte[b.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = b.get(i);
        }
        return out;
    }

    private static String escape(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int c = x & 0xff;
            sb.append(c >= 0x20 && c < 0x7f ? String.valueOf((char) c) : String.format("\\x%02x", c));
        }
        return sb.toString();
    }
}
