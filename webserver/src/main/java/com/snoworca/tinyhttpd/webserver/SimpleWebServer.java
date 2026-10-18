package com.snoworca.tinyhttpd.webserver;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ServiceLoader;

import com.snoworca.tinyhttpd.TinyHttpd;

/**
 * Command line file server: {@code java -cp ... SimpleWebServer [-h host] [-p port] [-d dir] [--listing] [--cors
 * origins] [--untrusted]}. Plugins are loaded with {@link ServiceLoader}.
 */
public final class SimpleWebServer {

    private SimpleWebServer() {
    }

    /**
     * Builds and starts a server from command line arguments.
     *
     * @param args arguments
     * @return the started server
     * @throws IOException if the directory or port is unusable
     */
    public static TinyHttpd start(String... args) throws IOException {
        String host = null;
        int port = 8080;
        File dir = new File(".");
        boolean listing = false;
        boolean untrusted = false;
        String cors = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-h") || a.equals("--host")) {
                host = args[++i];
            } else if (a.equals("-p") || a.equals("--port")) {
                port = Integer.parseInt(args[++i]);
            } else if (a.equals("-d") || a.equals("--dir")) {
                dir = new File(args[++i]);
            } else if (a.equals("--listing")) {
                listing = true;
            } else if (a.equals("--untrusted")) {
                untrusted = true;
            } else if (a.equals("--cors")) {
                cors = args[++i];
            } else {
                throw new IllegalArgumentException("unknown option " + a);
            }
        }
        StaticFileHandler files = new StaticFileHandler(dir).directoryListing(listing).untrustedContent(untrusted);
        for (WebServerPlugin p : ServiceLoader.load(WebServerPlugin.class)) {
            files.plugin(p);
        }
        TinyHttpd.Builder b = TinyHttpd.builder().port(port).handler(files)
                .bindAddress(host == null ? null : InetAddress.getByName(host));
        if (cors != null) {
            b.filter(new CorsFilter(cors.split(",")));
        }
        TinyHttpd server = b.build();
        server.start();
        return server;
    }

    /**
     * @param args see the class comment
     * @throws Exception on startup failure
     */
    public static void main(String[] args) throws Exception {
        TinyHttpd server = start(args);
        System.out.println("Serving on port " + server.getPort());
        while (!server.awaitTermination(Long.MAX_VALUE)) {
            // runs until the process stops
        }
    }
}
