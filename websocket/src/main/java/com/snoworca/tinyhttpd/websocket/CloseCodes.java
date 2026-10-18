package com.snoworca.tinyhttpd.websocket;

/** Close status codes (RFC 6455 §7.4.1). */
public final class CloseCodes {

    /** 1000 */
    public static final int NORMAL = 1000;
    /** 1001: the server is stopping or the peer stopped answering pings. */
    public static final int GOING_AWAY = 1001;
    /** 1002 */
    public static final int PROTOCOL_ERROR = 1002;
    /** 1003 */
    public static final int UNSUPPORTED_DATA = 1003;
    /** 1005: no code in the close frame (never sent). */
    public static final int NO_STATUS = 1005;
    /** 1006: closed without a close frame (never sent). */
    public static final int ABNORMAL = 1006;
    /** 1007 */
    public static final int INVALID_PAYLOAD = 1007;
    /** 1008 */
    public static final int POLICY_VIOLATION = 1008;
    /** 1009 */
    public static final int MESSAGE_TOO_BIG = 1009;
    /** 1011 */
    public static final int INTERNAL_ERROR = 1011;

    private CloseCodes() {
    }
}
