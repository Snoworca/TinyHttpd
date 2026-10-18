package com.snoworca.tinyhttpd.bench;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/** The new engine with default settings. */
final class NewEngine implements Engine {

    private TinyHttpd server;

    @Override
    public int start() throws IOException {
        server = TinyHttpd.builder().bindAddress(InetAddress.getByName("127.0.0.1")).port(0).idleTimeout(60000)
                .maxConnectionsPerAddress(1000) // every bench client is 127.0.0.1
                .maxRequestsPerConnection(Integer.MAX_VALUE) // shortcut: the bench client does not reconnect
                .handler(new Handler() {

                    @Override
                    public void handle(Request req, Response res) throws IOException {
                        if ("/upload".equals(req.getPath())) {
                            InputStream in = req.getInputStream();
                            byte[] buf = new byte[64 * 1024];
                            while (in.read(buf) != -1) {
                                // discard
                            }
                        } else if ("/download".equals(req.getPath())) {
                            long size = Long.parseLong(req.getQueryParameter("size"));
                            res.setContentLengthLong(size);
                            OutputStream out = res.getOutputStream();
                            byte[] buf = new byte[64 * 1024];
                            for (long left = size; left > 0; left -= buf.length) {
                                out.write(buf, 0, (int) Math.min(buf.length, left));
                            }
                            return;
                        }
                        res.getOutputStream().write("ok".getBytes("US-ASCII"));
                    }
                }).build();
        server.start();
        return server.getPort();
    }

    @Override
    public void stop() {
        server.stop(1000);
    }
}
