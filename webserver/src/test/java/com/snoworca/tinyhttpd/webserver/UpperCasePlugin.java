package com.snoworca.tinyhttpd.webserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/** Test plugin found through ServiceLoader: serves .up files in upper case. */
public class UpperCasePlugin implements WebServerPlugin {

    @Override
    public String[] extensions() {
        return new String[] {"up"};
    }

    @Override
    public String[] indexFiles() {
        return new String[0];
    }

    @Override
    public void serve(File file, Request req, Response res) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            byte[] b = new byte[(int) file.length()];
            int n = 0;
            while (n < b.length) {
                n += in.read(b, n, b.length - n);
            }
            res.setContentType("text/plain");
            res.getOutputStream().write(new String(b, "UTF-8").toUpperCase().getBytes("UTF-8"));
        } finally {
            in.close();
        }
    }
}
