package com.snoworca.tinyhttpd.session;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** Where sessions live. The default keeps them in memory; implementations must be thread safe. */
public interface SessionStore {

    /**
     * @param id session id
     * @return the session, or null
     */
    Session find(String id);

    /** @param session stored under its current id */
    void save(Session session);

    /** @param id removed id */
    void remove(String id);

    /** @return all sessions (a snapshot or a weakly consistent view) */
    Collection<Session> all();

    /** @return an in-memory store */
    static SessionStore memory() {
        return new SessionStore() {

            private final ConcurrentHashMap<String, Session> map = new ConcurrentHashMap<String, Session>();

            @Override
            public Session find(String id) {
                return map.get(id);
            }

            @Override
            public void save(Session session) {
                map.put(session.id, session);
            }

            @Override
            public void remove(String id) {
                map.remove(id);
            }

            @Override
            public Collection<Session> all() {
                return map.values();
            }
        };
    }
}
