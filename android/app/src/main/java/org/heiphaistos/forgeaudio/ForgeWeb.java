package org.heiphaistos.forgeaudio;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * What the Forge Audio page needs from the WebView and Capacitor does not provide:
 * links to other sites open in the browser, real fullscreen video, and downloads (server files and exported JSON).
 */
@CapacitorPlugin(name = "ForgeWeb")
public class ForgeWeb extends Plugin {

    /** www/forge-native.js (exported files); injected into the server page by MainActivity's poll. */
    static String nativeJs = "";

    /** Installed by MainActivity once the bridge is ready (Capacitor sets its own client after loading plugins). Lets Back leave fullscreen first. */
    static FullscreenChromeClient chrome;

    @Override
    public void load() {
        WebView webView = getBridge().getWebView();
        webView.addJavascriptInterface(new NativeFiles(getContext()), "ForgeNative");
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> download(url, userAgent, contentDisposition, mimeType));
        try (java.io.InputStream in = getContext().getAssets().open("public/forge-native.js")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            nativeJs = out.toString("UTF-8");
        } catch (java.io.IOException ignored) {
            // asset missing: exports simply do nothing
        }
    }

    /**
     * Pages of the server stay in the app; any other site (source on YouTube, GitHub, mail…) opens in its own app.
     * The bundled pages (setup, offline) are on localhost and may go to any server.
     */
    @Override
    public Boolean shouldOverrideLoad(Uri url) {
        String scheme = url.getScheme();
        if ("blob".equals(scheme) || "data".equals(scheme) || "javascript".equals(scheme)) return null;
        if ("http".equals(scheme) || "https".equals(scheme)) {
            Uri current = Uri.parse(String.valueOf(getBridge().getWebView().getUrl()));
            String host = current.getHost();
            // Bundled pages (localhost: setup, offline) may go anywhere, and anything may go back to them.
            if (host == null || "localhost".equals(host) || "localhost".equals(url.getHost()) || host.equals(url.getHost())) return false;
        }
        try {
            getActivity().startActivity(new Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
            // nothing can open it
        }
        return true;
    }

    private void download(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return; // handled by www/forge-native.js
        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(name)
                .setDescription("Forge Audio")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // Public Downloads folder needs no permission from Android 10; before, the app's own download folder.
            if (Build.VERSION.SDK_INT >= 29) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            else request.setDestinationInExternalFilesDir(getContext(), Environment.DIRECTORY_DOWNLOADS, name);
            if (mimeType != null && !mimeType.isEmpty()) request.setMimeType(mimeType);
            getContext().getSystemService(DownloadManager.class).enqueue(request);
            Toast.makeText(getContext(), "Téléchargement lancé : " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "Téléchargement impossible", Toast.LENGTH_SHORT).show();
        }
    }

    /** Called from the page with the content of an exported file. */
    static class NativeFiles {
        private final Context context;

        NativeFiles(Context context) {
            this.context = context.getApplicationContext();
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (safe.isEmpty()) safe = "forge-audio";
            boolean ok = false;
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                if (data.length > 50 * 1024 * 1024) throw new IllegalArgumentException("too large");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    values.put(MediaStore.Downloads.MIME_TYPE, mime);
                    Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                            if (out != null) { out.write(data); ok = true; }
                        }
                    }
                } else {
                    // ponytail: before Android 10, app folder (no storage permission); the toast gives the path.
                    File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    try (FileOutputStream out = new FileOutputStream(new File(dir, safe))) { out.write(data); ok = true; }
                }
            } catch (Exception ignored) {
                // reported below
            }
            final String message = ok ? "Enregistré dans Téléchargements : " + safe : "Enregistrement impossible";
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
        }
    }

    /** Capacitor refuses fullscreen (it hides the custom view at once): show it over the app, bars hidden, in landscape. */
    static class FullscreenChromeClient extends BridgeWebChromeClient {
        private final Activity activity;
        private View custom;
        private CustomViewCallback callback;
        /** Android 13+: Back reaches the back dispatcher, not the key events, and the WebView's own handler wins: register above it. */
        private Object backCallback;

        FullscreenChromeClient(Bridge bridge) {
            super(bridge);
            this.activity = bridge.getActivity();
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback cb) {
            if (custom != null) { cb.onCustomViewHidden(); return; }
            custom = view;
            callback = cb;
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(activity.getWindow(), decor);
            bars.hide(WindowInsetsCompat.Type.systemBars());
            bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            if (Build.VERSION.SDK_INT >= 33) {
                OnBackInvokedCallback cbBack = this::onHideCustomView;
                activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cbBack);
                backCallback = cbBack;
            }
        }

        @Override
        public void onHideCustomView() {
            if (custom == null) return;
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.removeView(custom);
            custom = null;
            if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
                activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
                backCallback = null;
            }
            WindowCompat.getInsetsController(activity.getWindow(), decor).show(WindowInsetsCompat.Type.systemBars());
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            if (callback != null) callback.onCustomViewHidden();
            callback = null;
        }

        boolean isFullscreen() {
            return custom != null;
        }

        boolean exitFullscreen() {
            if (custom == null) return false;
            onHideCustomView();
            return true;
        }
    }
}
