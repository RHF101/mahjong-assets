package com.rhf.snatcher;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Extractor {
    // URL absolute
    private static final Pattern P_URL = Pattern.compile(
        "https?://[^\\s\"'<>`\\)\\]}\\u002C]+", Pattern.CASE_INSENSITIVE);
    // src=, href=, data-src=, etc
    private static final Pattern P_ATTR = Pattern.compile(
        "(?:src|href|data-src|data-href|poster|action|data-url)\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE);
    // CSS url()
    private static final Pattern P_CSS_URL = Pattern.compile(
        "url\\(\\s*[\"']?([^\"')]+)[\"']?\\s*\\)", Pattern.CASE_INSENSITIVE);
    // @import
    private static final Pattern P_CSS_IMPORT = Pattern.compile(
        "@import\\s+(?:url\\(\\s*)?[\"']([^\"')]+)[\"']", Pattern.CASE_INSENSITIVE);
    // import(...) / require(...)
    private static final Pattern P_IMPORT = Pattern.compile(
        "(?:import|require|fetch)\\s*\\(\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    // Dynamic import() — sama seperti import() tapi buat ESM
    private static final Pattern P_ESM = Pattern.compile(
        "import\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)", Pattern.CASE_INSENSITIVE);
    // Sourcemap
    private static final Pattern P_MAP = Pattern.compile(
        "(?:sourceMappingURL|\\/\\/# sourceMappingURL)\\s*=?\\s*([^\\s]+)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern P_JSON_URL = Pattern.compile(
        "[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    public static Set<String> extract(String baseUrl, String content, String type) {
        Set<String> found = new HashSet<>();
        if (content == null || content.isEmpty()) return found;

        // Semua URL absolute
        Matcher m = P_URL.matcher(content);
        while (m.find()) found.add(clean(m.group()));

        // Atribut HTML
        m = P_ATTR.matcher(content);
        while (m.find()) {
            String raw = m.group(1);
            String abs = resolve(baseUrl, raw);
            if (abs != null) found.add(abs);
        }

        // CSS url()
        if ("css".equals(type) || content.contains("url(")) {
            m = P_CSS_URL.matcher(content);
            while (m.find()) {
                String raw = m.group(1);
                if (raw.startsWith("data:")) continue;
                String abs = resolve(baseUrl, raw);
                if (abs != null) found.add(abs);
            }
            m = P_CSS_IMPORT.matcher(content);
            while (m.find()) {
                String raw = m.group(1);
                String abs = resolve(baseUrl, raw);
                if (abs != null) found.add(abs);
            }
        }

        // JS import() / require() / fetch()
        if ("js".equals(type) || content.contains("import") || content.contains("require")) {
            m = P_IMPORT.matcher(content);
            while (m.find()) {
                String raw = m.group(1);
                if (raw.startsWith("data:") || raw.startsWith("#")) continue;
                String abs = resolve(baseUrl, raw);
                if (abs != null) found.add(abs);
            }
            m = P_ESM.matcher(content);
            while (m.find()) {
                String raw = m.group(1);
                String abs = resolve(baseUrl, raw);
                if (abs != null) found.add(abs);
            }
            m = P_MAP.matcher(content);
            while (m.find()) {
                String raw = m.group(1);
                String abs = resolve(baseUrl, raw);
                if (abs != null) found.add(abs);
            }
        }

        // JSON string URLs
        if ("json".equals(type)) {
            m = P_JSON_URL.matcher(content);
            while (m.find()) found.add(clean(m.group(1)));
        }

        return found;
    }

    private static String resolve(String base, String raw) {
        if (raw == null || raw.isEmpty()) return null;
        raw = raw.trim();
        if (raw.startsWith("data:") || raw.startsWith("blob:") ||
            raw.startsWith("about:") || raw.startsWith("javascript:") ||
            raw.startsWith("#")) return null;
        try {
            URI baseUri = URI.create(base);
            URI resolved = baseUri.resolve(raw);
            String s = resolved.toString();
            if (!s.startsWith("http://") && !s.startsWith("https://")) return null;
            return clean(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String u) {
        if (u == null) return null;
        u = u.trim();
        // Buang trailing punctuation yang mungkin keikut regex
        while (!u.isEmpty() && ".,;:!?)\\]}'\"".indexOf(u.charAt(u.length() - 1)) >= 0) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
