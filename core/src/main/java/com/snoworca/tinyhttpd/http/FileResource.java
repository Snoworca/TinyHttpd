package com.snoworca.tinyhttpd.http;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

/**
 * Something {@link FileResponses} can serve: a file, or an Android SAF document whose length and modification time
 * the app reads from {@code DocumentFile} and whose channel it opens from a {@code ParcelFileDescriptor}.
 */
public interface FileResource {

    /** @return length in bytes */
    long length();

    /** @return modification time in milliseconds, or 0 if unknown */
    long lastModified();

    /** @return a name for logs and downloads */
    String getName();

    /**
     * @return a channel positioned anywhere; {@link FileResponses} closes it
     * @throws IOException if it cannot be opened
     */
    FileChannel openChannel() throws IOException;

    /**
     * @param file the file
     * @return a resource reading the file
     */
    static FileResource of(final File file) {
        return new FileResource() {

            @Override
            public long length() {
                return file.length();
            }

            @Override
            public long lastModified() {
                return file.lastModified();
            }

            @Override
            public String getName() {
                return file.getName();
            }

            @Override
            public FileChannel openChannel() throws IOException {
                return new RandomAccessFile(file, "r").getChannel();
            }
        };
    }
}
