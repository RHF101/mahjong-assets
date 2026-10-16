package com.rhf.snatcher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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

    // UI elements
    private TextView statFiles, statMB, progressLabel;
    private TextView fileList;
    private LinearLayout fileListContainer;
    private Button btnOpen, btnZip, btnClear;
    private EditText urlInput;
    private View webContainer;
    private View uiPanel;

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

        // ============ ROOT ============
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0a0a0f"));

        // ============ UI PANEL (top) ============
        uiPanel = buildUIPanel();
        root.addView(uiPanel);

        // ============ WEBVIEW (bottom) ============
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
                    url.startsWith("file:")) {
                    return null;
                }
                try {
                    Request.Builder rb = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                        .header("Accept", "*/*")
                        .header("Accept-Language", "en-US,en;q=0.9");

                    String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
                    if (cookie != null && !cookie.isEmpty()) rb.header("Cookie", cookie);

                    Response res = client.newCall(rb.build()).execute();
                    ResponseBody body = res.body();
                    if (body == null) return null;
                    byte[] bytes = body.bytes();

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

        wv.loadUrl("about:blank");

        // WebView dapet sisa space
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        wv.setLayoutParams(wlp);
        root.addView(wv);

        setContentView(root);
    }

    private View buildUIPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(Color.parseColor("#13131f"));
        p.setPadding(dp(12), dp(12), dp(12), dp(8));

        // ============ Row 1: URL + tombol ============
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        urlInput = new EditText(this);
        urlInput.setText("https://public.pg-demo.com/demo/?gi=65&lang=en");
        urlInput.setTextColor(Color.WHITE);
        urlInput.setTextSize(11);
        urlInput.setBackgroundColor(Color.parseColor("#0a0a0f"));
        urlInput.setPadding(dp(8), dp(8), dp(8), dp(8));
        urlInput.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        urlInput.setLayoutParams(lp);
        row1.addView(urlInput);

        btnOpen = new Button(this);
        btnOpen.setText("▶ GO");
        btnOpen.setTextSize(11);
        btnOpen.setTextColor(Color.parseColor("#0a0a0f"));
        btnOpen.setBackgroundColor(Color.parseColor("#4ade80"));
        btnOpen.setPadding(dp(10), dp(8), dp(10), dp(8));
        lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        btnOpen.setLayoutParams(lp);
        btnOpen.setOnClickListener(v -> openWeb());
        row1.addView(btnOpen);

        p.addView(row1);

        // ============ Row 2: Stats ============
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        row2.setLayoutParams(rlp);

        statFiles = new TextView(this);
        statFiles.setTextColor(Color.parseColor("#4ade80"));
        statFiles.setTextSize(11);
        statFiles.setTypeface(android.graphics.Typeface.MONOSPACE);
        statFiles.setText("0 files");
        row2.addView(statFiles);

        TextView sep = new TextView(this);
        sep.setText("  |  ");
        sep.setTextColor(Color.parseColor("#555"));
        sep.setTextSize(11);
        row2.addView(sep);

        statMB = new TextView(this);
        statMB.setTextColor(Color.parseColor("#4ade80"));
        statMB.setTextSize(11);
        statMB.setTypeface(android.graphics.Typeface.MONOSPACE);
        statMB.setText("0.00 MB");
        row2.addView(statMB);

        progressLabel = new TextView(this);
        progressLabel.setTextColor(Color.parseColor("#888"));
        progressLabel.setTextSize(10);
        progressLabel.setText("  Idle");
        progressLabel.setPadding(dp(8), 0, 0, 0);
        row2.addView(progressLabel);

        p.addView(row2);

        // ============ Row 3: Tombol2 ============
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r3lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r3lp.topMargin = dp(6);
        row3.setLayoutParams(r3lp);

        btnZip = new Button(this);
        btnZip.setText("💾 Save ZIP");
        btnZip.setTextSize(10);
        btnZip.setTextColor(Color.parseColor("#4ade80"));
        btnZip.setBackgroundColor(Color.parseColor("#2a1f4a"));
        btnZip.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnZip.setEnabled(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnZip.setLayoutParams(blp);
        btnZip.setOnClickListener(v -> saveZip());
        row3.addView(btnZip);

        btnClear = new Button(this);
        btnClear.setText("🗑");
        btnClear.setTextSize(10);
        btnClear.setTextColor(Color.WHITE);
        btnClear.setBackgroundColor(Color.parseColor("#e74c3c"));
        btnClear.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(6);
        btnClear.setLayoutParams(clp);
        btnClear.setOnClickListener(v -> clearCapture());
        row3.addView(btnClear);

        p.addView(row3);

        // ============ File list (compact, 60px height) ============
        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        svlp.topMargin = dp(6);
        sv.setLayoutParams(svlp);
        sv.setBackgroundColor(Color.parseColor("#0a0a0f"));

        fileListContainer = new LinearLayout(this);
        fileListContainer.setOrientation(LinearLayout.VERTICAL);
        fileListContainer.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView placeholder = new TextView(this);
        placeholder.setTextColor(Color.parseColor("#666"));
        placeholder.setTextSize(9);
        placeholder.setText("Belum ada file. Klik GO.");
        placeholder.setTag("placeholder");
        fileListContainer.addView(placeholder);

        sv.addView(fileListContainer);
        p.addView(sv);

        return p;
    }

    private void openWeb() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "URL kosong", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i("Snatcher", "Opening: " + url);
        btnOpen.setText("⏳");
        btnOpen.setEnabled(false);
        progressLabel.setText("  Loading...");
        wv.loadUrl(url);
    }

    private void saveCaptured(String url, byte[] data) {
        try {
            URL u = new URL(url);
            String host = u.getHost();
            String path = u.getPath();
            if (path.isEmpty() || path.endsWith("/")) path += "index.html";
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
                runOnUiThread(() -> updateUI(fname, data.length));
            }
        } catch (Exception e) {
            Log.e("Snatcher", "Save fail: " + e.getMessage());
        }
    }

    private void updateUI(String fname, int size) {
        statFiles.setText(captureCount + " files");
        statMB.setText((totalBytes / 1024 / 1024.0) + " MB");
        progressLabel.setText("  " + captureCount + " files");

        if (captureCount > 0) btnZip.setEnabled(true);

        // Hapus placeholder
        View ph = fileListContainer.findViewWithTag("placeholder");
        if (ph != null) fileListContainer.removeView(ph);

        TextView item = new TextView(this);
        String ext = fname.contains(".") ? fname.substring(fname.lastIndexOf('.') + 1).toLowerCase() : "file";
        String shortName = fname.length() > 60 ? "..." + fname.substring(fname.length() - 57) : fname;
        item.setText("[" + ext + "] " + shortName + " (" + (size / 1024) + "K)");
        item.setTextColor(Color.parseColor("#cccccc"));
        item.setTextSize(9);
        item.setTypeface(android.graphics.Typeface.MONOSPACE);
        item.setPadding(0, dp(2), 0, dp(2));
        fileListContainer.addView(item, 0);

        while (fileListContainer.getChildCount() > 100) {
            fileListContainer.removeViewAt(fileListContainer.getChildCount() - 1);
        }
    }

    private String detectMime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".json")) return "json";
        if (p.endsWith(".js")) return "js";
        if (p.endsWith(".css")) return "css";
        if (p.endsWith(".png")) return "png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "jpg";
        if (p.endsWith(".webp")) return "webp";
        if (p.endsWith(".mp3")) return "mp3";
        if (p.endsWith(".wasm")) return "wasm";
        return "file";
    }

    private void saveZip() {
        try {
            File outDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "WebSnatcher");
            if (!outDir.exists()) outDir.mkdirs();
            File zipFile = new File(outDir, "snatch-" + System.currentTimeMillis() + ".zip");

            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
            File[] files = captureDir.listFiles();
            if (files != null) for (File f : files) addToZip(zos, f, "");
            zos.close();

            Toast.makeText(this, "ZIP: " + zipFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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

    private void clearCapture() {
        File[] files = captureDir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteRecursive(f);
            else f.delete();
        }
        capturedUrls.clear();
        capturedFiles.clear();
        mimeTypes.clear();
        totalBytes = 0;
        captureCount = 0;

        statFiles.setText("0 files");
        statMB.setText("0.00 MB");
        progressLabel.setText("  Idle");
        btnZip.setEnabled(false);
        fileListContainer.removeAllViews();
        TextView ph = new TextView(this);
        ph.setText("Belum ada file. Klik GO.");
        ph.setTextColor(Color.parseColor("#666"));
        ph.setTextSize(9);
        ph.setTag("placeholder");
        fileListContainer.addView(ph);
    }

    private void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File c : kids) {
            if (c.isDirectory()) deleteRecursive(c);
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
