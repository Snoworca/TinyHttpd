package com.snoworca.tinyhttpd.junit.engine;

import static org.junit.Assert.*;
import com.snoworca.tinyhttpd.http.*;
import com.snoworca.tinyhttpd.testing.RawConnection;
import java.io.*;
import java.net.SocketException;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import org.junit.Test;

/** FR-HTTP-003: cancellation is transport closure, never a fabricated HTTP response. */
public class ResponseAbortTest extends EngineTestBase {
    private static boolean inSocketWrite(Thread thread) {
        boolean nativeCall = false;
        boolean socketWrite = false;
        for (StackTraceElement frame : thread.getStackTrace()) {
            nativeCall |= frame.isNativeMethod();
            String name = frame.getClassName();
            socketWrite |= (name.startsWith("sun.nio.ch.") || name.startsWith("java.net."))
                    && frame.getMethodName().toLowerCase(Locale.ROOT).contains("write");
        }
        return nativeCall && socketWrite;
    }

    private static String readToClose(RawConnection connection) throws IOException {
        connection.setReadTimeout(3000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            int value;
            while ((value = connection.input().read()) != -1) {
                bytes.write(value);
            }
        } catch (SocketException | SSLException closed) {
            // An abort may send EOF or a transport reset, including a TLS truncation error.
        }
        return bytes.toString("ISO-8859-1");
    }

    // FR-HTTP-003 AC-1, AC-4, AC-6
    @Test
    public void fr_HTTP_003_AC1_abortBeforeCommitSendsNothingForGetAndHead() throws Exception {
        AtomicInteger checked = new AtomicInteger();
        routes.put("/abort", (req, res) -> {
            OutputStream body = res.getOutputStream();
            body.write("uncommitted".getBytes("US-ASCII"));
            res.abort();
            try { body.write('x'); fail("write after abort"); } catch (IOException expected) { checked.incrementAndGet(); }
            try { res.flushBuffer(); fail("flush after abort"); } catch (IOException expected) { checked.incrementAndGet(); }
            throw new IOException("a handler failure after abort must not create an error page");
        });
        routes.put("/ok", (req, res) -> text(res, "healthy"));
        start();
        for (String method : new String[] {"GET", "HEAD"}) {
            try (RawConnection connection = connect()) {
                connection.send(method + " /abort HTTP/1.1\r\nHost: x\r\n\r\n");
                assertEquals("abort must not emit headers or buffered bytes", "", readToClose(connection));
            }
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (checked.get() != 4 && System.nanoTime() < until) Thread.yield();
        assertEquals(4, checked.get());
        try (RawConnection connection = connect()) {
            connection.send("GET /ok HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("healthy", connection.read().body);
        }
    }

    // FR-HTTP-003 AC-2, AC-6
    @Test
    public void fr_HTTP_003_AC2_committedChunkIsNotCompletedOrReused() throws Exception {
        AtomicInteger second = new AtomicInteger();
        routes.put("/stream", (req, res) -> {
            res.getOutputStream().write("prefix".getBytes("US-ASCII"));
            res.flushBuffer();
            res.abort();
            throw new IllegalStateException("no fallback error response after abort");
        });
        routes.put("/second", (req, res) -> { second.incrementAndGet(); text(res, "second"); });
        start();
        try (RawConnection connection = connect()) {
            connection.send("GET /stream HTTP/1.1\r\nHost: x\r\n\r\nGET /second HTTP/1.1\r\nHost: x\r\n\r\n");
            String raw = readToClose(connection);
            assertTrue(raw, raw.startsWith("HTTP/1.1 200"));
            assertTrue(raw, raw.contains("6\r\nprefix\r\n"));
            assertFalse(raw, raw.endsWith("0\r\n\r\n"));
            assertFalse(raw, raw.contains("500 Internal"));
            assertEquals(0, second.get());
        }
    }

    // FR-HTTP-003 AC-3, AC-6
    @Test
    public void fr_HTTP_003_AC3_abortWakesBlockedWriterWithoutWaitingForWriteTimeout() throws Exception {
        AtomicReference<Response> response = new AtomicReference<>();
        AtomicReference<Thread> writer = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong written = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        routes.put("/blocked", (req, res) -> {
            response.set(res);
            writer.set(Thread.currentThread());
            entered.countDown();
            byte[] block = new byte[16 * 1024];
            try {
                for (;;) { res.getOutputStream().write(block); written.addAndGet(block.length); }
            } catch (Throwable problem) {
                failure.set(problem);
            } finally { ended.countDown(); }
        });
        start(); // default write timeout is 30 seconds
        try (RawConnection connection = connect(1024)) {
            connection.send("GET /blocked HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            long since = -1;
            long previous = -1;
            boolean stalled = false;
            while (System.nanoTime() < deadline) {
                long progress = written.get();
                assertEquals("writer ended before abort", 1, ended.getCount());
                if (progress > 0 && progress == previous && inSocketWrite(writer.get())) {
                    if (since < 0) since = System.nanoTime();
                    if (System.nanoTime() - since >= TimeUnit.MILLISECONDS.toNanos(300)) {
                        stalled = true;
                        break;
                    }
                } else { since = -1; }
                previous = progress;
                Thread.sleep(5);
            }
            assertTrue("client must stall the socket writer", stalled);
            Thread aborter = new Thread(() -> response.get().abort(), "response-aborter");
            aborter.setDaemon(true);
            aborter.start();
            aborter.join(1000);
            assertFalse("abort must not wait behind the blocked write", aborter.isAlive());
            assertTrue("abort must wake the writer before its 30s timeout", ended.await(1500, TimeUnit.MILLISECONDS));
            assertTrue(String.valueOf(failure.get()), failure.get() instanceof IOException);
        }
    }

    // FR-HTTP-003 AC-3, AC-6
    @Test
    public void fr_HTTP_003_AC3_repeatedConcurrentAbortIsSafe() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        routes.put("/abort", (req, res) -> {
            Thread[] threads = new Thread[8];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    try { for (int n = 0; n < 20; n++) res.abort(); }
                    catch (Throwable e) { failure.compareAndSet(null, e); }
                });
                threads[i].start();
            }
            for (Thread thread : threads) {
                try { thread.join(1000); if (thread.isAlive()) throw new AssertionError("abort blocked"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            ended.countDown();
        });
        start();
        try (RawConnection connection = connect()) {
            connection.send("HEAD /abort HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("", readToClose(connection));
            assertTrue(ended.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }

    // FR-HTTP-003 AC-3, AC-6: cancellation also ends an SSE owner waiting on its queue.
    @Test
    public void fr_HTTP_003_AC6_abortClosesSseAndReleasesItsOwnerAndSlot() throws Exception {
        AtomicReference<Response> response = new AtomicReference<>();
        AtomicReference<EventStream> stream = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        routes.put("/events", (req, res) -> {
            response.set(res);
            EventStream es = res.startEventStream();
            stream.set(es);
            ready.countDown();
            try { es.awaitClosed(60000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { returned.countDown(); }
        });
        routes.put("/new", (req, res) -> {
            try (EventStream es = res.startEventStream()) { es.send("ready", "new", null); }
        });
        start(com.snoworca.tinyhttpd.testing.TestServers.builder().maxEventStreams(1));
        try (RawConnection connection = connect()) {
            connection.send("GET /events HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            response.get().abort();
            assertFalse("abort must mark the stream closed", stream.get().isOpen());
            try { stream.get().send("late", "must fail", null); fail("send after abort"); }
            catch (IOException expected) { }
            assertTrue("abort must wake the SSE owner without a heartbeat", returned.await(1500, TimeUnit.MILLISECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (server.getStats().eventStreams != 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(0, server.getStats().eventStreams);
        }
        try (RawConnection connection = connect()) {
            connection.send("GET /new HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(200, connection.read().status());
        }
    }

    // FR-HTTP-003 AC-4: a Writer must not silently retain characters after cancellation.
    @Test
    public void fr_HTTP_003_AC4_writerRejectsEveryWriteAndFlushAfterAbort() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch checked = new CountDownLatch(1);
        routes.put("/writer", (req, res) -> {
            try {
                Writer writer = res.getWriter();
                writer.write("buffered before cancellation");
                res.abort();
                try { writer.write('x'); fail("write(int)"); } catch (IOException expected) { }
                try { writer.write(new char[] {'x'}, 0, 1); fail("write(char[])"); } catch (IOException expected) { }
                try { writer.write("text", 0, 2); fail("write(String)"); } catch (IOException expected) { }
                try { writer.append("text"); fail("append"); } catch (IOException expected) { }
                try { writer.flush(); fail("flush"); } catch (IOException expected) { }
            } catch (Throwable problem) { failure.set(problem); }
            finally { checked.countDown(); }
        });
        start();
        try (RawConnection connection = connect()) {
            connection.send("GET /writer HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("", readToClose(connection));
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertNull(String.valueOf(failure.get()), failure.get());
        }
    }

    // FR-HTTP-003 AC-7: an old response must not cancel the next request on a persistent connection.
    @Test
    public void fr_HTTP_003_AC7_oldResponseCannotAbortReusedConnection() throws Exception {
        AtomicReference<Response> previous = new AtomicReference<>();
        routes.put("/first", (req, res) -> { previous.set(res); text(res, "first"); });
        routes.put("/second", (req, res) -> { previous.get().abort(); text(res, "second"); });
        start();
        try (RawConnection connection = connect()) {
            connection.send("GET /first HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("first", connection.read().body);
            connection.send("GET /second HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals("second", connection.read().body);
        }
    }

    // FR-HTTP-003 AC-5
    @Test
    public void fr_HTTP_003_AC5_defaultIsUnsupportedAndWrapperDelegates() throws Exception {
        assertTrue(Response.class.getMethod("abort").isDefault());
        try { new LegacyResponse().abort(); fail("legacy implementation must report unsupported"); }
        catch (UnsupportedOperationException expected) { }
        AtomicInteger count = new AtomicInteger();
        Response response = new LegacyResponse() { @Override public void abort() { count.incrementAndGet(); } };
        ResponseWrapper wrapper = new ResponseWrapper(response);
        wrapper.abort(); wrapper.abort();
        assertEquals(2, count.get());
    }

    /** Implements the pre-abort interface without adding an abort method. */
    private static class LegacyResponse implements Response {
        public void setStatus(int status) { }
        public int getStatus() { return 200; }
        public void setHeader(String name, String value) { }
        public void addHeader(String name, String value) { }
        public void setDateHeader(String name, long millis) { }
        public String getHeader(String name) { return null; }
        public boolean containsHeader(String name) { return false; }
        public void setContentType(String type) { }
        public String getContentType() { return null; }
        public void setContentLengthLong(long length) { }
        public void addCookie(Cookie cookie) { }
        public boolean isCommitted() { return false; }
        public void flushBuffer() { }
        public void resetBuffer() { }
        public void reset() { }
        public void sendError(int status, String message) { }
        public void sendRedirect(String location) { }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public Writer getWriter() { return new StringWriter(); }
        public void setCloseConnection(boolean close) { }
        public void sendFile(File file, long offset, long length) { }
        public void sendFile(FileChannel channel, long offset, long length) { }
        public EventStream startEventStream() { throw new UnsupportedOperationException(); }
        public UpgradedConnection upgrade(String protocol) { throw new UnsupportedOperationException(); }
    }
}
