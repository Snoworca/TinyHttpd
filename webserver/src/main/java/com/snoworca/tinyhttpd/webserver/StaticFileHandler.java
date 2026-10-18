package com.snoworca.tinyhttpd.webserver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.snoworca.tinyhttpd.http.FileResponses;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MimeTypes;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/**
 * Serves files under a root directory with {@link FileResponses} (ranges, validators, nosniff). Directories use an
 * index file, else a listing when enabled (off by default: 403). Paths are already normalized by the server; the
 * canonical file must still lie under the root, so symbolic links leading outside are refused unless allowed.
 */
public final class StaticFileHandler implements Handler {

    private static final List<String> RISKY = Arrays.asList("text/html", "image/svg+xml", "application/xhtml+xml",
            "text/xml", "application/xml");

    private final File root;

    private final String rootPath;

    private final List<String> indexFiles = new ArrayList<String>(Arrays.asList("index.html", "index.htm"));

    private final Map<String, WebServerPlugin> plugins = new HashMap<String, WebServerPlugin>();

    private DirectoryListingHandler listing;

    private boolean followSymlinksOutsideRoot;

    private boolean untrustedContent;

    /**
     * @param root the directory to serve
     * @throws IOException if the root cannot be resolved
     */
    public StaticFileHandler(File root) throws IOException {
        this.root = root.getCanonicalFile();
        this.rootPath = this.root.getPath();
    }

    /**
     * @param enabled list directories without an index file (default false: 403)
     * @return this handler
     */
    public StaticFileHandler directoryListing(boolean enabled) {
        listing = enabled ? new DirectoryListingHandler(false) : null;
        return this;
    }

    /**
     * @param allowed serve files whose real path is outside the root (default false)
     * @return this handler
     */
    public StaticFileHandler followSymlinksOutsideRoot(boolean allowed) {
        followSymlinksOutsideRoot = allowed;
        return this;
    }

    /**
     * For roots holding user uploads: HTML, SVG and XML are sent as attachments and every response gets
     * {@code Content-Security-Policy: sandbox}, so an uploaded page cannot run script on this origin.
     *
     * @param enabled default false
     * @return this handler
     */
    public StaticFileHandler untrustedContent(boolean enabled) {
        untrustedContent = enabled;
        return this;
    }

    /**
     * @param plugin renders files of its extensions
     * @return this handler
     */
    public StaticFileHandler plugin(WebServerPlugin plugin) {
        for (String ext : plugin.extensions()) {
            plugins.put(ext.toLowerCase(Locale.US), plugin);
        }
        indexFiles.addAll(Arrays.asList(plugin.indexFiles()));
        return this;
    }

    @Override
    public void handle(Request req, Response res) throws IOException, HttpException {
        String method = req.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            throw new HttpException(405, "method not allowed").withHeader("Allow", "GET, HEAD");
        }
        File file = resolve(req.getPath());
        if (file.isDirectory()) {
            if (!req.getPath().endsWith("/")) {
                res.sendRedirect(req.getRequestURI() + "/" + (req.getQueryString() != null ? "?" + req.getQueryString() : ""));
                return;
            }
            File index = null;
            for (String name : indexFiles) {
                File f = new File(file, name);
                if (f.isFile()) {
                    index = f;
                    break;
                }
            }
            if (index == null) {
                if (listing == null) {
                    throw new HttpException(403, "directory listing disabled");
                }
                if (untrustedContent) {
                    res.setHeader("Content-Security-Policy", "sandbox");
                }
                listing.list(file, req, res);
                return;
            }
            file = index;
        }
        if (!file.isFile()) {
            throw new HttpException(404, "not found");
        }
        if (untrustedContent) {
            res.setHeader("Content-Security-Policy", "sandbox");
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        WebServerPlugin plugin = dot < 0 ? null : plugins.get(name.substring(dot + 1).toLowerCase(Locale.US));
        if (plugin != null) {
            plugin.serve(file, req, res);
            return;
        }
        String type = MimeTypes.get(name, "application/octet-stream");
        if (untrustedContent && RISKY.contains(type)) {
            FileResponses.attachment(res, name);
        }
        FileResponses.serve(req, res, file, type);
    }

    /** Maps a normalized request path to a file under the root, refusing escapes. */
    private File resolve(String path) throws IOException, HttpException {
        File f = new File(root, path);
        File canonical = f.getCanonicalFile();
        String c = canonical.getPath();
        boolean inside = c.equals(rootPath) || c.startsWith(rootPath.endsWith(File.separator) ? rootPath
                : rootPath + File.separator);
        // The path has no dot segments (rejected while parsing), so only a symbolic link can lead outside.
        if (!inside && !followSymlinksOutsideRoot) {
            throw new HttpException(403, "outside the root");
        }
        return f;
    }
}
