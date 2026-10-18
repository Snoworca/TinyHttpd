package com.snoworca.tinyhttpd.log;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logging abstraction so an Android app can route server logs to logcat. Callers check {@link #isLoggable} before
 * building a message. Levels: client aborts, parse errors, idle timeouts, rejections and evictions are FINE; repeated
 * accept failures and watchdog errors WARNING; unhandled handler exceptions SEVERE.
 */
public interface TinyLog {

    /**
     * @param level the level
     * @return true if messages at this level are recorded
     */
    boolean isLoggable(Level level);

    /**
     * @param level the level
     * @param message the message
     * @param thrown the exception, or null
     */
    void log(Level level, String message, Throwable thrown);

    /**
     * @param name logger name
     * @return a logger backed by {@code java.util.logging}
     */
    static TinyLog jul(String name) {
        final Logger logger = Logger.getLogger(name);
        return new TinyLog() {

            @Override
            public boolean isLoggable(Level level) {
                return logger.isLoggable(level);
            }

            @Override
            public void log(Level level, String message, Throwable thrown) {
                logger.log(level, message, thrown);
            }
        };
    }
}
