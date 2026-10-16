package com.rhf.snatcher;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends Activity {
    private WebView wv;
    private OkHttpClient client;
    private File captureDir;
    private final List<String> capturedUrls = new ArrayList<>();
    private final List<String> capturedFiles = new ArrayList<>();
    private final ConcurrentHashMap<String, String> mimeTypes = new ConcurrentHashMap<>();
    private long totalBytes = 0;
    private int captureCount = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

        captureDir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), "WebSnatcher/captures");
        if (!captureDir.exists()) captureDir.mkdirs();

        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36");
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setBlockNetworkLoads(false);
        s.setLoadsImagesAutomatically(true);

        wv.setWebChromeClient(new WebChromeClient());
        wv.addJavascriptInterface(new JSBridge(), "Android");

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();

                // Skip data:/blob:/file:
                if (url.startsWith("data:") || url.startsWith("blob:") ||
                    url.startsWith("about:") || url.startsWith("javascript:") ||
                    url.startsWith("file:")) {
                    return null;
                }

                try {
                    Request.Builder rb = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                        .header("Accept", "*/*")
                        .header("Accept-Language", "en-US,en;q=0.9");

                    // Pass cookie dari WebView kalau ada
                    String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
                    if (cookie != null && !cookie.isEmpty()) {
                        rb.header("Cookie", cookie);
                    }

                    Response res = client.newCall(rb.build()).execute();
                    ResponseBody body = res.body();
                    if (body == null) return null;
                    byte[] bytes = body.bytes();

                    // Simpan SEMUA jenis file
                    saveCaptured(url, bytes);

                    String ctype = res.header("Content-Type", "application/octet-stream");
                    String cenc = res.header("Content-Encoding", null);
                    return new WebResourceResponse(ctype, cenc, new ByteArrayInputStream(bytes));
                } catch (Exception e) {
                    Log.e("Snatcher", "Intercept fail: " + url + " — " + e.getMessage());
                    return null;
                }
            }
        });

        wv.loadUrl("file:///android_asset/index.html");
        setContentView(wv);
    }

    private void saveCaptured(String url, byte[] data) {
        try {
            URL u = new URL(url);
            String host = u.getHost();
            String path = u.getPath();
            String query = u.getQuery();
            if (path.isEmpty() || path.endsWith("/")) path += "index.html";

            // Ambil ekstensi dari query kalau nggak ada di path
            if (!path.matches(".*\\.[a-zA-Z0-9]+$") && query != null) {
                for (String p : query.split("&")) {
                    if (p.startsWith("file=") || p.endsWith(".js") || p.endsWith(".json")) {
                        path += "_" + p.split("=")[0];
                        break;
                    }
                }
            }

            // Sanitasi
            path = path.replaceAll("[?].*$", "");
            path = path.replaceAll("[^a-zA-Z0-9._/-]", "_");
            if (path.length() > 200) path = path.substring(0, 200);

            File out = new File(captureDir, host + path);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            FileOutputStream fos = new FileOutputStream(out);
            fos.write(data);
            fos.close();

            String fname = host + path;
            mimeTypes.put(fname, detectMime(path));

            if (!capturedUrls.contains(url)) {
                capturedUrls.add(url);
                capturedFiles.add(fname + "|" + data.length + "|" + url);
                totalBytes += data.length;
                captureCount++;

                // Kirim ke WebView
                final String js = "window.onCapture && window.onCapture(" +
                    captureCount + "," + totalBytes + ",'" +
                    fname.replace("'", "\\'").replace("\\", "\\\\") + "');";
                runOnUiThread(() -> wv.evaluateJavascript(js, null));
            }
        } catch (Exception e) {
            Log.e("Snatcher", "Save fail: " + e.getMessage());
        }
    }

    private String detectMime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".json")) return "json";
        if (p.endsWith(".js")) return "js";
        if (p.endsWith(".css")) return "css";
        if (p.endsWith(".html")) return "html";
        if (p.endsWith(".png")) return "png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "jpg";
        if (p.endsWith(".webp")) return "webp";
        if (p.endsWith(".gif")) return "gif";
        if (p.endsWith(".svg")) return "svg";
        if (p.endsWith(".mp3")) return "mp3";
        if (p.endsWith(".ogg")) return "ogg";
        if (p.endsWith(".wav")) return "wav";
        if (p.endsWith(".mp4")) return "mp4";
        if (p.endsWith(".wasm")) return "wasm";
        if (p.endsWith(".ttf")) return "ttf";
        if (p.endsWith(".woff") || p.endsWith(".woff2")) return "woff";
        return "file";
    }

    private class JSBridge {
        @JavascriptInterface
        public void openWeb(String url) {
            runOnUiThread(() -> wv.loadUrl(url));
        }

        @JavascriptInterface
        public String getStats() {
            return "{\"count\":" + captureCount + ",\"bytes\":" + totalBytes + "}";
        }

        @JavascriptInterface
        public String getFileList() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < capturedFiles.size(); i++) {
                if (i > 0) sb.append(",");
                String[] parts = capturedFiles.get(i).split("\\|", 3);
                String name = parts[0];
                sb.append("{\"name\":\"").append(name.replace("\"","\\\""))
                  .append("\",\"size\":").append(parts[1])
                  .append(",\"type\":\"").append(mimeTypes.getOrDefault(name, "file"))
                  .append("\",\"url\":\"").append(parts[2].replace("\"","\\\""))
                  .append("\"}");
            }
            sb.append("]");
            return sb.toString();
        }

        @JavascriptInterface
        public String saveZip() {
            try {
                File outDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "WebSnatcher");
                if (!outDir.exists()) outDir.mkdirs();
                File zipFile = new File(outDir, "snatch-" + System.currentTimeMillis() + ".zip");

                ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
                File[] files = captureDir.listFiles();
                if (files != null) {
                    for (File f : files) addToZip(zos, f, "");
                }
                zos.close();
                return zipFile.getAbsolutePath();
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void clearCapture() {
            File[] files = captureDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) deleteRecursive(f);
                    else f.delete();
                }
            }
            capturedUrls.clear();
            capturedFiles.clear();
            mimeTypes.clear();
            totalBytes = 0;
            captureCount = 0;
        }

        private void deleteRecursive(File f) {
            File[] kids = f.listFiles();
            if (kids != null) for (File c : kids) {
                if (c.isDirectory()) deleteRecursive(c);
                else c.delete();
            }
            f.delete();
        }

        private void addToZip(ZipOutputStream zos, File file, String base) throws Exception {
            String entryName = base + file.getName();
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                zos.putNextEntry(new ZipEntry(entryName + "/"));
                zos.closeEntry();
                if (children != null) for (File c : children) addToZip(zos, c, entryName + "/");
            } else {
                zos.putNextEntry(new ZipEntry(entryName));
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                fis.close();
                zos.closeEntry();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }
}
