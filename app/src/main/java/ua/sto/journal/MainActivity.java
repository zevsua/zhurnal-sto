package ua.sto.journal;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {

    private static final int REQ_SAVE = 1;
    private static final int REQ_OPEN = 2;
    private static final int REQ_CAMERA = 3;
    private static final int REQ_GALLERY = 4;
    private static final String START_URL = "file:///android_asset/index.html";
    private static final String PHOTO_HOST = "photo.sto.local";
    private static final int PHOTO_MAX = 1600;   // розмір фото по довшій стороні
    private static final int THUMB_MAX = 360;    // розмір мініатюри

    private final Object pendingLock = new Object();
    private final Random rnd = new Random();

    private WebView web;
    private String pendingBackup;
    private String photoJobId;    // до якого запису додаємо фото
    private File cameraFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF14171C);
        getWindow().setNavigationBarColor(0xFF14171C);

        if (savedInstanceState != null) {
            photoJobId = savedInstanceState.getString("photoJobId");
            String cf = savedInstanceState.getString("cameraFile");
            if (cf != null) cameraFile = new File(cf);
        }

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
                if (PHOTO_HOST.equals(u.getHost())) return true;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }

            // Фото віддаємо сторінці з пам'яті програми — без інтернету.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!PHOTO_HOST.equals(u.getHost())) return null;
                String name = u.getLastPathSegment();
                HashMap<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "max-age=31536000");
                if (name != null && name.matches("[A-Za-z0-9_-]+\\.jpg")) {
                    File f = new File(photoDir(), name);
                    if (f.isFile()) {
                        try {
                            return new WebResourceResponse("image/jpeg", null, 200, "OK", headers, new FileInputStream(f));
                        } catch (Exception ignored) { }
                    }
                }
                return new WebResourceResponse("image/jpeg", null, 404, "Not Found", headers,
                        new ByteArrayInputStream(new byte[0]));
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
        if (photoJobId != null) outState.putString("photoJobId", photoJobId);
        if (cameraFile != null) outState.putString("cameraFile", cameraFile.getAbsolutePath());
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
        boolean ok = resultCode == RESULT_OK;
        Uri uri = (ok && data != null) ? data.getData() : null;

        if (requestCode == REQ_SAVE) {
            boolean saved = false;
            if (uri != null && pendingBackup != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os != null) {
                        os.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                        saved = true;
                    }
                } catch (Exception ignored) { }
            }
            pendingBackup = null;
            js("window.onBackupSaved && window.onBackupSaved(" + saved + ")");

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

        } else if (requestCode == REQ_CAMERA) {
            final File f = cameraFile;
            if (ok && f != null && f.length() > 0) {
                List<Uri> list = new ArrayList<>();
                list.add(Uri.fromFile(f));
                processPhotos(photoJobId, list, f);
            }

        } else if (requestCode == REQ_GALLERY && ok && data != null) {
            List<Uri> list = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) list.add(u);
                }
            } else if (uri != null) {
                list.add(uri);
            }
            if (!list.isEmpty()) processPhotos(photoJobId, list, null);
        }
    }

    // ---------- фото ----------

    private File photoDir() {
        File d = new File(getFilesDir(), "photos");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("sto", MODE_PRIVATE);
    }

    private void startPhoto(String jobId, String source) {
        photoJobId = jobId;
        if ("camera".equals(source)) {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.isDirectory()) dir.mkdirs();
            cameraFile = new File(dir, "cam.jpg");
            if (cameraFile.exists()) cameraFile.delete();
            Uri out = FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, out);
            i.setClipData(ClipData.newRawUri("photo", out));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivityForResult(i, REQ_CAMERA);
            } catch (ActivityNotFoundException e) {
                js("window.onPhotoError && window.onPhotoError('Не знайдено програми камери')");
            }
        } else {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(Intent.createChooser(i, "Виберіть фото"), REQ_GALLERY);
            } catch (ActivityNotFoundException e) {
                js("window.onPhotoError && window.onPhotoError('Не знайдено галереї')");
            }
        }
    }

    /** Стискає фото у фоні, зберігає в пам'ять програми і повідомляє сторінку. */
    private void processPhotos(final String jobId, final List<Uri> sources, final File tempToDelete) {
        if (jobId == null) return;
        new Thread(() -> {
            int failed = 0;
            for (Uri src : sources) {
                try {
                    String id = savePhoto(src);
                    addPending(jobId, id);
                } catch (Throwable t) {
                    failed++;
                }
            }
            if (tempToDelete != null) tempToDelete.delete();
            final int f = failed;
            runOnUiThread(() -> {
                js("window.onPhotosAdded && window.onPhotosAdded()");
                if (f > 0) js("window.onPhotoError && window.onPhotoError('Не вдалося додати фото: " + f + "')");
            });
        }).start();
    }

    private InputStream open(Uri src) throws Exception {
        InputStream is = getContentResolver().openInputStream(src);
        if (is == null) throw new Exception("no stream");
        return is;
    }

    private String savePhoto(Uri src) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = open(src)) {
            BitmapFactory.decodeStream(is, null, o);
        }
        int longest = Math.max(o.outWidth, o.outHeight);
        if (longest <= 0) throw new Exception("not an image");
        int sample = 1;
        while (longest / (sample * 2) >= PHOTO_MAX) sample *= 2;

        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream is = open(src)) {
            bm = BitmapFactory.decodeStream(is, null, o2);
        }
        if (bm == null) throw new Exception("decode failed");

        int rotate = 0;
        try (InputStream is = open(src)) {
            int or = new ExifInterface(is).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) rotate = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) rotate = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) rotate = 270;
        } catch (Exception ignored) { }

        Bitmap full = scale(bm, PHOTO_MAX, rotate);
        if (full != bm) bm.recycle();
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(rnd.nextInt(1 << 30), 36);
        File dir = photoDir();
        write(full, new File(dir, id + ".jpg"), 82);
        Bitmap thumb = scale(full, THUMB_MAX, 0);
        write(thumb, new File(dir, id + "_t.jpg"), 75);
        if (thumb != full) thumb.recycle();
        full.recycle();
        return id;
    }

    private static Bitmap scale(Bitmap bm, int max, int rotate) {
        int w = bm.getWidth(), h = bm.getHeight();
        float s = Math.min(1f, max / (float) Math.max(w, h));
        if (s >= 1f && rotate == 0) return bm;
        Matrix m = new Matrix();
        m.postScale(s, s);
        if (rotate != 0) m.postRotate(rotate);
        return Bitmap.createBitmap(bm, 0, 0, w, h, m, true);
    }

    private static void write(Bitmap bm, File f, int quality) throws Exception {
        try (FileOutputStream os = new FileOutputStream(f)) {
            if (!bm.compress(Bitmap.CompressFormat.JPEG, quality, os)) throw new Exception("compress failed");
        }
    }

    private void addPending(String jobId, String photoId) throws Exception {
        synchronized (pendingLock) {
            JSONArray arr = new JSONArray(prefs().getString("pending", "[]"));
            arr.put(new JSONObject().put("jobId", jobId).put("photoId", photoId));
            prefs().edit().putString("pending", arr.toString()).commit();
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

        @JavascriptInterface
        public void addPhoto(String jobId, String source) {
            if (jobId == null || jobId.isEmpty()) return;
            runOnUiThread(() -> startPhoto(jobId, source));
        }

        @JavascriptInterface
        public void deletePhoto(String photoId) {
            if (photoId == null || !photoId.matches("[A-Za-z0-9_-]+")) return;
            new File(photoDir(), photoId + ".jpg").delete();
            new File(photoDir(), photoId + "_t.jpg").delete();
        }

        /** Віддає нові фото, які ще не прив'язані до записів, і очищає список. */
        @JavascriptInterface
        public String takePendingPhotos() {
            synchronized (pendingLock) {
                String s = prefs().getString("pending", "[]");
                prefs().edit().remove("pending").commit();
                return s;
            }
        }
    }
}
