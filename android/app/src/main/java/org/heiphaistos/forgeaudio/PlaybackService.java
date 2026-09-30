package org.heiphaistos.forgeaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media.session.MediaButtonReceiver;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground "media playback" service: keeps Forge Audio alive with the screen off or in another app,
 * shows the media notification (artwork, progress bar, previous / play-pause / next / close), receives
 * lock-screen, Bluetooth and headset buttons through a MediaSession, and pauses when headphones are unplugged.
 * Audio focus (phone call, another player) is handled by the WebView itself (Chromium AudioFocusDelegate):
 * requesting it here too makes the two fight and pauses playback as soon as it starts.
 */
public class PlaybackService extends Service {

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 42;
    private static final long IDLE_STOP_MS = 15 * 60 * 1000;

    static final String ACTION_UPDATE = "org.heiphaistos.forgeaudio.UPDATE";
    static final String ACTION_TOGGLE = "org.heiphaistos.forgeaudio.TOGGLE";
    static final String ACTION_NEXT = "org.heiphaistos.forgeaudio.NEXT";
    static final String ACTION_PREV = "org.heiphaistos.forgeaudio.PREV";
    static final String ACTION_CLOSE = "org.heiphaistos.forgeaudio.CLOSE";
    static final String ACTION_LIKE = "org.heiphaistos.forgeaudio.LIKE";

    private static boolean running = false;
    private static long lastUpdate = 0;
    private static String title = "";
    private static String author = "";
    private static String thumb = null;
    private static boolean playing = false;
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
        liked = isLiked;
        title = t;
        author = a;
        thumb = th;
        playing = p;
        if (changed) {
            position = pos;
            duration = dur;
            lastUpdate = SystemClock.elapsedRealtime();
        }
        if (!running && !p) return;
        if (!changed && running) return;
        Intent intent = new Intent(context, PlaybackService.class).setAction(ACTION_UPDATE);
        try {
            if (!running) ContextCompat.startForegroundService(context, intent);
            else context.startService(intent);
        } catch (Exception ignored) {
            // Background start restrictions: retried at the next state change.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        session = new MediaSessionCompat(this, "ForgeAudio");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { MainActivity.remote("play"); }
            @Override public void onPause() { MainActivity.remote("pause"); }
            @Override public void onSkipToNext() { MainActivity.remote("next"); }
            @Override public void onSkipToPrevious() { MainActivity.remote("prev"); }
            @Override public void onStop() { MainActivity.remote("pause"); }
            @Override public void onSeekTo(long ms) { MainActivity.remote("seek", ms / 1000.0); }
            @Override public void onCustomAction(String action, android.os.Bundle extras) { if ("like".equals(action)) MainActivity.remote("like"); }
        });
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
                byte[] data;
                try (InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        if (out.size() > 8 * 1024 * 1024) throw new java.io.IOException("artwork too large");
                    }
                    data = out.toByteArray();
                }
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
                lastKey = "";
                showNotification();
            });
        });
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
        running = false;
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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
