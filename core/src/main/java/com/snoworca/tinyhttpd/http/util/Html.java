package com.snoworca.tinyhttpd.http.util;

/** HTML escaping for text placed in elements or quoted attributes. */
public final class Html {

    private Html() {
    }

    /**
     * Escapes {@code & < > " '}.
     *
     * @param s text, may be null
     * @return escaped text; empty for null
     */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep;
            switch (c) {
                case '&': rep = "&amp;"; break;
                case '<': rep = "&lt;"; break;
                case '>': rep = "&gt;"; break;
                case '"': rep = "&quot;"; break;
                case '\'': rep = "&#39;"; break;
                default: rep = null;
            }
            if (rep != null) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 16).append(s, 0, i);
                }
                sb.append(rep);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }
}
