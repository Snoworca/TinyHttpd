package com.snoworca.tinyhttpd.webserver;

import java.io.File;
import java.io.IOException;

import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;

/**
 * Renders files of some extensions instead of sending them as they are (for example Markdown to HTML). Found with
 * {@link java.util.ServiceLoader} by {@link SimpleWebServer}; with R8, keep the implementation classes listed in
 * {@code META-INF/services/com.snoworca.tinyhttpd.webserver.WebServerPlugin}.
 */
public interface WebServerPlugin {

    /** @return lower-case file extensions this plugin serves, without dots */
    String[] extensions();

    /** @return extra index file names to look for in directories, may be empty */
    String[] indexFiles();

    /**
     * @param file an existing file under the root with one of the extensions
     * @param req the request
     * @param res the response
     * @throws IOException on failure
     * @throws HttpException to answer with an error status
     */
    void serve(File file, Request req, Response res) throws IOException, HttpException;
}
