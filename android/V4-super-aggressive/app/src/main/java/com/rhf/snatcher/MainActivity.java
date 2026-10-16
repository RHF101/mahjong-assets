package com.rhf.snatcher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends Activity {
    private WebView wv;
    private OkHttpClient client;
    private File captureDir, harDir, zipDir;
    private final Set<String> urlSeen = new HashSet<>();
    private final Set<String> hashSeen = new HashSet<>();
    private final ConcurrentLinkedQueue<String> followQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> harQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger fileCount = new AtomicInteger(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final Map<String, Integer> typeCount = new HashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ExecutorService harPool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    // Fitur baru
    private final Map<String, String> cookies = new HashMap<>(); // cookie jar
    private final Map<String, String> sessionTokens = new HashMap<>(); // session capturer
    private final List<String> userAgents = new ArrayList<>(); // UA rotation
    private volatile boolean stealthMode = true;
    private volatile boolean harEnabled = true;
    private volatile boolean domainFilterEnabled = false;
    private final Set<String> domainWhitelist = new HashSet<>();
    private final Set<String> domainBlacklist = new HashSet<>();

    private EditText urlInput;
    private Button btnGo, btnZip, btnClear, btnToggle;
    private TextView statText, typeText, progressText;
    private LinearLayout logList;
    private ScrollView logScroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        userAgents.add("Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36");
        userAgents.add("Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        userAgents.add("Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");
        userAgents.add("Mozilla/5.0 (Linux; Android 11; moto g power) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36");
        userAgents.add("Mozilla/5.0 (Linux; Android 13; V2225) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36");

        client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

        File base = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), "WebSnatcher");
        captureDir = new File(base, "captures");
        harDir = new File(base, "har");
        zipDir = base;
        if (!captureDir.exists()) captureDir.mkdirs();
        if (!harDir.exists()) harDir.mkdirs();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0a0a0f"));
        root.addView(buildUI());

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
        s.setUserAgentString(userAgents.get(0));
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setBlockNetworkLoads(false);

        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("data:") || url.startsWith("blob:") ||
                    url.startsWith("about:") || url.startsWith("javascript:") ||
                    url.startsWith("file:")) return null;
                return intercept(url, req.getMethod());
            }
        });
        wv.loadUrl("about:blank");

        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        wv.setLayoutParams(wlp);
        root.addView(wv);

        setContentView(root);
    }

    private View buildUI() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(Color.parseColor("#13131f"));
        p.setPadding(dp(12), dp(12), dp(12), dp(8));

        // Row 1 — URL + GO
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);

        urlInput = new EditText(this);
        urlInput.setText("https://public.pg-demo.com/demo/?gi=65&lang=en");
        urlInput.setTextColor(Color.WHITE);
        urlInput.setTextSize(11);
        urlInput.setBackgroundColor(Color.parseColor("#0a0a0f"));
        urlInput.setPadding(dp(8), dp(8), dp(8), dp(8));
        urlInput.setSingleLine(true);
        r1.addView(urlInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnGo = new Button(this);
        btnGo.setText("▶ GO");
        btnGo.setTextSize(11);
        btnGo.setTextColor(Color.parseColor("#0a0a0f"));
        btnGo.setBackgroundColor(Color.parseColor("#4ade80"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        btnGo.setLayoutParams(lp);
        btnGo.setOnClickListener(v -> openWeb());
        r1.addView(btnGo);
        p.addView(r1);

        // Row 2 — Stats
        statText = new TextView(this);
        statText.setTextColor(Color.parseColor("#4ade80"));
        statText.setTextSize(11);
        statText.setTypeface(Typeface.MONOSPACE);
        statText.setText("0 files  0.00 MB  0 HAR");
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(6);
        statText.setLayoutParams(lp2);
        p.addView(statText);

        typeText = new TextView(this);
        typeText.setTextColor(Color.parseColor("#60a5fa"));
        typeText.setTextSize(10);
        typeText.setTypeface(Typeface.MONOSPACE);
        typeText.setText("types: -");
        p.addView(typeText);

        // Row 3 — Tombol
        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        lp2 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(6);
        r3.setLayoutParams(lp2);

        btnZip = new Button(this);
        btnZip.setText("💾 ZIP");
        btnZip.setTextSize(10);
        btnZip.setTextColor(Color.parseColor("#4ade80"));
        btnZip.setBackgroundColor(Color.parseColor("#2a1f4a"));
        btnZip.setEnabled(false);
        btnZip.setOnClickListener(v -> saveZip());
        r3.addView(btnZip, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnToggle = new Button(this);
        btnToggle.setText("⚙ STEALTH: ON");
        btnToggle.setTextSize(9);
        btnToggle.setTextColor(Color.parseColor("#4ade80"));
        btnToggle.setBackgroundColor(Color.parseColor("#2a1f4a"));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp3.leftMargin = dp(6);
        btnToggle.setLayoutParams(lp3);
        btnToggle.setOnClickListener(v -> {
            stealthMode = !stealthMode;
            btnToggle.setText(stealthMode ? "⚙ STEALTH: ON" : "⚙ STEALTH: OFF");
            log("Stealth: " + (stealthMode ? "ON" : "OFF"));
        });
        r3.addView(btnToggle);

        btnClear = new Button(this);
        btnClear.setText("🗑");
        btnClear.setTextSize(10);
        btnClear.setTextColor(Color.WHITE);
        btnClear.setBackgroundColor(Color.parseColor("#e74c3c"));
        LinearLayout.LayoutParams lpCl = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpCl.leftMargin = dp(6);
        btnClear.setLayoutParams(lpCl);
        btnClear.setOnClickListener(v -> clearCapture());
        r3.addView(btnClear);
        p.addView(r3);

        // Log
        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(140));
        lp4.topMargin = dp(6);
        logScroll.setLayoutParams(lp4);
        logScroll.setBackgroundColor(Color.parseColor("#0a0a0f"));

        logList = new LinearLayout(this);
        logList.setOrientation(LinearLayout.VERTICAL);
        logList.setPadding(dp(6), dp(6), dp(6), dp(6));
        logScroll.addView(logList);
        p.addView(logScroll);

        progressText = new TextView(this);
        progressText.setTextColor(Color.parseColor("#888"));
        progressText.setTextSize(9);
        progressText.setText("Idle");
        p.addView(progressText);

        return p;
    }

    private void openWeb() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) return;

        urlSeen.clear();
        hashSeen.clear();
        followQueue.clear();
        harQueue.clear();
        fileCount.set(0);
        totalBytes.set(0);
        typeCount.clear();
        sessionTokens.clear();
        btnGo.setEnabled(false);
        btnGo.setText("⏳");
        log("▶ Open: " + url);
        wv.loadUrl(url);
    }

    private WebResourceResponse intercept(String url, String method) {
        try {
            // Domain filter
            if (domainFilterEnabled) {
                try {
                    String host = new URL(url).getHost();
                    if (domainBlacklist.contains(host)) return null;
                    if (!domainWhitelist.isEmpty() && !domainWhitelist.contains(host)) return null;
                } catch (Exception ignored) {}
            }

            // UA rotation
            String ua = userAgents.get(random.nextInt(userAgents.size()));

            Request.Builder rb = new Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "cross-site");

            // Cookie forward
            String c = CookieManager.getInstance().getCookie(url);
            if (c != null && !c.isEmpty()) {
                rb.header("Cookie", c);
                parseCookies(c, url);
            }

            // Stealth delay
            if (stealthMode) {
                try { Thread.sleep(random.nextInt(80) + 20); } catch (Exception ignored) {}
            }

            // Request execution dengan retry
            Response res = executeWithRetry(rb.build(), 3);

            ResponseBody body = res.body();
            if (body == null) return null;
            byte[] data = body.bytes();

            // Auto-decompress
            data = decompressIfNeeded(data, res);

            // Save HAR entry
            if (harEnabled) {
                queueHar(url, method, res.code(), res.headers(), data);
            }

            // Save file
            saveFile(url, data);

            // Extract session tokens
            extractSessionTokens(url, data);

            String ctype = res.header("Content-Type", "application/octet-stream");
            String cenc = res.header("Content-Encoding", null);

            // CORS bypass headers
            Map<String, String> respHeaders = new HashMap<>();
            respHeaders.put("Access-Control-Allow-Origin", "*");
            respHeaders.put("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            respHeaders.put("Access-Control-Allow-Headers", "*");

            return new WebResourceResponse(ctype, null, res.code(),
                res.message() != null ? res.message() : "OK",
                respHeaders, new ByteArrayInputStream(data));
        } catch (Exception e) {
            Log.e("Snatcher", "Intercept fail " + url + ": " + e.getMessage());
            return null;
        }
    }

    private Response executeWithRetry(Request req, int maxRetries) throws IOException {
        IOException lastErr = null;
        for (int i = 0; i < maxRetries; i++) {
            try {
                Response res = client.newCall(req).execute();
                if (res.code() == 429 || res.code() == 503) {
                    // Rate limit — slow down
                    try { Thread.sleep((long) Math.pow(2, i) * 1000); } catch (Exception ignored) {}
                    res.close();
                    continue;
                }
                return res;
            } catch (IOException e) {
                lastErr = e;
                if (i < maxRetries - 1) {
                    try { Thread.sleep((long) Math.pow(2, i) * 500); } catch (Exception ignored) {}
                }
            }
        }
        throw lastErr != null ? lastErr : new IOException("Retry exhausted");
    }

    private byte[] decompressIfNeeded(byte[] data, Response res) {
        String enc = res.header("Content-Encoding");
        if (enc == null) return data;
        try {
            if (enc.contains("gzip")) {
                InputStream is = new GZIPInputStream(new ByteArrayInputStream(data));
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                return bos.toByteArray();
            }
        } catch (Exception ignored) {}
        return data;
    }

    private void parseCookies(String c, String url) {
        try {
            String host = new URL(url).getHost();
            for (String pair : c.split(";")) {
                pair = pair.trim();
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq).trim();
                    String v = pair.substring(eq + 1).trim();
                    cookies.put(host + "|" + k, v);
                }
            }
        } catch (Exception ignored) {}
    }

    private void extractSessionTokens(String url, byte[] data) {
        try {
            String text = new String(data, "UTF-8").substring(0, Math.min(data.length, 5000));
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\"(token|sessionId|accessToken|authorization|apiKey|authToken)\"\\s*:\\s*\"([^\"]+)\"");
            java.util.regex.Matcher m = p.matcher(text);
            while (m.find()) {
                sessionTokens.put(m.group(1), m.group(2));
                log("🔑 " + m.group(1) + " = " + m.group(2).substring(0, Math.min(20, m.group(2).length())) + "...");
            }
        } catch (Exception ignored) {}
    }

    private void queueHar(String url, String method, int status, okhttp3.Headers headers, byte[] data) {
        try {
            String har = buildHarEntry(url, method, status, headers, data);
            harQueue.add(har);
            if (harQueue.size() == 1) {
                harPool.execute(this::flushHar);
            }
        } catch (Exception ignored) {}
    }

    private String buildHarEntry(String url, String method, int status, okhttp3.Headers headers, byte[] data) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"startedDateTime\":\"").append(new java.util.Date().toString()).append("\",");
        sb.append("\"time\":0,");
        sb.append("\"request\":{");
        sb.append("\"method\":\"").append(method).append("\",");
        sb.append("\"url\":\"").append(url.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",");
        sb.append("\"httpVersion\":\"HTTP/2\",");
        sb.append("\"headers\":[],\"queryString\":[],\"cookies\":[],\"headersSize\":-1,\"bodySize\":0");
        sb.append("},");
        sb.append("\"response\":{");
        sb.append("\"status\":").append(status).append(",");
        sb.append("\"statusText\":\"OK\",");
        sb.append("\"httpVersion\":\"HTTP/2\",");
        sb.append("\"headers\":[],\"cookies\":[],\"content\":{");
        sb.append("\"size\":").append(data.length).append(",");
        sb.append("\"mimeType\":\"").append(headers.get("Content-Type") != null ? headers.get("Content-Type") : "application/octet-stream").append("\",");
        sb.append("\"text\":\"<base64 ").append(data.length).append(" bytes>\"");
        sb.append("},\"redirectURL\":\"\",\"headersSize\":-1,\"bodySize\":").append(data.length).append("},");
        sb.append("\"cache\":{},\"timings\":{\"send\":0,\"wait\":0,\"receive\":0}");
        sb.append("}");
        return sb.toString();
    }

    private void flushHar() {
        try {
            Thread.sleep(2000);
            if (harQueue.isEmpty()) return;
            File f = new File(harDir, "capture-" + System.currentTimeMillis() + ".har");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write("{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"SuperSnatcher\",\"version\":\"4.0\"},\"entries\":[".getBytes());
            boolean first = true;
            String entry;
            while ((entry = harQueue.poll()) != null) {
                if (!first) fos.write(",".getBytes());
                fos.write(entry.getBytes());
                first = false;
            }
            fos.write("]}}".getBytes());
            fos.close();
            ui.post(() -> log("📄 HAR: " + f.getName()));
        } catch (Exception ignored) {}
    }

    private void saveFile(String url, byte[] data) {
        try {
            URL u = new URL(url);
            String host = Helpers.safeHost(u.getHost());
            String path = Helpers.safePath(u.getPath());
            String type = Helpers.classify(path);

            String hash = Helpers.sha1(data);
            if (!hashSeen.add(hash)) return;

            File out = new File(captureDir, host + "/" + type + "/" + path);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(data);
            fos.close();

            int count = fileCount.incrementAndGet();
            long total = totalBytes.addAndGet(data.length);
            typeCount.put(type, typeCount.getOrDefault(type, 0) + 1);

            ui.post(() -> {
                statText.setText(count + " files  " + String.format("%.2f", total / 1024.0 / 1024.0) + " MB  " + harQueue.size() + " HAR");
                StringBuilder sb = new StringBuilder("types: ");
                for (Map.Entry<String, Integer> e : typeCount.entrySet()) {
                    sb.append(e.getKey()).append("=").append(e.getValue()).append(" ");
                }
                typeText.setText(sb.toString());
                addLog("[" + count + "] " + type + "/" + path + " (" + (data.length / 1024) + "K)");
                btnZip.setEnabled(true);
            });

            // Follow-up parse
            if (type.equals("js") || type.equals("css") || type.equals("html") ||
                type.equals("json") || type.equals("xml")) {
                String content = new String(data, "UTF-8");
                Set<String> found = Extractor.extract(url, content, type);
                for (String f : found) {
                    if (!urlSeen.contains(f)) followQueue.add(f);
                }
            }
        } catch (Exception e) {
            Log.e("Snatcher", "Save fail: " + e.getMessage());
        }
    }

    private final AtomicInteger crawlerRunning = new AtomicInteger(0);

    private void startCrawler() {
        if (crawlerRunning.getAndIncrement() > 0) return;
        pool.execute(() -> {
            try {
                while (true) {
                    String u = followQueue.poll();
                    if (u == null) {
                        if (followQueue.isEmpty()) {
                            try { Thread.sleep(2000); } catch (Exception ignored) {}
                            if (followQueue.isEmpty()) break;
                        }
                        continue;
                    }
                    if (!urlSeen.add(u)) continue;
                    try {
                        String ua = userAgents.get(random.nextInt(userAgents.size()));
                        Request req = new Request.Builder().url(u)
                            .header("User-Agent", ua)
                            .header("Referer", "https://public.pg-demo.com/")
                            .build();
                        Response res = client.newCall(req).execute();
                        ResponseBody body = res.body();
                        if (body != null) {
                            byte[] data = body.bytes();
                            if (data.length > 0 && data.length < 5 * 1024 * 1024) {
                                saveFile(u, data);
                            }
                        }
                    } catch (Exception ignored) {}
                    if (stealthMode) try { Thread.sleep(random.nextInt(100) + 30); } catch (Exception ignored) {}
                }
            } finally {
                crawlerRunning.decrementAndGet();
            }
        });
    }

    private void addLog(String msg) {
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextColor(Color.parseColor("#cccccc"));
        t.setTextSize(9);
        t.setTypeface(Typeface.MONOSPACE);
        logList.addView(t, 0);
        while (logList.getChildCount() > 200) logList.removeViewAt(logList.getChildCount() - 1);
    }

    private void log(String msg) {
        ui.post(() -> addLog(msg));
        startCrawler();
    }

    private void saveZip() {
        try {
            File zipFile = new File(zipDir, "snatch-" + System.currentTimeMillis() + ".zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
            for (File f : captureDir.listFiles()) addToZip(zos, f, "captures/");
            for (File f : harDir.listFiles()) addToZip(zos, f, "har/");
            // Save cookies + tokens
            StringBuilder meta = new StringBuilder("=== COOKIES ===\n");
            for (Map.Entry<String, String> e : cookies.entrySet()) meta.append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
            meta.append("\n=== SESSION TOKENS ===\n");
            for (Map.Entry<String, String> e : sessionTokens.entrySet()) meta.append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
            ZipEntry metaEntry = new ZipEntry("session-meta.txt");
            zos.putNextEntry(metaEntry);
            zos.write(meta.toString().getBytes());
            zos.closeEntry();
            zos.close();
            Toast.makeText(this, "✓ " + zipFile.getName(), Toast.LENGTH_LONG).show();
            log("💾 " + zipFile.getName());
        } catch (Exception e) {
            Toast.makeText(this, "Err: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addToZip(ZipOutputStream zos, File f, String base) throws Exception {
        if (f.isDirectory()) {
            zos.putNextEntry(new ZipEntry(base + f.getName() + "/"));
            zos.closeEntry();
            for (File c : f.listFiles()) addToZip(zos, c, base + f.getName() + "/");
        } else {
            zos.putNextEntry(new ZipEntry(base + f.getName()));
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
            fis.close();
            zos.closeEntry();
        }
    }

    private void clearCapture() {
        File[] fs = captureDir.listFiles();
        if (fs != null) for (File f : fs) if (f.isDirectory()) deleteRec(f); else f.delete();
        File[] hs = harDir.listFiles();
        if (hs != null) for (File f : hs) f.delete();
        urlSeen.clear();
        hashSeen.clear();
        followQueue.clear();
        harQueue.clear();
        cookies.clear();
        sessionTokens.clear();
        fileCount.set(0);
        totalBytes.set(0);
        typeCount.clear();
        statText.setText("0 files  0.00 MB  0 HAR");
        typeText.setText("types: -");
        logList.removeAllViews();
        btnZip.setEnabled(false);
        btnGo.setEnabled(true);
        btnGo.setText("▶ GO");
    }

    private void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File c : kids) if (c.isDirectory()) deleteRec(c); else c.delete();
        f.delete();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
            getResources().getDisplayMetrics());
    }

    @Override
    public void onBackPressed() {
        if (wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }
}
