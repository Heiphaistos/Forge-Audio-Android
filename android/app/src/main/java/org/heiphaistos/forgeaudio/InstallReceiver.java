package org.heiphaistos.forgeaudio;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;

/** Result of an update install session ({@link Updater}): system confirmation when Android asks, « up to date » notification after. */
public class InstallReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "updates";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            // The update replaced (and closed) the app, and Android forbids reopening it from the background: one tap reopens it.
            notifyUpdated(context);
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Mise à jour annulée" : "Mise à jour impossible" + (msg != null ? " : " + msg : ""), Toast.LENGTH_LONG).show();
        }
    }

    private static void notifyUpdated(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (open == null) return;
        PendingIntent tap = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String version = Updater.installedVersion(c);
        nm.notify(42, new NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_forge)
            .setContentTitle("Forge Audio est à jour" + (version != null ? " (" + version + ")" : ""))
            .setContentText("Touchez pour rouvrir l'application.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build());
    }
}
