package org.heiphaistos.forgeaudio;

import android.Manifest;
import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hosts the Forge Audio web app and keeps it playing in the background.
 *
 * The web app exposes window.__forgeNowPlaying() (current track), window.__forgeRemote(action, value)
 * (play / pause / next / prev / seek), window.__forgeBack() and window.__forgeOpenLink(text). This activity
 * polls the first to drive the media notification ({@link PlaybackService}) and forwards notification,
 * headset, Back and share actions to the others.
 */
public class MainActivity extends BridgeActivity {

    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollNowPlaying;
    /** Link shared to the app, delivered once the player page is ready. */
    private String pendingShare;

    private static final String NOW_PLAYING_JS =
        "(function(){try{return window.__forgeNowPlaying?JSON.stringify(window.__forgeNowPlaying()):null}catch(e){return null}})()";
    private static final String RELEASES_API = "https://api.github.com/repos/Heiphaistos/Forge-Audio-Android/releases/latest";
    private static final String APK_URL = "https://forgeaudio.heiphaistos.org/ForgeAudio-android.apk";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ForgeWeb.class);
        super.onCreate(savedInstanceState);
        current = new WeakReference<>(this);

        WebView webView = getBridge().getWebView();
        ForgeWeb.chrome = new ForgeWeb.FullscreenChromeClient(getBridge());
        webView.setWebChromeClient(ForgeWeb.chrome);
        keepClearOfSystemBars((View) webView.getParent());
        // The player starts tracks by itself (next track, radio): no user gesture needed.
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }

        // Back: leave fullscreen video, close the open panel or go back a view in the web app;
        // with nothing left, go to the home screen instead of closing (closing would stop the music).
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (ForgeWeb.chrome != null && ForgeWeb.chrome.exitFullscreen()) return;
                WebView wv = getBridge().getWebView();
                wv.evaluateJavascript("(function(){try{return !!(window.__forgeBack&&window.__forgeBack())}catch(e){return false}})()", handled -> {
                    if ("true".equals(handled)) return;
                    if (wv.canGoBack()) wv.goBack();
                    else moveTaskToBack(true);
                });
            }
        });

        takeShare(getIntent());
        handler.post(poll);
        handler.postDelayed(this::checkUpdate, 8000);
    }

    /**
     * The app stops above the navigation buttons / gesture bar and below the status bar and camera cutout,
     * on every phone and WebView version (Android 15+ draws apps edge to edge). The keyboard pushes it up too.
     */
    private static void keepClearOfSystemBars(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** The fullscreen video view swallows the Back key before the back dispatcher sees it. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && ForgeWeb.chrome != null && ForgeWeb.chrome.isFullscreen()) {
            if (event.getAction() == KeyEvent.ACTION_UP) ForgeWeb.chrome.exitFullscreen();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeShare(intent);
    }

    /** "Share → Forge Audio" from YouTube, Spotify, SoundCloud…: the link is played or imported. */
    private void takeShare(Intent intent) {
        if (intent == null) return;
        String text = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && !text.trim().isEmpty()) pendingShare = text;
    }

    private void deliverShare() {
        if (pendingShare == null) return;
        String text = pendingShare;
        getBridge().getWebView().evaluateJavascript(
            "(function(){if(!window.__forgeOpenLink)return false;window.__forgeOpenLink(" + JSONObject.quote(text) + ");return true})()",
            done -> { if ("true".equals(done) && text.equals(pendingShare)) pendingShare = null; });
    }

    @Override
    public void onPause() {
        super.onPause();
        // Keep JavaScript running in the background: it chains tracks and answers the notification.
        WebView webView = getBridge().getWebView();
        webView.onResume();
        webView.resumeTimers();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopService(new Intent(this, PlaybackService.class));
        super.onDestroy();
    }

    /** Send a command (toggle, play, pause, next, prev, like) to the web player. */
    static void remote(String action) {
        remote(action, Double.NaN);
    }

    /** Same with a number (seek: position in seconds). */
    static void remote(String action, double value) {
        MainActivity activity = current.get();
        if (activity == null) return;
        String arg = Double.isNaN(value) ? "" : "," + value;
        activity.runOnUiThread(() -> activity.getBridge().getWebView().evaluateJavascript(
            "window.__forgeRemote && window.__forgeRemote('" + action.replaceAll("[^a-z]", "") + "'" + arg + ")", null));
    }

    private void pollNowPlaying() {
        try {
            WebView wv = getBridge().getWebView();
            wv.evaluateJavascript(NOW_PLAYING_JS, this::onNowPlaying);
            // Returns at once when already installed on this page (window.__forgeBlobPatch).
            if (!ForgeWeb.nativeJs.isEmpty()) wv.evaluateJavascript(ForgeWeb.nativeJs, null);
            deliverShare();
        } catch (Exception ignored) {
            // WebView not ready yet
        }
        handler.postDelayed(poll, 1000);
    }

    private void onNowPlaying(String value) {
        try {
            if (value == null || "null".equals(value)) return;
            // evaluateJavascript returns a JSON-encoded string: decode it, then parse the object.
            String json = new JSONArray("[" + value + "]").getString(0);
            JSONObject np = new JSONObject(json);
            String thumb = np.isNull("thumbnail") ? null : np.optString("thumbnail", null);
            double duration = np.isNull("duration") ? -1 : np.optDouble("duration", -1);
            PlaybackService.update(this, np.optString("title", ""), np.optString("author", ""), thumb,
                np.optBoolean("playing", false), np.optDouble("position", 0), duration, np.optBoolean("liked", false));
        } catch (Exception ignored) {
            // Page without the player (login screen, server setup)
        }
    }

    /** Sideloaded APKs get no store updates: once a day, offer the newer release. */
    private void checkUpdate() {
        SharedPreferences prefs = getSharedPreferences("forge", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("updateCheck", 0) < 24 * 3600 * 1000L) return;
        final String installed;
        try {
            installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return;
        }
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                String body;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0 && out.size() < 1_000_000; ) out.write(buf, 0, n);
                    body = out.toString(StandardCharsets.UTF_8.name());
                }
                prefs.edit().putLong("updateCheck", now).apply();
                String latest = new JSONObject(body).optString("tag_name", "").replaceFirst("^v", "");
                if (!isNewer(latest, installed) || latest.equals(prefs.getString("updateSkipped", ""))) return;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(this)
                        .setTitle("Mise à jour disponible")
                        .setMessage("Forge Audio " + latest + " est disponible (vous avez la " + installed + "). Téléchargez-la (si Chrome affiche « Ce fichier peut être dangereux », appuyez sur « Télécharger quand même »), puis ouvrez le fichier pour l'installer par-dessus : vos playlists et votre compte sont conservés.")
                        .setPositiveButton("Télécharger", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL))))
                        .setNegativeButton("Plus tard", null)
                        .setNeutralButton("Ignorer cette version", (d, w) -> prefs.edit().putString("updateSkipped", latest).apply())
                        .show();
                });
            } catch (Exception ignored) {
                // offline or API limit: retried at next launch
            }
        }).start();
    }

    /** Compares dotted versions (0.4.10 > 0.4.9). */
    static boolean isNewer(String latest, String installed) {
        if (latest == null || latest.isEmpty() || installed == null) return false;
        String[] a = latest.split("\\."), b = installed.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parse(a[i]) : 0, y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (Exception e) { return 0; }
    }
}
