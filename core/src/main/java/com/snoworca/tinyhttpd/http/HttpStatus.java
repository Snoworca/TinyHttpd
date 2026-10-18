package com.snoworca.tinyhttpd.http;

/** Status code constants and reason phrases (RFC 9110 §15). */
public final class HttpStatus {

    /** 100 */
    public static final int CONTINUE = 100;
    /** 101 */
    public static final int SWITCHING_PROTOCOLS = 101;
    /** 200 */
    public static final int OK = 200;
    /** 201 */
    public static final int CREATED = 201;
    /** 202 */
    public static final int ACCEPTED = 202;
    /** 204 */
    public static final int NO_CONTENT = 204;
    /** 205 */
    public static final int RESET_CONTENT = 205;
    /** 206 */
    public static final int PARTIAL_CONTENT = 206;
    /** 301 */
    public static final int MOVED_PERMANENTLY = 301;
    /** 302 */
    public static final int FOUND = 302;
    /** 303 */
    public static final int SEE_OTHER = 303;
    /** 304 */
    public static final int NOT_MODIFIED = 304;
    /** 307 */
    public static final int TEMPORARY_REDIRECT = 307;
    /** 308 */
    public static final int PERMANENT_REDIRECT = 308;
    /** 400 */
    public static final int BAD_REQUEST = 400;
    /** 401 */
    public static final int UNAUTHORIZED = 401;
    /** 403 */
    public static final int FORBIDDEN = 403;
    /** 404 */
    public static final int NOT_FOUND = 404;
    /** 405 */
    public static final int METHOD_NOT_ALLOWED = 405;
    /** 406 */
    public static final int NOT_ACCEPTABLE = 406;
    /** 408 */
    public static final int REQUEST_TIMEOUT = 408;
    /** 409 */
    public static final int CONFLICT = 409;
    /** 410 */
    public static final int GONE = 410;
    /** 411 */
    public static final int LENGTH_REQUIRED = 411;
    /** 412 */
    public static final int PRECONDITION_FAILED = 412;
    /** 413 */
    public static final int CONTENT_TOO_LARGE = 413;
    /** 414 */
    public static final int URI_TOO_LONG = 414;
    /** 415 */
    public static final int UNSUPPORTED_MEDIA_TYPE = 415;
    /** 416 */
    public static final int RANGE_NOT_SATISFIABLE = 416;
    /** 417 */
    public static final int EXPECTATION_FAILED = 417;
    /** 421 */
    public static final int MISDIRECTED_REQUEST = 421;
    /** 426 */
    public static final int UPGRADE_REQUIRED = 426;
    /** 429 */
    public static final int TOO_MANY_REQUESTS = 429;
    /** 431 */
    public static final int REQUEST_HEADER_FIELDS_TOO_LARGE = 431;
    /** 500 */
    public static final int INTERNAL_SERVER_ERROR = 500;
    /** 501 */
    public static final int NOT_IMPLEMENTED = 501;
    /** 503 */
    public static final int SERVICE_UNAVAILABLE = 503;
    /** 505 */
    public static final int HTTP_VERSION_NOT_SUPPORTED = 505;

    private HttpStatus() {
    }

    /**
     * @param status a status code
     * @return the reason phrase, or an empty string for unknown codes
     */
    public static String reason(int status) {
        switch (status) {
            case 100: return "Continue";
            case 101: return "Switching Protocols";
            case 200: return "OK";
            case 201: return "Created";
            case 202: return "Accepted";
            case 203: return "Non-Authoritative Information";
            case 204: return "No Content";
            case 205: return "Reset Content";
            case 206: return "Partial Content";
            case 207: return "Multi-Status";
            case 300: return "Multiple Choices";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 303: return "See Other";
            case 304: return "Not Modified";
            case 307: return "Temporary Redirect";
            case 308: return "Permanent Redirect";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 406: return "Not Acceptable";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 410: return "Gone";
            case 411: return "Length Required";
            case 412: return "Precondition Failed";
            case 413: return "Content Too Large";
            case 414: return "URI Too Long";
            case 415: return "Unsupported Media Type";
            case 416: return "Range Not Satisfiable";
            case 417: return "Expectation Failed";
            case 421: return "Misdirected Request";
            case 422: return "Unprocessable Content";
            case 426: return "Upgrade Required";
            case 428: return "Precondition Required";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            case 505: return "HTTP Version Not Supported";
            case 507: return "Insufficient Storage";
            default: return "";
        }
    }
}
