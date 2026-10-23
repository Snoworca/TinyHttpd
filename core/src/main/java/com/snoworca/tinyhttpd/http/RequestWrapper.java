package com.snoworca.tinyhttpd.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Delegates every method to a wrapped request; override what a filter changes. Same role as Servlet's
 * {@code HttpServletRequestWrapper}.
 */
public class RequestWrapper implements Request {

    private final Request wrapped;

    /** @param wrapped the request to delegate to */
    public RequestWrapper(Request wrapped) {
        if (wrapped == null) {
            throw new IllegalArgumentException("wrapped");
        }
        this.wrapped = wrapped;
    }

    /** @return the wrapped request */
    public Request getWrapped() {
        return wrapped;
    }

    @Override
    public String getMethod() {
        return wrapped.getMethod();
    }
    @Override
    public String getRequestURI() {
        return wrapped.getRequestURI();
    }
    @Override
    public String getQueryString() {
        return wrapped.getQueryString();
    }
    @Override
    public String getProtocol() {
        return wrapped.getProtocol();
    }
    @Override
    public String getScheme() {
        return wrapped.getScheme();
    }
    @Override
    public boolean isSecure() {
        return wrapped.isSecure();
    }
    @Override
    public String getPath() {
        return wrapped.getPath();
    }
    @Override
    public String getServerName() {
        return wrapped.getServerName();
    }
    @Override
    public int getServerPort() {
        return wrapped.getServerPort();
    }
    @Override
    public String getHeader(String name) {
        return wrapped.getHeader(name);
    }
    @Override
    public List<String> getHeaders(String name) {
        return wrapped.getHeaders(name);
    }
    @Override
    public Collection<String> getHeaderNames() {
        return wrapped.getHeaderNames();
    }

    @Override
    public java.util.List<java.util.Map.Entry<String, String>> getHeaderEntries() {
        return wrapped.getHeaderEntries();
    }
    @Override
    public long getDateHeader(String name) {
        return wrapped.getDateHeader(name);
    }
    @Override
    public String getContentType() {
        return wrapped.getContentType();
    }
    @Override
    public long getContentLengthLong() {
        return wrapped.getContentLengthLong();
    }
    @Override
    public String getCharacterEncoding() {
        return wrapped.getCharacterEncoding();
    }
    @Override
    public List<Cookie> getCookies() {
        return wrapped.getCookies();
    }
    @Override
    public String getQueryParameter(String name) {
        return wrapped.getQueryParameter(name);
    }
    @Override
    public List<String> getQueryParameterValues(String name) {
        return wrapped.getQueryParameterValues(name);
    }
    @Override
    public Map<String, List<String>> getQueryParameterMap() {
        return wrapped.getQueryParameterMap();
    }
    @Override
    public InputStream getInputStream() throws IOException {
        return wrapped.getInputStream();
    }
    @Override
    public Map<String, List<String>> readForm(int maxBytes) throws IOException, HttpException {
        return wrapped.readForm(maxBytes);
    }
    @Override
    public MultipartReader readMultipart(MultipartLimits limits) throws IOException, HttpException {
        return wrapped.readMultipart(limits);
    }
    @Override
    public String getRemoteAddr() {
        return wrapped.getRemoteAddr();
    }
    @Override
    public int getRemotePort() {
        return wrapped.getRemotePort();
    }
    @Override
    public String getLocalAddr() {
        return wrapped.getLocalAddr();
    }
    @Override
    public int getLocalPort() {
        return wrapped.getLocalPort();
    }
    @Override
    public Object getAttribute(String name) {
        return wrapped.getAttribute(name);
    }
    @Override
    public void setAttribute(String name, Object value) {
        wrapped.setAttribute(name, value);
    }
    @Override
    public void removeAttribute(String name) {
        wrapped.removeAttribute(name);
    }
}
