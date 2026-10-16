package com.rhf.v5;

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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private WebView wv;
    private File captureDir;
    private final Set<String> urlSeen = new HashSet<>();
    private final Set<String> hashSeen = new HashSet<>();
    private int fileCount = 0;
    private long totalBytes = 0;
    private final Map<String, Integer> typeCount = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText urlInput;
    private Button btnGo, btnZip, btnClear;
    private TextView statText, typeText;
    private LinearLayout logList;
    private ScrollView logScroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i("SnatcherV5", "onCreate started");

        captureDir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), "SnatcherV5/captures");
        if (!captureDir.exists()) {
            boolean ok = captureDir.mkdirs();
            Log.i("SnatcherV5", "Capture dir created: " + ok + " at " + captureDir.getAbsolutePath());
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0a0a0f"));

        root.addView(buildUI());

        try {
            wv = new WebView(this);
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(true);
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setUserAgentString("Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36");
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

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
        } catch (Exception e) {
            Log.e("SnatcherV5", "WebView init error", e);
            addLog("WebView error: " + e.getMessage());
        }

        setContentView(root);
        Log.i("SnatcherV5", "onCreate done");
    }

    private View buildUI() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(Color.parseColor("#13131f"));
        p.setPadding(dp(10), dp(10), dp(10), dp(8));

        // Row 1
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
        btnGo.setText("GO");
        btnGo.setTextSize(11);
        btnGo.setTextColor(Color.parseColor("#0a0a0f"));
        btnGo.setBackgroundColor(Color.parseColor("#4ade80"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        btnGo.setLayoutParams(lp);
        btnGo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWeb(); }
        });
        r1.addView(btnGo);
        p.addView(r1);

        // Stats
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

        // Buttons
        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        lp2 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(6);
        r3.setLayoutParams(lp2);

        btnZip = new Button(this);
        btnZip.setText("ZIP");
        btnZip.setTextSize(10);
        btnZip.setTextColor(Color.parseColor("#4ade80"));
        btnZip.setBackgroundColor(Color.parseColor("#2a1f4a"));
        btnZip.setEnabled(false);
        btnZip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveZip(); }
        });
        r3.addView(btnZip, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnClear = new Button(this);
        btnClear.setText("X");
        btnClear.setTextSize(10);
        btnClear.setTextColor(Color.WHITE);
        btnClear.setBackgroundColor(Color.parseColor("#e74c3c"));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.leftMargin = dp(6);
        btnClear.setLayoutParams(lp3);
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearCapture(); }
        });
        r3.addView(btnClear);
        p.addView(r3);

        // Log
        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(150));
        lp4.topMargin = dp(6);
        logScroll.setLayoutParams(lp4);
        logScroll.setBackgroundColor(Color.parseColor("#0a0a0f"));

        logList = new LinearLayout(this);
        logList.setOrientation(LinearLayout.VERTICAL);
        logList.setPadding(dp(6), dp(6), dp(6), dp(6));
        logScroll.addView(logList);
        p.addView(logScroll);

        return p;
    }

    private void openWeb() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) return;
        urlSeen.clear();
        hashSeen.clear();
        fileCount = 0;
        totalBytes = 0;
        typeCount.clear();
        btnGo.setEnabled(false);
        addLog("> " + url);
        try {
            wv.loadUrl(url);
        } catch (Exception e) {
            addLog("loadUrl error: " + e.getMessage());
        }
    }

    private WebResourceResponse intercept(String url) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            byte[] data = bos.toByteArray();

            saveFile(url, data);

            String ctype = conn.getContentType();
            if (ctype == null) ctype = "application/octet-stream";
            String cenc = conn.getContentEncoding();

            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");

            return new WebResourceResponse(ctype, cenc, code, "OK", headers, new ByteArrayInputStream(data));
        } catch (Exception e) {
            Log.e("SnatcherV5", "intercept fail: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void saveFile(String url, byte[] data) {
        try {
            URL u = new URL(url);
            String host = safe(u.getHost());
            String path = u.getPath();
            if (path == null || path.isEmpty() || path.endsWith("/")) path = "/index.html";
            int dot = path.lastIndexOf('.');
            String ext = "";
            if (dot >= 0) {
                ext = path.substring(dot + 1).toLowerCase();
                if (ext.length() > 6) ext = ext.substring(0, 6);
            }
            if (ext.isEmpty()) ext = "html";
            String type = classify(ext);

            String pathSafe = path.replaceAll("[?].*$", "");
            pathSafe = pathSafe.replaceAll("[^a-zA-Z0-9._/-]", "_");
            if (pathSafe.length() > 180) pathSafe = pathSafe.substring(pathSafe.length() - 180);
            if (pathSafe.startsWith("/")) pathSafe = pathSafe.substring(1);

            File out = new File(captureDir, host + "/" + type + "/" + pathSafe);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(data);
            fos.close();

            fileCount++;
            totalBytes += data.length;
            Integer c = typeCount.get(type);
            typeCount.put(type, c == null ? 1 : c + 1);

            final int fc = fileCount;
            final long tb = totalBytes;
            ui.post(new Runnable() {
                @Override public void run() {
                    statText.setText(fc + " files  " + String.format("%.2f", tb / 1024.0 / 1024.0) + " MB");
                    StringBuilder sb = new StringBuilder("types: ");
                    for (Map.Entry<String, Integer> e : typeCount.entrySet()) {
                        sb.append(e.getKey()).append("=").append(e.getValue()).append(" ");
                    }
                    typeText.setText(sb.toString());
                    addLog("[" + fc + "] " + type + "/" + pathSafe + " (" + (data.length / 1024) + "K)");
                    btnZip.setEnabled(true);
                }
            });
        } catch (Exception e) {
            Log.e("SnatcherV5", "save fail: " + e.getMessage());
        }
    }

    private String classify(String ext) {
        if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("gif") ||
            ext.equals("webp") || ext.equals("svg") || ext.equals("ico") || ext.equals("bmp")) return "img";
        if (ext.equals("mp3") || ext.equals("ogg") || ext.equals("wav") || ext.equals("m4a") || ext.equals("aac")) return "audio";
        if (ext.equals("mp4") || ext.equals("webm") || ext.equals("mkv") || ext.equals("mov")) return "video";
        if (ext.equals("ttf") || ext.equals("otf") || ext.equals("woff") || ext.equals("woff2")) return "font";
        if (ext.equals("js") || ext.equals("mjs")) return "js";
        if (ext.equals("css")) return "css";
        if (ext.equals("json")) return "json";
        if (ext.equals("xml") || ext.equals("plist") || ext.equals("atlas")) return "xml";
        if (ext.equals("html") || ext.equals("htm")) return "html";
        if (ext.equals("wasm")) return "wasm";
        return "other";
    }

    private String safe(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }

    private void addLog(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (logList == null) return;
                TextView t = new TextView(MainActivity.this);
                t.setText(msg);
                t.setTextColor(Color.parseColor("#cccccc"));
                t.setTextSize(9);
                t.setTypeface(Typeface.MONOSPACE);
                logList.addView(t, 0);
                while (logList.getChildCount() > 200) logList.removeViewAt(logList.getChildCount() - 1);
            }
        });
    }

    private void saveZip() {
        try {
            File zipFile = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "SnatcherV5/snatch-" + System.currentTimeMillis() + ".zip");
            File parent = zipFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile));
            File[] files = captureDir.listFiles();
            if (files != null) for (File f : files) addToZip(zos, f, "");
            zos.close();
            Toast.makeText(this, "ZIP: " + zipFile.getName(), Toast.LENGTH_LONG).show();
            addLog("ZIP: " + zipFile.getAbsolutePath());
        } catch (Exception e) {
            Toast.makeText(this, "Err: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addToZip(ZipOutputStream zos, File f, String base) throws IOException {
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
            if (f.isDirectory()) deleteRec(f); else f.delete();
        }
        urlSeen.clear();
        hashSeen.clear();
        fileCount = 0;
        totalBytes = 0;
        typeCount.clear();
        statText.setText("0 files  0.00 MB");
        typeText.setText("types: -");
        logList.removeAllViews();
        btnZip.setEnabled(false);
        btnGo.setEnabled(true);
    }

    private void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File c : kids) {
            if (c.isDirectory()) deleteRec(c); else c.delete();
        }
        f.delete();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
            getResources().getDisplayMetrics());
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }
}
