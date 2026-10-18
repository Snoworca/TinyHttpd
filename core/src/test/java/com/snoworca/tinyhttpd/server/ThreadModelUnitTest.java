package com.snoworca.tinyhttpd.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.snoworca.tinyhttpd.ServerConfig;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/** P3-02 phase CAS race and P3-06 default thread factory (TH-13). */
public class ThreadModelUnitTest {

    private static ServerConfig config() {
        return com.snoworca.tinyhttpd.testing.TestServers.builder().handler(new Handler() {

            @Override
            public void handle(Request request, Response response) {
            }
        }).workerStackBytes(128 * 1024).build().getConfig();
    }

    private static ServerConfig.Listener cfgListener(HttpServer server) {
        return server.config.listeners.get(0);
    }

    @Test
    public void evictionAndWorkerRaceHasOneWinner() throws Exception {
        HttpServer server = new HttpServer(config());
        for (int round = 0; round < 1000; round++) {
            final Connection c = new Connection(server, new Socket(), cfgListener(server), null);
            final AtomicInteger wins = new AtomicInteger();
            final CountDownLatch go = new CountDownLatch(1);
            Thread[] ts = new Thread[2];
            final int[] targets = {Connection.READING_HEADERS, Connection.CLOSING};
            for (int i = 0; i < 2; i++) {
                final int target = targets[i];
                ts[i] = new Thread(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (c.phase.compareAndSet(Connection.IDLE, target)) {
                            wins.incrementAndGet();
                        }
                    }
                });
                ts[i].start();
            }
            go.countDown();
            ts[0].join();
            ts[1].join();
            assertEquals(1, wins.get());
        }
    }

    @Test
    public void th13_defaultThreadFactory() {
        ServerConfig cfg = config();
        HttpServer.DefaultThreadFactory f = new HttpServer.DefaultThreadFactory(cfg, cfg.logger);
        Thread t = f.newThread(new Runnable() {

            @Override
            public void run() {
            }
        });
        assertEquals("tinyhttpd-worker-1", t.getName());
        assertTrue(t.isDaemon());
        assertEquals("tinyhttpd-worker-2", f.newThread(null).getName());
    }
}
