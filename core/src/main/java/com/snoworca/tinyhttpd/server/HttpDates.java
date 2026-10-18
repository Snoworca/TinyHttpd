package com.snoworca.tinyhttpd.server;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * HTTP dates (RFC 9110 §5.6.7). The current {@code Date} value is formatted once per second and shared through a
 * volatile reference instead of per response.
 */
public final class HttpDates {

    private static final class Cached {

        final long second;

        final String value;

        final byte[] line;

        Cached(long second, String value) {
            this.second = second;
            this.value = value;
            this.line = ("Date: " + value + "\r\n").getBytes(Ascii.LATIN1);
        }
    }

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private static final SimpleDateFormat IMF = formatter("EEE, dd MMM yyyy HH:mm:ss 'GMT'");

    private static volatile Cached cached = new Cached(-1, "");

    private HttpDates() {
    }

    private static SimpleDateFormat formatter(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(GMT);
        f.setLenient(false);
        return f;
    }

    private static Cached current() {
        long now = System.currentTimeMillis();
        Cached c = cached;
        if (c.second != now / 1000) {
            c = new Cached(now / 1000, format(now));
            cached = c;
        }
        return c;
    }

    /** @return the current date as an IMF-fixdate */
    public static String now() {
        return current().value;
    }

    /** @return {@code "Date: <now>\r\n"} as bytes */
    static byte[] dateLine() {
        return current().line;
    }

    /**
     * @param millis time
     * @return the IMF-fixdate
     */
    public static String format(long millis) {
        synchronized (IMF) {
            return IMF.format(new Date(millis));
        }
    }

    /**
     * Parses IMF-fixdate, RFC 850 and asctime dates.
     *
     * @param value the header value
     * @return milliseconds
     * @throws IllegalArgumentException if no format matches
     */
    public static long parse(String value) {
        String v = value.trim();
        String[] patterns = {"EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy"};
        for (String p : patterns) {
            try {
                return formatter(p).parse(v).getTime();
            } catch (ParseException ignored) {
                // try the next format
            }
        }
        throw new IllegalArgumentException("not an HTTP date: " + value);
    }
}
