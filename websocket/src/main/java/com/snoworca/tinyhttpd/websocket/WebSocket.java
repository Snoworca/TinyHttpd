package com.snoworca.tinyhttpd.websocket;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.UpgradedConnection;

/**
 * One WebSocket connection (RFC 6455, server side). Send methods may be called from any thread; frames are written
 * whole under one lock, so application sends and pong replies never interleave. The connection keeps a snapshot of
 * the handshake request.
 */
public final class WebSocket {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final int OP_CONT = 0, OP_TEXT = 1, OP_BINARY = 2, OP_CLOSE = 8, OP_PING = 9, OP_PONG = 10;

    private final UpgradedConnection conn;

    private final InputStream in;

    private final OutputStream out;

    private final WebSocketListener listener;

    private final WebSocketHandler config;

    private final Object writeLock = new Object();

    private final String path;

    private final String query;

    private final String remoteAddr;

    private final List<String> headerNames;

    private final List<String> headerValues;

    private final Map<String, List<String>> queryParams;

    private final String subprotocol;

    private volatile boolean closeSent;

    private volatile boolean open = true;

    private Object attachment;

    WebSocket(UpgradedConnection conn, Request handshake, String subprotocol, WebSocketListener listener,
            WebSocketHandler config) {
        this.conn = conn;
        this.in = conn.getInputStream();
        this.out = conn.getOutputStream();
        this.listener = listener;
        this.config = config;
        this.subprotocol = subprotocol;
        this.path = handshake.getPath();
        this.query = handshake.getQueryString();
        this.remoteAddr = handshake.getRemoteAddr();
        this.queryParams = handshake.getQueryParameterMap();
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        java.util.ArrayList<String> values = new java.util.ArrayList<String>();
        for (String n : handshake.getHeaderNames()) {
            for (String v : handshake.getHeaders(n)) {
                names.add(n);
                values.add(v);
            }
        }
        this.headerNames = Collections.unmodifiableList(names);
        this.headerValues = Collections.unmodifiableList(values);
    }

    // ------------------------------------------------------------------ handshake snapshot

    /** @return the decoded path of the handshake request */
    public String getPath() {
        return path;
    }

    /** @return the raw query of the handshake request, or null */
    public String getQueryString() {
        return query;
    }

    /**
     * @param name query parameter
     * @return its first value, or null
     */
    public String getQueryParameter(String name) {
        List<String> v = queryParams.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    /**
     * @param name header name, case-insensitive
     * @return the first value from the handshake, or null
     */
    public String getHeader(String name) {
        for (int i = 0; i < headerNames.size(); i++) {
            if (headerNames.get(i).equalsIgnoreCase(name)) {
                return headerValues.get(i);
            }
        }
        return null;
    }

    /** @return the client address */
    public String getRemoteAddr() {
        return remoteAddr;
    }

    /** @return the negotiated subprotocol, or null */
    public String getSubprotocol() {
        return subprotocol;
    }

    /** @return an application object stored with {@link #setAttachment} */
    public Object getAttachment() {
        return attachment;
    }

    /** @param attachment any application object */
    public void setAttachment(Object attachment) {
        this.attachment = attachment;
    }

    /** @return true until a close frame was sent or the connection dropped */
    public boolean isOpen() {
        return open && !closeSent;
    }

    // ------------------------------------------------------------------ sending

    /**
     * @param text message
     * @throws IOException if closed or the write fails
     */
    public void sendText(String text) throws IOException {
        sendFrame(OP_TEXT, text.getBytes(UTF8));
    }

    /**
     * @param data message
     * @throws IOException if closed or the write fails
     */
    public void sendBinary(byte[] data) throws IOException {
        sendFrame(OP_BINARY, data);
    }

    /**
     * @param payload at most 125 bytes
     * @throws IOException if closed or the write fails
     */
    public void ping(byte[] payload) throws IOException {
        if (payload.length > 125) {
            throw new IllegalArgumentException("control frame payload over 125 bytes");
        }
        sendFrame(OP_PING, payload);
    }

    /**
     * Starts the closing handshake; the connection ends when the peer answers or after a short wait.
     *
     * @param code close code
     * @param reason reason, at most 123 UTF-8 bytes
     */
    public void close(int code, String reason) {
        byte[] r = reason == null ? new byte[0] : reason.getBytes(UTF8);
        if (r.length > 123) {
            r = java.util.Arrays.copyOf(r, 123);
        }
        byte[] payload = new byte[2 + r.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(r, 0, payload, 2, r.length);
        synchronized (writeLock) {
            if (closeSent || !open) {
                return;
            }
            try {
                writeFrame(OP_CLOSE, payload);
            } catch (IOException e) {
                open = false;
            }
            closeSent = true;
        }
        try {
            conn.setReadTimeout(Math.max(1000, Math.min(config.pingIntervalMillis, 5000)));
        } catch (IOException ignored) {
            // the read loop ends either way
        }
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (closeSent || !open) {
                throw new IOException("websocket closed");
            }
            try {
                writeFrame(opcode, payload);
            } catch (IOException e) {
                open = false;
                throw e;
            }
        }
    }

    /** Holding writeLock: one write per frame (header and payload together). */
    private void writeFrame(int opcode, byte[] payload) throws IOException {
        int len = payload.length;
        int headerLen = len < 126 ? 2 : len <= 0xffff ? 4 : 10;
        byte[] frame = new byte[headerLen + len];
        frame[0] = (byte) (0x80 | opcode);
        if (len < 126) {
            frame[1] = (byte) len;
        } else if (len <= 0xffff) {
            frame[1] = 126;
            frame[2] = (byte) (len >> 8);
            frame[3] = (byte) len;
        } else {
            frame[1] = 127;
            for (int i = 0; i < 8; i++) {
                frame[2 + i] = (byte) ((long) len >> (56 - 8 * i));
            }
        }
        System.arraycopy(payload, 0, frame, headerLen, len);
        out.write(frame, 0, frame.length);
    }

    // ------------------------------------------------------------------ reading

    /** Thrown to close with a code. */
    private static final class Fail extends Exception {

        private static final long serialVersionUID = 1L;

        final int code;

        Fail(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Runs the read loop on the handler thread until the connection ends. */
    void run() {
        int closeCode = CloseCodes.ABNORMAL;
        String closeReason = "";
        conn.onServerStop(new Runnable() {

            @Override
            public void run() {
                close(CloseCodes.GOING_AWAY, "server stopping");
            }
        });
        try {
            conn.setReadTimeout(config.pingIntervalMillis);
            listener.onOpen(this);
            boolean awaitingPong = false;
            int messageOp = -1;
            ByteArrayOutputStream message = null;
            while (true) {
                int b0;
                try {
                    b0 = in.read();
                } catch (SocketTimeoutException idle) {
                    if (closeSent || awaitingPong) {
                        closeCode = closeSent ? closeCode : CloseCodes.GOING_AWAY;
                        if (!closeSent) {
                            close(CloseCodes.GOING_AWAY, "ping timeout");
                        }
                        break;
                    }
                    ping(new byte[0]);
                    awaitingPong = true;
                    continue;
                }
                if (b0 < 0) {
                    break;
                }
                awaitingPong = false;
                int b1 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                if ((b0 & 0x70) != 0) {
                    throw new Fail(CloseCodes.PROTOCOL_ERROR, "reserved bits set");
                }
                if ((b1 & 0x80) == 0) {
                    throw new Fail(CloseCodes.PROTOCOL_ERROR, "client frame not masked");
                }
                long len = b1 & 0x7f;
                if (len == 126) {
                    len = readByte() << 8 | readByte();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = len << 8 | readByte();
                    }
                    if (len < 0) {
                        throw new Fail(CloseCodes.PROTOCOL_ERROR, "bad length");
                    }
                }
                boolean control = opcode >= 8;
                if (control && (len > 125 || !fin)) {
                    throw new Fail(CloseCodes.PROTOCOL_ERROR, "bad control frame");
                }
                if (len > config.maxFramePayloadBytes) {
                    throw new Fail(CloseCodes.MESSAGE_TOO_BIG, "frame too large");
                }
                byte[] mask = readFully(new byte[4]);
                byte[] payload = readFully(new byte[(int) len]);
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
                switch (opcode) {
                    case OP_TEXT:
                    case OP_BINARY:
                        if (messageOp >= 0) {
                            throw new Fail(CloseCodes.PROTOCOL_ERROR, "new message inside a fragmented one");
                        }
                        if (fin) {
                            deliver(opcode, payload);
                        } else {
                            messageOp = opcode;
                            message = new ByteArrayOutputStream();
                            message.write(payload, 0, payload.length);
                        }
                        break;
                    case OP_CONT:
                        if (messageOp < 0) {
                            throw new Fail(CloseCodes.PROTOCOL_ERROR, "continuation without a message");
                        }
                        if (message.size() + payload.length > config.maxMessageBytes) {
                            throw new Fail(CloseCodes.MESSAGE_TOO_BIG, "message too large");
                        }
                        message.write(payload, 0, payload.length);
                        if (fin) {
                            byte[] whole = message.toByteArray();
                            message = null;
                            int op = messageOp;
                            messageOp = -1;
                            deliver(op, whole);
                        }
                        break;
                    case OP_PING:
                        synchronized (writeLock) {
                            if (!closeSent) {
                                writeFrame(OP_PONG, payload);
                            }
                        }
                        break;
                    case OP_PONG:
                        break;
                    case OP_CLOSE:
                        if (payload.length == 1) {
                            throw new Fail(CloseCodes.PROTOCOL_ERROR, "bad close payload");
                        }
                        closeCode = payload.length >= 2 ? (payload[0] & 0xff) << 8 | payload[1] & 0xff
                                : CloseCodes.NO_STATUS;
                        closeReason = payload.length > 2 ? decodeText(payload, 2) : "";
                        if (!closeSent) {
                            byte[] echo = payload.length >= 2 ? new byte[] {payload[0], payload[1]} : new byte[0];
                            synchronized (writeLock) {
                                if (!closeSent) {
                                    closeSent = true;
                                    writeFrame(OP_CLOSE, echo);
                                }
                            }
                        }
                        return;
                    default:
                        throw new Fail(CloseCodes.PROTOCOL_ERROR, "unknown opcode " + opcode);
                }
            }
        } catch (Fail f) {
            closeCode = f.code;
            closeReason = f.getMessage();
            close(f.code, f.getMessage());
        } catch (IOException e) {
            closeCode = CloseCodes.ABNORMAL;
        } catch (RuntimeException e) {
            closeCode = CloseCodes.INTERNAL_ERROR;
            close(CloseCodes.INTERNAL_ERROR, "");
            throw e;
        } finally {
            open = false;
            try {
                listener.onClose(this, closeCode, closeReason);
            } finally {
                conn.close();
            }
        }
    }

    private void deliver(int opcode, byte[] payload) throws Fail {
        if (opcode == OP_TEXT) {
            listener.onText(this, decodeText(payload, 0));
        } else {
            listener.onBinary(this, payload);
        }
    }

    private static String decodeText(byte[] b, int off) throws Fail {
        try {
            return UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, off, b.length - off))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new Fail(CloseCodes.INVALID_PAYLOAD, "invalid UTF-8");
        }
    }

    /** Mid-frame read: a timeout here ends the connection (the parser cannot resume). */
    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("connection closed inside a frame");
        }
        return b;
    }

    private byte[] readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                throw new EOFException("connection closed inside a frame");
            }
            off += n;
        }
        return b;
    }
}
