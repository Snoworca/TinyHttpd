package com.snoworca.tinyhttpd.webserver;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.FileResponses;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MimeTypes;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.server.PercentCodec;

/**
 * Serves files under a root directory with {@link FileResponses} (ranges, validators, nosniff). Directories use an
 * index file, else a listing when enabled (off by default: 403). Paths are already normalized by the server; the
 * canonical file must still lie under the root, so symbolic links leading outside are refused unless allowed.
 * Settings may be changed while serving; other threads see a change at once.
 */
public final class StaticFileHandler implements Handler {

    private static final List<String> RISKY = Arrays.asList("text/html", "image/svg+xml", "application/xhtml+xml",
            "text/xml", "application/xml");

    private final File root;

    private final String rootPath;

    private final List<String> indexFiles = new CopyOnWriteArrayList<String>(Arrays.asList("index.html", "index.htm"));

    private final Map<String, WebServerPlugin> plugins = new ConcurrentHashMap<String, WebServerPlugin>();

    private volatile DirectoryListingHandler listing;

    private volatile boolean followSymlinksOutsideRoot;

    private volatile boolean untrustedContent;

    private volatile long maxFileBytes = -1;

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
     * @param bytes largest file served (403 above it), -1 or {@link TinyHttpd#UNLIMITED_LONG} for none;
     *        default -1
     * @return this handler
     */
    public StaticFileHandler maxFileBytes(long bytes) {
        maxFileBytes = bytes;
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
            // The client's URL decides where relative links in a listing or index page resolve, so test it, not
            // getPath(): under a Router mount "/share" reaches this handler as "/", and serving it there would point
            // the links above the mount.
            String uri = req.getRequestURI();
            if (!uri.endsWith("/")) {
                // Relative ("./name/") from the decoded, re-encoded last segment: never "//host" (an open redirect)
                // nor a scheme, and it stays inside a mount.
                String last = PercentCodec.decodePath("/" + uri.substring(uri.lastIndexOf('/') + 1));
                if (last == null) {
                    throw new HttpException(400, "invalid path");
                }
                StringBuilder location = new StringBuilder("./")
                        .append(PercentCodec.encode(last.substring(1))).append('/');
                if (req.getQueryString() != null) {
                    location.append('?').append(req.getQueryString());
                }
                res.sendRedirect(location.toString());
                return;
            }
            File index = null;
            for (String name : indexFiles) {
                File f = new File(file, name);
                if (f.isFile() && (followSymlinksOutsideRoot || insideRoot(f))) {
                    index = f;
                    break;
                }
            }
            if (index == null) {
                DirectoryListingHandler listing = this.listing;
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
        long max = maxFileBytes;
        if (max >= 0 && file.length() > max) {
            throw new HttpException(403, "file too large");
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

    private boolean insideRoot(File f) throws IOException {
        String c = f.getCanonicalFile().getPath();
        return c.equals(rootPath) || c.startsWith(rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator);
    }

    /** Maps a normalized request path to a file under the root, refusing escapes. */
    private File resolve(String path) throws IOException, HttpException {
        if (path.indexOf('\\') >= 0) {
            // A separator on Windows: "%5C..%5C" would climb out, even past followSymlinksOutsideRoot's lexical
            // safety (no dot segments). No portable file name needs it.
            throw new HttpException(400, "backslash in path");
        }
        File f = new File(root, path);
        // The path has no dot segments (rejected while parsing), so only a symbolic link can lead outside.
        if (!followSymlinksOutsideRoot && !insideRoot(f)) {
            throw new HttpException(403, "outside the root");
        }
        return f;
    }
}
