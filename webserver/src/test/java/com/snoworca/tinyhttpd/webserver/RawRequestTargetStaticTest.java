package com.snoworca.tinyhttpd.webserver;

import static org.junit.Assert.*;

import com.snoworca.tinyhttpd.RequestTargetPolicy;
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.testing.RawConnection;
import com.snoworca.tinyhttpd.testing.TestServers;
import com.snoworca.tinyhttpd.testing.TestTls;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

/** FR-HTTP-004 AC-5: RAW never turns decoded file paths into unchecked filesystem paths. */
public class RawRequestTargetStaticTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Rule public Timeout timeout = Timeout.seconds(60);

    // FR-HTTP-004 AC-5, AC-7
    @Test
    public void fr_HTTP_004_AC5_rawPolicyCannotExposeOutsideSentinel() throws Exception {
        File root = temporary.newFolder("www");
        File outside = temporary.newFile("secret.txt");
        String sentinel = "OUTSIDE-" + java.util.UUID.randomUUID();
        Files.write(outside.toPath(), sentinel.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "hello.txt").toPath(), "safe".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(root, "escape.txt").toPath(), outside.toPath());
        String[] unsafe = {
            "/../secret.txt", "/%2e%2e/secret.txt", "/%2E%2E/secret.txt", "/.%2e/secret.txt",
            "/x/../../secret.txt", "/..%2fsecret.txt", "/..%2Fsecret.txt", "/%00secret.txt",
            "/%ZZ", "/%", "/%252e%252e/secret.txt", "/..%5csecret.txt", "/..\\secret.txt",
            "/escape.txt", "/esc%61pe.txt"
        };
        for (boolean tls : new boolean[] {false, true}) {
            TinyHttpd.Builder builder = TestServers.builder().port(0).hostCheck(false)
                    .requestTargetPolicy(RequestTargetPolicy.RAW).handler(new StaticFileHandler(root));
            if (tls) builder.tls(TestTls.server().build());
            TinyHttpd server = builder.build();
            try {
                server.start();
                for (String path : unsafe) {
                    RawConnection.RawResponse response = get(server, tls, path);
                    assertTrue(path + " " + response.statusLine,
                            response.status() == 400 || response.status() == 403 || response.status() == 404);
                    assertFalse(path, response.body.contains(sentinel));
                }
                assertEquals("safe", get(server, tls, "/hello.txt").body);
                assertEquals("safe", get(server, tls, "/hell%6f.txt").body);
            } finally { server.stop(200); }
        }
    }

    // FR-HTTP-004 AC-5: allowing outside symlinks does not allow lexical dot/backslash traversal.
    @Test
    public void fr_HTTP_004_AC5_explicitSymlinkOptionDoesNotDisablePathValidation() throws Exception {
        File root = temporary.newFolder("www");
        TinyHttpd server = TestServers.builder().port(0).hostCheck(false)
                .requestTargetPolicy(RequestTargetPolicy.RAW)
                .handler(new StaticFileHandler(root).followSymlinksOutsideRoot(true)).build();
        try {
            server.start();
            for (String path : new String[] {"/../secret.txt", "/%2e%2e/secret.txt", "/..%5csecret.txt", "/..\\secret.txt"}) {
                assertEquals(path, 400, get(server, false, path).status());
            }
        } finally { server.stop(200); }
    }

    private static RawConnection.RawResponse get(TinyHttpd server, boolean tls, String path) throws Exception {
        try (RawConnection c = new RawConnection(server.getPort(), 0, tls ? TestTls.socketFactory() : null)) {
            c.send("GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            return c.read();
        }
    }
}
