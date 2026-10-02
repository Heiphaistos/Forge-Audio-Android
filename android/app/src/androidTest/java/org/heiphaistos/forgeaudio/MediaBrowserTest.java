package org.heiphaistos.forgeaudio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.session.MediaControllerCompat;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Browses PlaybackService the way Android Auto does (connected test: ./gradlew connectedDebugAndroidTest).
 * The root never needs the server; the lists below it are logged ("ForgeAuto") and filled only when the app
 * is signed in to a server.
 */
@RunWith(AndroidJUnit4.class)
public class MediaBrowserTest {

    private MediaBrowserCompat browser;

    @Test
    public void browsesTheLibrary() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch connected = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            browser = new MediaBrowserCompat(context, new ComponentName(context, PlaybackService.class),
                new MediaBrowserCompat.ConnectionCallback() {
                    @Override public void onConnected() { connected.countDown(); }
                }, null);
            browser.connect();
        });
        assertTrue("connected", connected.await(10, TimeUnit.SECONDS));
        assertEquals("root", browser.getRoot());

        List<MediaItem> root = children("root");
        assertEquals(3, root.size());
        for (MediaItem item : root) {
            assertTrue(item.isBrowsable());
            List<MediaItem> list = children(item.getMediaId());
            Log.i("ForgeAuto", item.getMediaId() + " (" + item.getDescription().getTitle() + "): " + list.size());
            for (MediaItem child : list) {
                Log.i("ForgeAuto", "  " + child.getMediaId() + " | " + child.getDescription().getTitle() + " | " + child.getDescription().getSubtitle()
                    + (child.isPlayable() ? " [playable]" : "") + (child.isBrowsable() ? " [browsable]" : ""));
                if (child.isBrowsable()) {
                    for (MediaItem t : children(child.getMediaId())) Log.i("ForgeAuto", "    " + t.getMediaId() + " | " + t.getDescription().getTitle());
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(browser::disconnect);
    }

    /**
     * Android Auto picks a track with the app closed: the app opens, plays it, then answers the controls.
     * Needs the app signed in to a server whose library has the playlist "pl:autotest-pl" (2nd track: One More Time).
     */
    @Test
    public void playsFromAutoThenTakesControls() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch connected = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            browser = new MediaBrowserCompat(context, new ComponentName(context, PlaybackService.class),
                new MediaBrowserCompat.ConnectionCallback() {
                    @Override public void onConnected() { connected.countDown(); }
                }, null);
            browser.connect();
        });
        assertTrue("connected", connected.await(10, TimeUnit.SECONDS));
        assertTrue("app closed at start", !MainActivity.isAlive());
        children("pl:autotest-pl");
        MediaControllerCompat controller = new MediaControllerCompat(context, browser.getSessionToken());

        controller.getTransportControls().playFromMediaId("pl:autotest-pl|1", null);
        assertTrue("playing One More Time", waitUntil(() -> PlaybackService.playing && PlaybackService.title.contains("One More Time"), 90));
        Log.i("ForgeAuto", "now playing: " + PlaybackService.title + " / state " + controller.getPlaybackState().getState());

        controller.getTransportControls().pause();
        assertTrue("paused", waitUntil(() -> !PlaybackService.playing, 10));
        Log.i("ForgeAuto", "after pause: state " + controller.getPlaybackState().getState());
        controller.getTransportControls().play();
        assertTrue("playing again", waitUntil(() -> PlaybackService.playing, 10));
        // The rest of the playlist follows only with a web app exposing window.__forgePlayTracks
        // (otherwise __forgeOpenLink plays the chosen track alone): logged, not asserted.
        controller.getTransportControls().skipToNext();
        waitUntil(() -> PlaybackService.title.contains("Get Lucky"), 60);
        Log.i("ForgeAuto", "after next: " + PlaybackService.title + " / metadata " + controller.getMetadata().getDescription().getTitle());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(browser::disconnect);
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier ok, int seconds) throws InterruptedException {
        for (int i = 0; i < seconds * 4; i++) {
            if (ok.getAsBoolean()) return true;
            Thread.sleep(250);
        }
        return false;
    }

    private List<MediaItem> children(String id) throws InterruptedException {
        List<MediaItem> out = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> browser.subscribe(id, new MediaBrowserCompat.SubscriptionCallback() {
            @Override public void onChildrenLoaded(String parentId, List<MediaItem> children) {
                out.addAll(children);
                browser.unsubscribe(parentId);
                done.countDown();
            }
        }));
        assertTrue("children of " + id, done.await(30, TimeUnit.SECONDS));
        return out;
    }
}
