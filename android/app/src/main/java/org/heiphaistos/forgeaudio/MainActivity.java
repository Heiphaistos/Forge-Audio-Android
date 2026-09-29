package org.heiphaistos.forgeaudio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;
import java.lang.ref.WeakReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hosts the Forge Audio web app and keeps it playing in the background.
 *
 * The web app exposes window.__forgeNowPlaying() (current track) and window.__forgeRemote(action)
 * (play / pause / next / prev). This activity polls the first to drive the media notification
 * ({@link PlaybackService}) and forwards notification / headset buttons to the second.
 */
public class MainActivity extends BridgeActivity {

    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollNowPlaying;

    private static final String NOW_PLAYING_JS =
        "(function(){try{return window.__forgeNowPlaying?JSON.stringify(window.__forgeNowPlaying()):null}catch(e){return null}})()";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new WeakReference<>(this);

        WebView webView = getBridge().getWebView();
        // The player starts tracks by itself (next track, radio): no user gesture needed.
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }

        // Back: navigate inside the app, and when there is nothing to go back to, go to the home screen
        // instead of closing (closing the activity would stop the music).
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView wv = getBridge().getWebView();
                if (wv.canGoBack()) wv.goBack();
                else moveTaskToBack(true);
            }
        });

        handler.post(poll);
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
        handler.removeCallbacks(poll);
        stopService(new Intent(this, PlaybackService.class));
        super.onDestroy();
    }

    /** Send a command (toggle, play, pause, next, prev) to the web player. */
    static void remote(String action) {
        MainActivity activity = current.get();
        if (activity == null) return;
        activity.runOnUiThread(() -> activity.getBridge().getWebView().evaluateJavascript(
            "window.__forgeRemote && window.__forgeRemote('" + action.replaceAll("[^a-z]", "") + "')", null));
    }

    private void pollNowPlaying() {
        try {
            getBridge().getWebView().evaluateJavascript(NOW_PLAYING_JS, this::onNowPlaying);
        } catch (Exception ignored) {
            // WebView not ready yet
        }
        handler.postDelayed(poll, 1500);
    }

    private void onNowPlaying(String value) {
        try {
            if (value == null || "null".equals(value)) return;
            // evaluateJavascript returns a JSON-encoded string: decode it, then parse the object.
            String json = new JSONArray("[" + value + "]").getString(0);
            JSONObject np = new JSONObject(json);
            String title = np.optString("title", "");
            String author = np.optString("author", "");
            String thumb = np.isNull("thumbnail") ? null : np.optString("thumbnail", null);
            boolean playing = np.optBoolean("playing", false);
            PlaybackService.update(this, title, author, thumb, playing);
        } catch (Exception ignored) {
            // Page without the player (login screen, server setup)
        }
    }
}
