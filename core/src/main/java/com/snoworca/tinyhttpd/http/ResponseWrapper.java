package com.snoworca.tinyhttpd.http;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.channels.FileChannel;

/**
 * Delegates every method to a wrapped response; override what a filter changes. Same role as Servlet's
 * {@code HttpServletResponseWrapper}.
 */
public class ResponseWrapper implements Response {

    private final Response wrapped;

    /** @param wrapped the response to delegate to */
    public ResponseWrapper(Response wrapped) {
        if (wrapped == null) {
            throw new IllegalArgumentException("wrapped");
        }
        this.wrapped = wrapped;
    }

    /** @return the wrapped response */
    public Response getWrapped() {
        return wrapped;
    }

    @Override
    public void setStatus(int status) {
        wrapped.setStatus(status);
    }
    @Override
    public int getStatus() {
        return wrapped.getStatus();
    }
    @Override
    public void setHeader(String name, String value) {
        wrapped.setHeader(name, value);
    }
    @Override
    public void addHeader(String name, String value) {
        wrapped.addHeader(name, value);
    }
    @Override
    public void setDateHeader(String name, long millis) {
        wrapped.setDateHeader(name, millis);
    }
    @Override
    public String getHeader(String name) {
        return wrapped.getHeader(name);
    }
    @Override
    public boolean containsHeader(String name) {
        return wrapped.containsHeader(name);
    }
    @Override
    public void setContentType(String type) {
        wrapped.setContentType(type);
    }
    @Override
    public String getContentType() {
        return wrapped.getContentType();
    }
    @Override
    public void setContentLengthLong(long length) {
        wrapped.setContentLengthLong(length);
    }
    @Override
    public void addCookie(Cookie cookie) {
        wrapped.addCookie(cookie);
    }
    @Override
    public boolean isCommitted() {
        return wrapped.isCommitted();
    }
    @Override
    public void flushBuffer() throws IOException {
        wrapped.flushBuffer();
    }
    @Override
    public void resetBuffer() {
        wrapped.resetBuffer();
    }
    @Override
    public void reset() {
        wrapped.reset();
    }
    @Override
    public void sendError(int status, String message) throws IOException {
        wrapped.sendError(status, message);
    }
    @Override
    public void sendRedirect(String location) throws IOException {
        wrapped.sendRedirect(location);
    }
    @Override
    public OutputStream getOutputStream() throws IOException {
        return wrapped.getOutputStream();
    }
    @Override
    public Writer getWriter() throws IOException {
        return wrapped.getWriter();
    }
    @Override
    public void setCloseConnection(boolean close) {
        wrapped.setCloseConnection(close);
    }
    @Override
    public void sendFile(File file, long offset, long length) throws IOException {
        wrapped.sendFile(file, offset, length);
    }
    @Override
    public void sendFile(FileChannel channel, long offset, long length) throws IOException {
        wrapped.sendFile(channel, offset, length);
    }
    @Override
    public EventStream startEventStream() throws IOException, HttpException {
        return wrapped.startEventStream();
    }
    @Override
    public UpgradedConnection upgrade(String protocol) throws IOException, HttpException {
        return wrapped.upgrade(protocol);
    }
}
