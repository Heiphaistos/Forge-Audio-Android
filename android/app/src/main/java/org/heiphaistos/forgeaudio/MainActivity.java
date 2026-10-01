package org.heiphaistos.forgeaudio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import java.lang.ref.WeakReference;
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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ForgeWeb.class);
        super.onCreate(savedInstanceState);
        current = new WeakReference<>(this);
        ForgeWidget.refresh(this);

        WebView webView = getBridge().getWebView();
        ForgeWeb.chrome = new ForgeWeb.FullscreenChromeClient(getBridge());
        webView.setWebChromeClient(ForgeWeb.chrome);
        keepClearOfSystemBars((View) webView.getParent());
        // « ForgeAudioApp/<version> »: the web app shows the installed version in Paramètres (appendUserAgent adds the name only).
        String version = Updater.installedVersion(this);
        if (version != null) webView.getSettings().setUserAgentString(webView.getSettings().getUserAgentString().replace("ForgeAudioApp", "ForgeAudioApp/" + version));
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
        handler.postDelayed(() -> Updater.check(this), 8000);
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
    public void onResume() {
        super.onResume();
        Updater.resume(this);
        Updater.check(this);
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
        if (current.get() == this) current = new WeakReference<>(null);
        ForgeWidget.refresh(this);
        super.onDestroy();
    }

    /** A web player is there to receive commands. */
    static boolean isAlive() {
        return current.get() != null;
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
