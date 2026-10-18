package com.snoworca.tinyhttpd.testing;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;

/** OkHttp client helpers. */
public final class Clients {

    private Clients() {
    }

    /** Counts connections acquired by calls, to check connection reuse. */
    public static final class ConnectionCounter extends EventListener {

        public final AtomicInteger acquired = new AtomicInteger();

        public final AtomicInteger connected = new AtomicInteger();

        @Override
        public void connectionAcquired(Call call, Connection connection) {
            acquired.incrementAndGet();
        }

        @Override
        public void connectEnd(Call call, java.net.InetSocketAddress address, java.net.Proxy proxy,
                okhttp3.Protocol protocol) {
            connected.incrementAndGet();
        }
    }

    public static OkHttpClient okHttp(boolean retryOnConnectionFailure, EventListener listener) {
        OkHttpClient.Builder b = new OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(retryOnConnectionFailure);
        if (listener != null) {
            b.eventListener(listener);
        }
        return b.build();
    }

    public static OkHttpClient okHttp() {
        return okHttp(true, null);
    }

    public static void shutdown(OkHttpClient client) {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    public static String get(OkHttpClient client, String url) throws IOException {
        okhttp3.Response r = client.newCall(new okhttp3.Request.Builder().url(url).build()).execute();
        try {
            return r.body().string();
        } finally {
            r.close();
        }
    }
}
