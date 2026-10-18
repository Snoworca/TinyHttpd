package com.snoworca.tinyhttpd.http;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/** Sends events to many streams. Closed streams are dropped on the next broadcast. Thread safe. */
public final class EventBroadcaster {

    private final CopyOnWriteArrayList<EventStream> streams = new CopyOnWriteArrayList<EventStream>();

    /** @param stream a stream to receive broadcasts */
    public void subscribe(EventStream stream) {
        streams.addIfAbsent(stream);
    }

    /** @param stream a stream to stop receiving */
    public void unsubscribe(EventStream stream) {
        streams.remove(stream);
    }

    /**
     * Enqueues the event on every open stream; it never blocks on a slow client.
     *
     * @param event event type, or null
     * @param data data
     * @param id id, or null
     */
    public void broadcast(String event, String data, String id) {
        for (EventStream s : streams) {
            if (!s.isOpen()) {
                streams.remove(s);
                continue;
            }
            try {
                s.send(event, data, id);
            } catch (IOException e) {
                streams.remove(s);
            }
        }
    }

    /** @return subscribed streams, including ones not yet noticed as closed */
    public int size() {
        return streams.size();
    }
}
