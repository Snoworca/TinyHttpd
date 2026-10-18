package com.snoworca.tinyhttpd.websocket;

/** A {@link WebSocketListener} that ignores every event; override what you need. */
public abstract class WebSocketAdapter implements WebSocketListener {

    @Override
    public void onOpen(WebSocket ws) {
    }

    @Override
    public void onText(WebSocket ws, String text) {
    }

    @Override
    public void onBinary(WebSocket ws, byte[] data) {
    }

    @Override
    public void onClose(WebSocket ws, int code, String reason) {
    }
}
