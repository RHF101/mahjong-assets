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
import android.view.Gravity;
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
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private File zipDir;
    private final Set<String> urlSeen = new HashSet<>();
    private final Set<String> hashSeen = new HashSet<>();
    private final ConcurrentLinkedQueue<String> followQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger fileCount = new AtomicInteger(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final Map<String, Integer> typeCount = new HashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText urlInput;
    private Button btnGo, btnZip, btnClear;
    private TextView statText, progressText, typeText;
    private LinearLayout logList;
    private ScrollView logScroll;

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
        zipDir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), "WebSnatcher");
        if (!captureDir.exists()) captureDir.mkdirs();
        if (!zipDir.exists()) zipDir.mkdirs();

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
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36");
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
                return intercept(url);
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
        btnGo.setPadding(dp(12), dp(8), dp(12), dp(8));
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
        statText.setText("0 files  0.00 MB");
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

        // Row 3 — Buttons
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
        btnZip.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnZip.setEnabled(false);
        btnZip.setOnClickListener(v -> saveZip());
        r3.addView(btnZip, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnClear = new Button(this);
        btnClear.setText("🗑");
        btnClear.setTextSize(10);
        btnClear.setTextColor(Color.WHITE);
        btnClear.setBackgroundColor(Color.parseColor("#e74c3c"));
        btnClear.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lpCl = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpCl.leftMargin = dp(6);
        btnClear.setLayoutParams(lpCl);
        btnClear.setOnClickListener(v -> clearCapture());
        r3.addView(btnClear);
        p.addView(r3);

        // Log view
        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(140));
        lp3.topMargin = dp(6);
        logScroll.setLayoutParams(lp3);
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
        // Reset
        urlSeen.clear();
        hashSeen.clear();
        followQueue.clear();
        fileCount.set(0);
        totalBytes.set(0);
        typeCount.clear();
        btnGo.setEnabled(false);
        btnGo.setText("⏳");
        log("▶ Open: " + url);
        wv.loadUrl(url);
    }

    private WebResourceResponse intercept(String url) {
        try {
            if (!urlSeen.add(url)) {
                // Udah pernah — skip
            }
            Request.Builder rb = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9");
            String c = CookieManager.getInstance().getCookie(url);
            if (c != null && !c.isEmpty()) rb.header("Cookie", c);

            Response res = client.newCall(rb.build()).execute();
            ResponseBody body = res.body();
            if (body == null) return null;
            byte[] data = body.bytes();

            saveFile(url, data);

            String ctype = res.header("Content-Type", "application/octet-stream");
            String cenc = res.header("Content-Encoding", null);
            return new WebResourceResponse(ctype, cenc, new ByteArrayInputStream(data));
        } catch (Exception e) {
            Log.e("Snatcher", "Intercept fail " + url + ": " + e.getMessage());
            return null;
        }
    }

    private void saveFile(String url, byte[] data) {
        try {
            URL u = new URL(url);
            String host = Helpers.safeHost(u.getHost());
            String path = Helpers.safePath(u.getPath());
            String type = Helpers.classify(path);

            String hash = Helpers.sha1(data);
            if (!hashSeen.add(hash)) {
                // Duplikat — skip
                return;
            }

            File out = new File(captureDir, host + "/" + type + "/" + path);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(data);
            fos.close();

            int count = fileCount.incrementAndGet();
            long total = totalBytes.addAndGet(data.length);
            typeCount.put(type, typeCount.getOrDefault(type, 0) + 1);

            // Update UI
            ui.post(() -> {
                statText.setText(count + " files  " + (total / 1024.0 / 1024.0) + " MB");
                StringBuilder sb = new StringBuilder("types: ");
                for (Map.Entry<String, Integer> e : typeCount.entrySet()) {
                    sb.append(e.getKey()).append("=").append(e.getValue()).append(" ");
                }
                typeText.setText(sb.toString());
                addLog("[" + count + "] " + type + "/" + path + " (" + (data.length / 1024) + "K)");
                btnZip.setEnabled(true);
            });

            // Parse buat follow-up (JS/CSS/HTML/JSON/XML)
            if (type.equals("js") || type.equals("css") || type.equals("html") ||
                type.equals("json") || type.equals("xml")) {
                String content = new String(data, "UTF-8");
                Set<String> found = Extractor.extract(url, content, type);
                for (String f : found) {
                    if (!urlSeen.contains(f)) followQueue.add(f);
                }
                if (!found.isEmpty()) {
                    ui.post(() -> log("  ↳ found " + found.size() + " urls in " + path));
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
                            try { Thread.sleep(1500); } catch (Exception ignored) {}
                            if (followQueue.isEmpty()) break;
                        }
                        continue;
                    }
                    if (!urlSeen.add(u)) continue;
                    try {
                        Request req = new Request.Builder().url(u)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
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
        t.setPadding(0, dp(1), 0, dp(1));
        logList.addView(t, 0);
        while (logList.getChildCount() > 200) {
            logList.removeViewAt(logList.getChildCount() - 1);
        }
    }

    private void log(String msg) {
        ui.post(() -> addLog(msg));
        startCrawler();
    }

    private void saveZip() {
        try {
            File zipFile = new File(zipDir, "snatch-" + System.currentTimeMillis() + ".zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
            File[] files = captureDir.listFiles();
            if (files != null) for (File f : files) addToZip(zos, f, "");
            zos.close();
            Toast.makeText(this, "✓ ZIP: " + zipFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            log("💾 " + zipFile.getName());
        } catch (Exception e) {
            Toast.makeText(this, "Err: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addToZip(ZipOutputStream zos, File f, String base) throws Exception {
        String entry = base + f.getName();
        if (f.isDirectory()) {
            zos.putNextEntry(new ZipEntry(entry + "/"));
            zos.closeEntry();
            File[] kids = f.listFiles();
            if (kids != null) for (File c : kids) addToZip(zos, c, entry + "/");
        } else {
            zos.putNextEntry(new ZipEntry(entry));
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
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) deleteRec(f);
            else f.delete();
        }
        urlSeen.clear();
        hashSeen.clear();
        followQueue.clear();
        fileCount.set(0);
        totalBytes.set(0);
        typeCount.clear();
        statText.setText("0 files  0.00 MB");
        typeText.setText("types: -");
        logList.removeAllViews();
        btnZip.setEnabled(false);
        btnGo.setEnabled(true);
        btnGo.setText("▶ GO");
    }

    private void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File c : kids) {
            if (c.isDirectory()) deleteRec(c);
            else c.delete();
        }
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
