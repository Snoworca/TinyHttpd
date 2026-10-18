package com.snoworca.tinyhttpd.server;

import com.snoworca.tinyhttpd.http.HttpStatus;

/**
 * Responses the engine writes without a handler: parse errors and rejections. Each is a fixed part prepared once plus
 * the cached {@code Date} line; they always close the connection.
 */
final class ErrorResponses {

    private static final int[] STATUSES = {400, 408, 413, 414, 417, 421, 431, 501, 503, 505};

    private static final byte[][] HEADS = new byte[STATUSES.length][];

    private static final byte[][] TAILS = new byte[STATUSES.length][];

    static {
        for (int i = 0; i < STATUSES.length; i++) {
            int s = STATUSES[i];
            String body = s + " " + HttpStatus.reason(s) + "\n";
            HEADS[i] = ("HTTP/1.1 " + s + " " + HttpStatus.reason(s) + "\r\n").getBytes(Ascii.LATIN1);
            TAILS[i] = ("Content-Type: text/plain; charset=UTF-8\r\nContent-Length: " + body.length() + "\r\n"
                    + (s == 503 ? "Retry-After: 1\r\n" : "") + "Connection: close\r\n\r\n" + body).getBytes(Ascii.LATIN1);
        }
    }

    private ErrorResponses() {
    }

    /** @return the full response bytes for a parse error status */
    static byte[] forStatus(int status) {
        for (int i = 0; i < STATUSES.length; i++) {
            if (STATUSES[i] == status) {
                byte[] date = HttpDates.dateLine();
                byte[] out = new byte[HEADS[i].length + date.length + TAILS[i].length];
                System.arraycopy(HEADS[i], 0, out, 0, HEADS[i].length);
                System.arraycopy(date, 0, out, HEADS[i].length, date.length);
                System.arraycopy(TAILS[i], 0, out, HEADS[i].length + date.length, TAILS[i].length);
                return out;
            }
        }
        return forStatus(400);
    }

    static byte[] serviceUnavailable() {
        return forStatus(503);
    }
}
