package com.snoworca.tinyhttpd.webserver;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.util.Html;
import com.snoworca.tinyhttpd.server.PercentCodec;

/** HTML listing of a directory. Every name is HTML-escaped and every link percent-encoded. */
public final class DirectoryListingHandler {

    private final boolean showHidden;

    /** @param showHidden list names starting with {@code .} */
    public DirectoryListingHandler(boolean showHidden) {
        this.showHidden = showHidden;
    }

    /**
     * @param dir the directory
     * @param req the request, whose path names the directory
     * @param res the response
     * @throws IOException on write failure
     */
    public void list(File dir, Request req, Response res) throws IOException {
        String[] names = dir.list();
        if (names == null) {
            names = new String[0];
        }
        Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        String path = req.getPath().endsWith("/") ? req.getPath() : req.getPath() + "/";
        StringBuilder sb = new StringBuilder(256 + names.length * 64);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>").append(Html.escape(path))
                .append("</title></head><body><h1>").append(Html.escape(path)).append("</h1><ul>");
        if (!"/".equals(path)) {
            sb.append("<li><a href=\"../\">..</a></li>");
        }
        for (String n : names) {
            if (!showHidden && n.startsWith(".")) {
                continue;
            }
            boolean isDir = new File(dir, n).isDirectory();
            sb.append("<li><a href=\"").append(PercentCodec.encode(n)).append(isDir ? "/" : "").append("\">")
                    .append(Html.escape(n)).append(isDir ? "/" : "").append("</a></li>");
        }
        sb.append("</ul></body></html>");
        res.setContentType("text/html; charset=UTF-8");
        res.getOutputStream().write(sb.toString().getBytes("UTF-8"));
    }
}
