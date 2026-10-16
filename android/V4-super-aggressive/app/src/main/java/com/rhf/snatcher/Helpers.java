package com.rhf.snatcher;

import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public class Helpers {
    private static final Set<String> MEDIA_IMG = new HashSet<>();
    private static final Set<String> MEDIA_AUDIO = new HashSet<>();
    private static final Set<String> MEDIA_VIDEO = new HashSet<>();
    private static final Set<String> MEDIA_FONT = new HashSet<>();
    private static final Set<String> CODE_JS = new HashSet<>();
    private static final Set<String> CODE_CSS = new HashSet<>();
    private static final Set<String> DATA_JSON = new HashSet<>();
    private static final Set<String> DATA_XML = new HashSet<>();
    private static final Set<String> ARCHIVE = new HashSet<>();

    static {
        for (String s : new String[]{"png","jpg","jpeg","gif","webp","svg","ico","bmp","tif","tiff","avif","heic"}) MEDIA_IMG.add(s);
        for (String s : new String[]{"mp3","ogg","wav","m4a","aac","opus","flac","weba"}) MEDIA_AUDIO.add(s);
        for (String s : new String[]{"mp4","webm","mkv","mov","avi","m3u8","ts"}) MEDIA_VIDEO.add(s);
        for (String s : new String[]{"ttf","otf","woff","woff2","eot"}) MEDIA_FONT.add(s);
        for (String s : new String[]{"js","mjs","cjs","jsx","ts","tsx","luau"}) CODE_JS.add(s);
        for (String s : new String[]{"css","scss","sass","less"}) CODE_CSS.add(s);
        for (String s : new String[]{"json","json5","jsonc"}) DATA_JSON.add(s);
        for (String s : new String[]{"xml","plist","atlas","tmx","svg"}) DATA_XML.add(s);
        for (String s : new String[]{"zip","tar","gz","rar","7z","wasm"}) ARCHIVE.add(s);
    }

    public static String ext(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('.');
        if (i < 0) return "";
        int q = path.indexOf('?', i);
        return (q < 0 ? path.substring(i + 1) : path.substring(i + 1, q)).toLowerCase();
    }

    public static String classify(String path) {
        String e = ext(path);
        if (MEDIA_IMG.contains(e)) return "img";
        if (MEDIA_AUDIO.contains(e)) return "audio";
        if (MEDIA_VIDEO.contains(e)) return "video";
        if (MEDIA_FONT.contains(e)) return "font";
        if (CODE_JS.contains(e)) return "js";
        if (CODE_CSS.contains(e)) return "css";
        if (DATA_JSON.contains(e)) return "json";
        if (DATA_XML.contains(e)) return "xml";
        if (ARCHIVE.contains(e)) return "archive";
        if (e.equals("html") || e.equals("htm") || e.isEmpty()) return "html";
        if (e.equals("txt") || e.equals("md") || e.equals("csv")) return "text";
        return "other";
    }

    public static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }

    public static String safeHost(String host) {
        return host.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }

    public static String safePath(String path) {
        if (path == null || path.isEmpty()) return "index.html";
        path = path.replaceAll("[?].*$", "");
        path = path.replaceAll("[^a-zA-Z0-9._/-]", "_");
        if (path.length() > 150) {
            int cut = path.lastIndexOf('/');
            if (cut > 0) path = path.substring(cut + 1);
        }
        if (path.startsWith("/")) path = path.substring(1);
        return path;
    }
}
