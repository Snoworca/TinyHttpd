package com.snoworca.tinyhttpd.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.snoworca.tinyhttpd.ServerConfig;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/** P1-03: repeated accept failures (EMFILE) back off from 10 ms up to 1 s instead of spinning. */
public class AcceptBackoffTest {

    @Test
    public void backoffDoublesUpToOneSecond() {
        assertEquals(10, HttpServer.acceptBackoff(1));
        assertEquals(20, HttpServer.acceptBackoff(2));
        assertEquals(640, HttpServer.acceptBackoff(7));
        assertEquals(1000, HttpServer.acceptBackoff(8));
        assertEquals(1000, HttpServer.acceptBackoff(100));
    }

    @Test
    public void failingAcceptDoesNotSpin() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        ServerConfig config = TinyHttpd.builder().handler(new Handler() {

            @Override
            public void handle(Request request, Response response) {
            }
        }).build().getConfig();
        HttpServer server = new HttpServer(config, new HttpServer.ServerSocketSource() {

            @Override
            public ServerSocket open(ServerConfig c) throws IOException {
                return new ServerSocket(0) {

                    @Override
                    public Socket accept() throws IOException {
                        calls.incrementAndGet();
                        throw new IOException("Too many open files");
                    }
                };
            }
        });
        server.start();
        Thread.sleep(500);
        server.stop(100);
        // 10+20+40+80+160 ms = 310 ms for 5 failures; a spinning loop would make thousands of calls.
        assertTrue("accept calls: " + calls.get(), calls.get() >= 4 && calls.get() <= 8);
    }
}
