package com.snoworca.tinyhttpd.http;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * File extension to media type, as a table in code: classpath resources are slow to scan inside an APK and may be
 * stripped by R8. Thread safe.
 */
public final class MimeTypes {

    private static final Map<String, String> TYPES = new HashMap<String, String>(256);

    static {
        String[] table = {
            // text and web
            "txt", "text/plain", "md", "text/markdown", "asc", "text/plain", "log", "text/plain", "csv", "text/csv",
            "htm", "text/html", "html", "text/html", "css", "text/css", "xml", "text/xml", "js", "application/javascript",
            "mjs", "application/javascript", "json", "application/json", "map", "application/json",
            "wasm", "application/wasm", "java", "text/x-java-source", "ics", "text/calendar", "vtt", "text/vtt",
            "srt", "application/x-subrip", "rtf", "application/rtf", "yaml", "application/yaml", "yml", "application/yaml",
            // images
            "gif", "image/gif", "jpg", "image/jpeg", "jpeg", "image/jpeg", "jpe", "image/jpeg", "png", "image/png",
            "svg", "image/svg+xml", "webp", "image/webp", "bmp", "image/bmp", "ico", "image/x-icon",
            "tif", "image/tiff", "tiff", "image/tiff", "heic", "image/heic", "heif", "image/heif", "avif", "image/avif",
            "dng", "image/x-adobe-dng", "cr2", "image/x-canon-cr2", "nef", "image/x-nikon-nef", "arw", "image/x-sony-arw",
            "psd", "image/vnd.adobe.photoshop",
            // audio
            "mp3", "audio/mpeg", "m4a", "audio/mp4", "aac", "audio/aac", "flac", "audio/flac", "wav", "audio/wav",
            "ogg", "audio/ogg", "oga", "audio/ogg", "opus", "audio/opus", "wma", "audio/x-ms-wma", "mid", "audio/midi",
            "midi", "audio/midi", "aiff", "audio/aiff", "ape", "audio/x-ape", "m3u", "audio/mpegurl",
            // video
            "mp4", "video/mp4", "m4v", "video/x-m4v", "mkv", "video/x-matroska", "webm", "video/webm",
            "mov", "video/quicktime", "avi", "video/x-msvideo", "wmv", "video/x-ms-wmv", "flv", "video/x-flv",
            "ogv", "video/ogg", "3gp", "video/3gpp", "3g2", "video/3gpp2", "ts", "video/mp2t", "mts", "video/mp2t",
            "m2ts", "video/mp2t", "mpg", "video/mpeg", "mpeg", "video/mpeg", "m3u8", "application/vnd.apple.mpegurl",
            // documents
            "pdf", "application/pdf", "doc", "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls", "application/vnd.ms-excel",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt", "application/vnd.ms-powerpoint",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "odt", "application/vnd.oasis.opendocument.text", "ods", "application/vnd.oasis.opendocument.spreadsheet",
            "odp", "application/vnd.oasis.opendocument.presentation", "epub", "application/epub+zip",
            "hwp", "application/x-hwp", "hwpx", "application/hwp+zip",
            // archives and binaries
            "zip", "application/zip", "gz", "application/gzip", "tgz", "application/gzip", "tar", "application/x-tar",
            "bz2", "application/x-bzip2", "xz", "application/x-xz", "7z", "application/x-7z-compressed",
            "rar", "application/vnd.rar", "apk", "application/vnd.android.package-archive",
            "jar", "application/java-archive", "iso", "application/x-iso9660-image", "dmg", "application/x-apple-diskimage",
            "exe", "application/octet-stream", "bin", "application/octet-stream", "class", "application/octet-stream",
            // fonts
            "woff", "font/woff", "woff2", "font/woff2", "ttf", "font/ttf", "otf", "font/otf",
        };
        for (int i = 0; i < table.length; i += 2) {
            TYPES.put(table[i], table[i + 1]);
        }
    }

    private MimeTypes() {
    }

    /**
     * @param extensionOrFileName {@code mkv}, {@code .MKV} or {@code movie.mkv}
     * @return the media type, or null if unknown
     */
    public static String get(String extensionOrFileName) {
        if (extensionOrFileName == null) {
            return null;
        }
        int dot = extensionOrFileName.lastIndexOf('.');
        String ext = (dot < 0 ? extensionOrFileName : extensionOrFileName.substring(dot + 1)).toLowerCase(Locale.US);
        synchronized (TYPES) {
            return TYPES.get(ext);
        }
    }

    /**
     * @param fileName a file name
     * @param fallback returned for unknown extensions
     * @return the media type
     */
    public static String get(String fileName, String fallback) {
        String t = get(fileName);
        return t != null ? t : fallback;
    }

    /**
     * Adds or replaces a mapping.
     *
     * @param extension extension without dot
     * @param type media type
     */
    public static void register(String extension, String type) {
        synchronized (TYPES) {
            TYPES.put(extension.toLowerCase(Locale.US), type);
        }
    }
}
