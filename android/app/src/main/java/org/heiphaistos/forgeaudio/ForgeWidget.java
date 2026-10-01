package org.heiphaistos.forgeaudio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Home screen widget: cover, title / artist and previous / play-pause / next, the same state as the media
 * notification. PlaybackService redraws it on every change; the system only asks when the widget is placed.
 * While the app is closed (no web player to drive), every button opens it.
 */
public class ForgeWidget extends AppWidgetProvider {

    /** Cover shown by the widget, set by PlaybackService (about 256 px). */
    static Bitmap art;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context);
    }

    /** Redraw every placed widget from PlaybackService's current state. */
    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, ForgeWidget.class));
        if (ids.length == 0) return;
        boolean live = MainActivity.isAlive();
        boolean playing = live && PlaybackService.playing;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_player);
        views.setTextViewText(R.id.widget_title, PlaybackService.title.isEmpty() ? context.getString(R.string.app_name) : PlaybackService.title);
        views.setTextViewText(R.id.widget_artist, PlaybackService.author);
        if (art != null) views.setImageViewBitmap(R.id.widget_art, art);
        else views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher);
        views.setImageViewResource(R.id.widget_toggle, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        views.setContentDescription(R.id.widget_toggle, playing ? "Pause" : "Lecture");

        Intent openIntent = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 10, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_root, open);
        views.setOnClickPendingIntent(R.id.widget_prev, live ? command(context, PlaybackService.ACTION_PREV, 11) : open);
        views.setOnClickPendingIntent(R.id.widget_toggle, live ? command(context, PlaybackService.ACTION_TOGGLE, 12) : open);
        views.setOnClickPendingIntent(R.id.widget_next, live ? command(context, PlaybackService.ACTION_NEXT, 13) : open);
        manager.updateAppWidget(ids, views);
    }

    /** A tap on a widget may start the foreground service even with the app in the background. */
    private static PendingIntent command(Context context, String action, int code) {
        Intent i = new Intent(context, PlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(context, code, i, flags) : PendingIntent.getService(context, code, i, flags);
    }
}
