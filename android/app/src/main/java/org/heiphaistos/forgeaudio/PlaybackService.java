package org.heiphaistos.forgeaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.SystemClock;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;
import android.webkit.CookieManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Foreground "media playback" service: keeps Forge Audio alive with the screen off or in another app,
 * shows the media notification (artwork, progress bar, previous / play-pause / next / close), receives
 * lock-screen, Bluetooth and headset buttons through a MediaSession, and pauses when headphones are unplugged.
 * Audio focus (phone call, another player) is handled by the WebView itself (Chromium AudioFocusDelegate):
 * requesting it here too makes the two fight and pauses playback as soon as it starts.
 *
 * It is also the app's MediaBrowserService for Android Auto: the same session (now playing, controls) plus
 * a library to browse (liked tracks, playlists, recently played) read from the server with the app's session.
 */
public class PlaybackService extends MediaBrowserServiceCompat {

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 42;
    private static final long IDLE_STOP_MS = 15 * 60 * 1000;

    static final String ACTION_UPDATE = "org.heiphaistos.forgeaudio.UPDATE";
    static final String ACTION_TOGGLE = "org.heiphaistos.forgeaudio.TOGGLE";
    static final String ACTION_NEXT = "org.heiphaistos.forgeaudio.NEXT";
    static final String ACTION_PREV = "org.heiphaistos.forgeaudio.PREV";
    static final String ACTION_CLOSE = "org.heiphaistos.forgeaudio.CLOSE";
    static final String ACTION_LIKE = "org.heiphaistos.forgeaudio.LIKE";

    /** The service exists: started by the app, or only bound by Android Auto. */
    private static PlaybackService instance;
    /** Started (onStartCommand ran): survives Android Auto disconnecting. */
    private static boolean started = false;
    private static long lastUpdate = 0;
    // Read by ForgeWidget.
    static String title = "";
    static String author = "";
    private static String thumb = null;
    static boolean playing = false;
    private static double position = 0;
    private static double duration = -1;
    private static boolean liked = false;

    private MediaSessionCompat session;
    private WifiManager.WifiLock wifiLock;
    private Bitmap artwork;
    private String artworkUrl;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleStop = this::stopSelf;
    private String lastKey = "";
    private boolean noisyRegistered = false;

    /** Headphones unplugged or Bluetooth disconnected: pause instead of playing through the speaker. */
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) MainActivity.remote("pause");
        }
    };


    /** Called by MainActivity with the web player's state. Starts the service on first playback. */
    static void update(Context context, String t, String a, String th, boolean p, double pos, double dur, boolean isLiked) {
        // Position far from where the progress bar extrapolates it = the user seeked in the app.
        double expected = position + (playing ? (SystemClock.elapsedRealtime() - lastUpdate) / 1000.0 : 0);
        boolean changed = !t.equals(title) || !a.equals(author) || p != playing || (th == null ? thumb != null : !th.equals(thumb))
            || Math.abs(pos - expected) > 2 || Math.abs(dur - duration) > 1 || isLiked != liked;
        if (th == null ? thumb != null : !th.equals(thumb)) ForgeWidget.art = null;
        liked = isLiked;
        title = t;
        author = a;
        thumb = th;
        playing = p;
        if (changed) {
            position = pos;
            duration = dur;
            lastUpdate = SystemClock.elapsedRealtime();
            ForgeWidget.refresh(context);
        }
        if (instance == null && !p) return;
        if (!changed && instance != null) return;
        // Bound by Android Auto: refresh its session right away, whether or not the start below is allowed.
        if (instance != null) instance.showNotification();
        if (started) return;
        Intent intent = new Intent(context, PlaybackService.class).setAction(ACTION_UPDATE);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception ignored) {
            // Background start restrictions: retried at the next state change.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        session = new MediaSessionCompat(this, "ForgeAudio");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { command("play", Double.NaN); }
            @Override public void onPause() { command("pause", Double.NaN); }
            @Override public void onSkipToNext() { command("next", Double.NaN); }
            @Override public void onSkipToPrevious() { command("prev", Double.NaN); }
            @Override public void onStop() { command("pause", Double.NaN); }
            @Override public void onSeekTo(long ms) { command("seek", ms / 1000.0); }
            @Override public void onCustomAction(String action, Bundle extras) { if ("like".equals(action)) command("like", Double.NaN); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) { playFromMediaId(mediaId); }
        });
        setSessionToken(session.getSessionToken());
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        session.setActive(true);
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ForgeAudio:stream");
            wifiLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always enter the foreground first: Android kills services that don't within a few seconds.
        started = true;
        showNotification();
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
            MediaButtonReceiver.handleIntent(session, intent);
        } else if (ACTION_TOGGLE.equals(action)) {
            MainActivity.remote("toggle");
        } else if (ACTION_NEXT.equals(action)) {
            MainActivity.remote("next");
        } else if (ACTION_PREV.equals(action)) {
            MainActivity.remote("prev");
        } else if (ACTION_LIKE.equals(action)) {
            MainActivity.remote("like");
        } else if (ACTION_CLOSE.equals(action)) {
            MainActivity.remote("pause");
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Lecture en cours", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Contrôles de lecture de Forge Audio");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private PendingIntent action(String name, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(name);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void showNotification() {
        String key = title + "|" + author + "|" + playing + "|" + liked + "|" + (artwork != null);
        boolean fresh = !key.equals(lastKey);
        lastKey = key;

        session.setMetadata(new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration > 0 ? (long) (duration * 1000) : -1)
            .build());
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_STOP
                | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | (duration > 0 ? PlaybackStateCompat.ACTION_SEEK_TO : 0))
            .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                duration > 0 ? (long) (position * 1000) : PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f, lastUpdate)
            .addCustomAction(new PlaybackStateCompat.CustomAction.Builder("like", liked ? "Retirer des titres likés" : "J'aime",
                liked ? R.drawable.ic_liked : R.drawable.ic_like).build())
            .build());
        syncNoisyReceiver();

        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_forge)
            .setContentTitle(title.isEmpty() ? "Forge Audio" : title)
            .setContentText(author)
            .setLargeIcon(artwork)
            .setContentIntent(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setDeleteIntent(action(ACTION_CLOSE, 5))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "Précédent", action(ACTION_PREV, 2))
            .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Lecture", action(ACTION_TOGGLE, 3))
            .addAction(android.R.drawable.ic_media_next, "Suivant", action(ACTION_NEXT, 4))
            .addAction(liked ? R.drawable.ic_liked : R.drawable.ic_like, liked ? "Retirer des titres likés" : "J'aime", action(ACTION_LIKE, 6))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fermer", action(ACTION_CLOSE, 5))
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .build();

        int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
        } catch (Exception e) {
            // Not allowed from the background right now: just refresh the notification.
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
        }

        if (wifiLock != null) {
            if (playing && !wifiLock.isHeld()) wifiLock.acquire();
            if (!playing && wifiLock.isHeld()) wifiLock.release();
        }
        // Paused for a long time: let Android reclaim the app.
        handler.removeCallbacks(idleStop);
        if (!playing) handler.postDelayed(idleStop, IDLE_STOP_MS);

        if (fresh) loadArtwork();
    }

    private void loadArtwork() {
        final String url = thumb;
        if (url == null || url.equals(artworkUrl)) return;
        artworkUrl = url;
        io.execute(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                byte[] data = readAll(c, 8 * 1024 * 1024);
                // Decode at about 512 px: a full-size cover in a notification wastes memory and can be refused.
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                o.inSampleSize = 1;
                while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= 512) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (Exception ignored) {
                // no artwork
            }
            final Bitmap result = bmp;
            handler.post(() -> {
                if (!url.equals(artworkUrl)) return;
                artwork = result;
                ForgeWidget.art = result == null ? null : Bitmap.createScaledBitmap(result, 256, 256 * result.getHeight() / Math.max(1, result.getWidth()), true);
                lastKey = "";
                showNotification();
                ForgeWidget.refresh(this);
            });
        });
    }

    private static byte[] readAll(HttpURLConnection c, int max) throws IOException {
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IOException("response too large");
            }
            return out.toByteArray();
        }
    }

    // ---------- Android Auto ----------

    private static final String ROOT = "root", LIKED = "liked", PLAYLISTS = "playlists", RECENT = "recent", PLAYLIST = "pl:";
    /**
     * Apps allowed to browse the library (titles of the user's tracks and playlists): Android Auto, this app and
     * the system. Any other app only gets an empty root, the controls stay available through the session.
     * ponytail: package names only, not signatures; check signatures (UAMP PackageValidator) if the library becomes sensitive.
     */
    private static final Set<String> BROWSERS = new HashSet<>(Arrays.asList(
        "com.google.android.projection.gearhead", "com.google.android.carassistant", "com.google.android.autosimulator"));
    private static final long LIBRARY_TTL_MS = 30 * 1000;
    private JSONObject library;
    private long libraryAt;

    /** Session command: with the app closed (Android Auto, headset), open it first; the command runs once the player is ready. */
    private void command(String action, double value) {
        if (MainActivity.isAlive()) MainActivity.remote(action, value);
        else openPlayer(MainActivity.remoteJs(action, value));
    }

    /**
     * The music is played by the app's web page: open it. Android may refuse to open an activity from the
     * background (phone locked in the car): Android Auto then shows the message instead.
     */
    private void openPlayer(String js) {
        MainActivity.openAndRun(this, js);
        if (MainActivity.isAlive()) return;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
            .setState(PlaybackStateCompat.STATE_ERROR, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
            .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, "Ouvrez Forge Audio sur le téléphone pour lancer la lecture")
            .build());
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        // No "resume" card from System UI: playing needs the app's web player, not this service.
        if (rootHints != null && rootHints.getBoolean(BrowserRoot.EXTRA_RECENT)) return null;
        boolean allowed = clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID || BROWSERS.contains(clientPackageName);
        return new BrowserRoot(allowed ? ROOT : "", null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaItem>> result) {
        if (parentId.isEmpty()) {
            result.sendResult(new ArrayList<>());
            return;
        }
        if (ROOT.equals(parentId)) {
            List<MediaItem> items = new ArrayList<>();
            items.add(folder(LIKED, "Titres likés", null));
            items.add(folder(PLAYLISTS, "Playlists", null));
            items.add(folder(RECENT, "Écoutés récemment", null));
            result.sendResult(items);
            return;
        }
        result.detach();
        String cookie = cookie();
        io.execute(() -> {
            List<MediaItem> items = new ArrayList<>();
            try {
                JSONObject lib = library(cookie);
                if (PLAYLISTS.equals(parentId)) {
                    JSONArray lists = lib.optJSONArray("playlists");
                    for (int i = 0; lists != null && i < lists.length(); i++) {
                        JSONObject p = lists.getJSONObject(i);
                        JSONArray t = p.optJSONArray("tracks");
                        int n = t == null ? 0 : t.length();
                        items.add(folder(PLAYLIST + p.getString("id"), p.optString("name"), n + (n > 1 ? " titres" : " titre")));
                    }
                } else {
                    JSONArray tracks = tracks(lib, parentId);
                    for (int i = 0; i < tracks.length(); i++) {
                        JSONObject t = tracks.getJSONObject(i);
                        MediaDescriptionCompat d = new MediaDescriptionCompat.Builder()
                            .setMediaId(parentId + "|" + i).setTitle(t.optString("title")).setSubtitle(t.optString("author")).build();
                        items.add(new MediaItem(d, MediaItem.FLAG_PLAYABLE));
                    }
                }
            } catch (Exception ignored) {
                // Server unreachable or not signed in: empty list.
            }
            final List<MediaItem> done = items;
            handler.post(() -> result.sendResult(done));
        });
    }

    private static MediaItem folder(String id, String title, String subtitle) {
        return new MediaItem(new MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(), MediaItem.FLAG_BROWSABLE);
    }

    /** Play a track chosen in Android Auto, followed by the rest of its list. */
    private void playFromMediaId(String mediaId) {
        int bar = mediaId == null ? -1 : mediaId.lastIndexOf('|');
        if (bar < 0) return;
        String list = mediaId.substring(0, bar);
        String cookie = cookie();
        io.execute(() -> {
            try {
                int index = Integer.parseInt(mediaId.substring(bar + 1));
                JSONArray all = tracks(library(cookie), list);
                if (index < 0 || index >= all.length()) return;
                // ponytail: the chosen track and the next 300; send the whole list with its start if Previous must go further back.
                JSONArray queue = new JSONArray();
                for (int i = index; i < Math.min(all.length(), index + 300); i++) queue.put(all.get(i));
                String js = "(function(t){if(window.__forgePlayTracks)window.__forgePlayTracks(t,0);"
                    + "else if(window.__forgeOpenLink)window.__forgeOpenLink(t[0].url)})(" + queue + ")";
                handler.post(() -> {
                    if (MainActivity.isAlive()) MainActivity.run(js);
                    else openPlayer(js);
                });
            } catch (Exception ignored) {
                // unknown id or server unreachable
            }
        });
    }

    /** Tracks of a browsable list: liked, recently played (without repeats) or a playlist ("pl:<id>"). */
    private static JSONArray tracks(JSONObject lib, String list) throws Exception {
        if (LIKED.equals(list)) return lib.optJSONArray("liked") != null ? lib.getJSONArray("liked") : new JSONArray();
        JSONArray out = new JSONArray();
        if (RECENT.equals(list)) {
            JSONArray history = lib.optJSONArray("history");
            Set<String> seen = new HashSet<>();
            for (int i = 0; history != null && i < history.length() && out.length() < 50; i++) {
                JSONObject t = history.getJSONObject(i).optJSONObject("track");
                if (t != null && seen.add(t.optString("url"))) out.put(t);
            }
        } else if (list.startsWith(PLAYLIST)) {
            JSONArray lists = lib.optJSONArray("playlists");
            for (int i = 0; lists != null && i < lists.length(); i++) {
                JSONObject p = lists.getJSONObject(i);
                if (list.substring(PLAYLIST.length()).equals(p.optString("id"))) return p.optJSONArray("tracks") != null ? p.getJSONArray("tracks") : out;
            }
        }
        return out;
    }

    /** Session cookie of the server page (read on the main thread: the WebView provider may not be loaded yet). */
    private String cookie() {
        String server = server(this);
        try {
            return server == null ? null : CookieManager.getInstance().getCookie(server);
        } catch (Exception e) {
            return null;
        }
    }

    static String server(Context context) {
        return context.getSharedPreferences("forge", Context.MODE_PRIVATE).getString("server", null);
    }

    /** The user's library from the server (GET /api/me/data), kept 30 s. Runs on the io thread. */
    private JSONObject library(String cookie) throws Exception {
        if (library != null && SystemClock.elapsedRealtime() - libraryAt < LIBRARY_TTL_MS) return library;
        String server = server(this);
        if (server == null || cookie == null) return new JSONObject();
        HttpURLConnection c = (HttpURLConnection) new URL(server + "/api/me/data").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Cookie", cookie);
        if (c.getResponseCode() != 200) return new JSONObject();
        JSONObject data = new JSONObject(new String(readAll(c, 20 * 1024 * 1024), "UTF-8")).optJSONObject("data");
        JSONObject lib = data == null ? null : data.optJSONObject("library");
        library = lib == null ? new JSONObject() : lib;
        libraryAt = SystemClock.elapsedRealtime();
        return library;
    }

    /** Listen for unplugged headphones only while playing. */
    private void syncNoisyReceiver() {
        if (playing && !noisyRegistered) {
            registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        } else if (!playing && noisyRegistered) {
            unregisterReceiver(noisy);
            noisyRegistered = false;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App swiped away from recents: the web player is gone with it.
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        started = false;
        playing = false;
        ForgeWidget.refresh(this);
        handler.removeCallbacks(idleStop);
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (noisyRegistered) unregisterReceiver(noisy);
        noisyRegistered = false;
        session.setActive(false);
        session.release();
        io.shutdownNow();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
