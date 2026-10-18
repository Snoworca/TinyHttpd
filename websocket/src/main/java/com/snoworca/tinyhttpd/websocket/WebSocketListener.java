package com.snoworca.tinyhttpd.websocket;

/**
 * Receives WebSocket events. All callbacks run on the connection's reading thread, one at a time; a slow callback
 * delays reading (and pong replies) for that connection only. Extend {@link WebSocketAdapter} to implement only some.
 */
public interface WebSocketListener {

    /** @param ws the open connection */
    void onOpen(WebSocket ws);

    /**
     * @param ws the connection
     * @param text a complete text message
     */
    void onText(WebSocket ws, String text);

    /**
     * @param ws the connection
     * @param data a complete binary message
     */
    void onBinary(WebSocket ws, byte[] data);

    /**
     * Called once when the connection ends, cleanly or not.
     *
     * @param ws the connection
     * @param code close code (1006 when the connection dropped without a close frame)
     * @param reason close reason, may be empty
     */
    void onClose(WebSocket ws, int code, String reason);
}
