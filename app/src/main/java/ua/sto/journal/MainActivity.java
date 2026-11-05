package ua.sto.journal;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQ_SAVE = 1;
    private static final int REQ_OPEN = 2;
    private static final String START_URL = "file:///android_asset/index.html";

    private WebView web;
    private String pendingBackup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF14171C);
        getWindow().setNavigationBarColor(0xFF14171C);

        web = new WebView(this);
        web.setBackgroundColor(0xFF14171C);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // localStorage — тут зберігаються всі записи
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        // Спершу закриваємо відкрите вікно в програмі, а якщо нічого не відкрито — виходимо.
        web.evaluateJavascript("(window.onAndroidBack && window.onAndroidBack()) ? 'yes' : 'no'", value -> {
            if (!"\"yes\"".equals(value)) finish();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;

        if (requestCode == REQ_SAVE) {
            boolean ok = false;
            if (uri != null && pendingBackup != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os != null) {
                        os.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                        ok = true;
                    }
                } catch (Exception ignored) { }
            }
            pendingBackup = null;
            js("window.onBackupSaved && window.onBackupSaved(" + ok + ")");
        } else if (requestCode == REQ_OPEN && uri != null) {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) return;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = is.read(b)) > 0) buf.write(b, 0, n);
                String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                js("window.onBackupLoaded && window.onBackupLoaded(" + JSONObject.quote(text) + ")");
            } catch (Exception e) {
                js("window.onBackupLoaded && window.onBackupLoaded('')");
            }
        }
    }

    private void js(String code) {
        web.post(() -> web.evaluateJavascript(code, null));
    }

    /** Те, що сторінка може викликати як window.Android.* */
    private class Bridge {
        @JavascriptInterface
        public void saveBackup(String json, String fileName) {
            runOnUiThread(() -> {
                pendingBackup = json;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (ActivityNotFoundException e) {
                    pendingBackup = null;
                    js("window.onBackupSaved && window.onBackupSaved(false)");
                }
            });
        }

        @JavascriptInterface
        public void openBackup() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_OPEN);
                } catch (ActivityNotFoundException ignored) { }
            });
        }
    }
}
