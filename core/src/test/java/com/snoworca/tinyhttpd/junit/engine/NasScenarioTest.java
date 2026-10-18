package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.Cookie;
import com.snoworca.tinyhttpd.http.EventBroadcaster;
import com.snoworca.tinyhttpd.http.EventStream;
import com.snoworca.tinyhttpd.http.FileResponses;
import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.FilterChain;
import com.snoworca.tinyhttpd.http.Handler;
import com.snoworca.tinyhttpd.http.HttpException;
import com.snoworca.tinyhttpd.http.MultipartLimits;
import com.snoworca.tinyhttpd.http.MultipartReader;
import com.snoworca.tinyhttpd.http.Part;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import com.snoworca.tinyhttpd.http.util.Html;
import com.snoworca.tinyhttpd.testing.Clients;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okio.BufferedSource;

/**
 * P4-10: a small NAS written against the public API — login with a cookie, JSON directory listing, multipart upload
 * straight to disk, ranged download and change notifications over SSE.
 */
public class NasScenarioTest extends EngineTestBase {

    private File root;

    private final ConcurrentHashMap<String, String> sessions = new ConcurrentHashMap<String, String>();

    private final EventBroadcaster changes = new EventBroadcaster();

    @Before
    public void setUpNas() throws IOException {
        root = File.createTempFile("nas", "");
        root.delete();
        root.mkdirs();
        routes.put("/login", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                if (!"secret".equals(req.readForm(1024).get("password").get(0))) {
                    throw new HttpException(401, "wrong password");
                }
                String sid = UUID.randomUUID().toString();
                sessions.put(sid, "admin");
                res.addCookie(new Cookie("sid", sid).setHttpOnly(true).setPath("/").setSameSite("Strict"));
                res.setStatus(204);
            }
        });
        routes.put("/api/files", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException {
                StringBuilder json = new StringBuilder("[");
                String[] names = root.list();
                java.util.Arrays.sort(names);
                for (String n : names) {
                    json.append(json.length() > 1 ? "," : "").append("{\"name\":\"").append(Html.escape(n))
                            .append("\",\"size\":").append(new File(root, n).length()).append('}');
                }
                res.setContentType("application/json");
                res.getWriter().write(json.append(']').toString());
            }
        });
        routes.put("/api/upload", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                MultipartReader mr = req.readMultipart(MultipartLimits.of(1L << 30, 10));
                for (Part p; (p = mr.next()) != null;) {
                    String name = p.getSubmittedFileName();
                    if (name == null) {
                        continue;
                    }
                    File tmp = new File(root, name + ".part");
                    OutputStream out = new FileOutputStream(tmp);
                    try {
                        InputStream in = p.getInputStream();
                        byte[] b = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(b)) != -1) {
                            out.write(b, 0, n);
                        }
                    } finally {
                        out.close();
                    }
                    tmp.renameTo(new File(root, name));
                    changes.broadcast("added", name, null);
                }
                res.setStatus(204);
            }
        });
        routes.put("/download", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                String name = req.getQueryParameter("name");
                File f = name == null ? null : new File(root, new File(name).getName());
                if (f == null || !f.isFile()) {
                    throw new HttpException(404, "no such file");
                }
                FileResponses.attachment(res, f.getName());
                FileResponses.serve(req, res, f, null);
            }
        });
        routes.put("/events", new Handler() {

            @Override
            public void handle(Request req, Response res) throws IOException, HttpException {
                EventStream es = res.startEventStream();
                changes.subscribe(es);
                try {
                    es.awaitClosed(15000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @After
    public void deleteRoot() {
        for (File f : root.listFiles()) {
            f.delete();
        }
        root.delete();
    }

    private final Filter auth = new Filter() {

        @Override
        public void doFilter(Request req, Response res, FilterChain chain) throws IOException, HttpException {
            if (!req.getPath().equals("/login")) {
                String user = null;
                for (Cookie c : req.getCookies()) {
                    if (c.getName().equals("sid")) {
                        user = sessions.get(c.getValue());
                    }
                }
                if (user == null) {
                    throw new HttpException(401, "login required");
                }
            }
            chain.doFilter(req, res);
        }
    };

    @Test
    public void nasRoundTrip() throws Exception {
        hostCheck = true;
        start(TinyHttpd.builder().filter(auth));
        final String base = "http://127.0.0.1:" + port;
        final java.util.List<String> cookies = new java.util.concurrent.CopyOnWriteArrayList<String>();
        OkHttpClient client = Clients.okHttp().newBuilder().cookieJar(new okhttp3.CookieJar() {

            @Override
            public void saveFromResponse(okhttp3.HttpUrl url, java.util.List<okhttp3.Cookie> list) {
                for (okhttp3.Cookie c : list) {
                    cookies.add(c.name() + "=" + c.value());
                }
            }

            @Override
            public java.util.List<okhttp3.Cookie> loadForRequest(okhttp3.HttpUrl url) {
                java.util.List<okhttp3.Cookie> out = new java.util.ArrayList<okhttp3.Cookie>();
                for (String c : cookies) {
                    out.add(okhttp3.Cookie.parse(url, c));
                }
                return out;
            }
        }).build();
        try {
            assertEquals(401, client.newCall(new okhttp3.Request.Builder().url(base + "/api/files").build()).execute()
                    .code());
            okhttp3.Response login = client.newCall(new okhttp3.Request.Builder().url(base + "/login")
                    .post(new okhttp3.FormBody.Builder().add("password", "secret").build()).build()).execute();
            assertEquals(204, login.code());
            login.close();

            okhttp3.Response events = client.newCall(new okhttp3.Request.Builder().url(base + "/events").build())
                    .execute();
            BufferedSource stream = events.body().source();
            long deadline = System.currentTimeMillis() + 2000;
            while (changes.size() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            byte[] data = new byte[300 * 1024];
            new java.util.Random(7).nextBytes(data);
            okhttp3.Response up = client.newCall(new okhttp3.Request.Builder().url(base + "/api/upload")
                    .post(new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("note", "hi")
                            .addFormDataPart("file", "사진.jpg", RequestBody.create(data, MediaType.get("image/jpeg")))
                            .build()).build()).execute();
            assertEquals(204, up.code());
            up.close();
            assertEquals("event: added", stream.readUtf8Line());
            assertEquals("data: 사진.jpg", stream.readUtf8Line());
            events.close();

            String list = Clients.get(client, base + "/api/files");
            assertEquals("[{\"name\":\"사진.jpg\",\"size\":" + data.length + "}]", list);

            okhttp3.Response part = client.newCall(new okhttp3.Request.Builder()
                    .url(base + "/download?name=" + java.net.URLEncoder.encode("사진.jpg", "UTF-8"))
                    .header("Range", "bytes=100-199").build()).execute();
            assertEquals(206, part.code());
            assertTrue(part.header("Content-Disposition").contains("filename*=UTF-8''"));
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 100, 200), part.body().bytes());
            part.close();
        } finally {
            Clients.shutdown(client);
        }
    }
}
