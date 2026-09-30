package org.heiphaistos.forgeaudio;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONObject;

/**
 * Built-in updates for the sideloaded APK (no store): the newer release is downloaded in the background,
 * checked (size, SHA-256, package, version, signing key), then installed through the system installer.
 *
 * The mirror publishes {@code ForgeAudio-android.json} = { version, size, sha256 } next to the APK, so a version
 * is only offered once the mirror serves it. The first update asks once to allow « installer des applications
 * inconnues » for Forge Audio and shows the system confirmation; from Android 12 the app then updates
 * itself without confirmation (it is its own installer of record), unless the phone's system asks anyway.
 */
final class Updater {
    static final String BASE = "https://forgeaudio.heiphaistos.org/";
    private static final long EVERY_MS = 6 * 3600 * 1000L;
    private static boolean busy;
    /** An APK ready to install, waiting for the « unknown apps » permission. */
    private static File pending;
    /** The update dialog is on screen (coming back to the app must not stack a second one). */
    private static boolean offering;

    private Updater() {}

    /** At launch and when the app comes back: at most every 6 hours. */
    static void check(Activity a) {
        SharedPreferences prefs = a.getSharedPreferences("forge", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (busy || offering || pending != null || now - prefs.getLong("updateCheck", 0) < EVERY_MS) return;
        final String installed = installedVersion(a);
        if (installed == null) return;
        busy = true;
        Context app = a.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject info = new JSONObject(readText(BASE + "ForgeAudio-android.json"));
                prefs.edit().putLong("updateCheck", now).apply();
                String latest = info.optString("version", "");
                cleanOld(app, installed);
                if (!MainActivity.isNewer(latest, installed) || latest.equals(prefs.getString("updateSkipped", ""))) return;
                File apk = download(app, latest, info.optLong("size", -1), info.optString("sha256", ""));
                if (!verified(app, apk, latest)) { apk.delete(); return; }
                a.runOnUiThread(() -> offer(a, apk, latest, installed));
            } catch (Exception ignored) {
                // offline, mirror down: retried at the next check
            } finally {
                busy = false;
            }
        }).start();
    }

    /** Coming back from the « unknown apps » settings screen. */
    static void resume(Activity a) {
        if (pending == null || Build.VERSION.SDK_INT < 26) return;
        if (!a.getPackageManager().canRequestPackageInstalls()) return;
        File apk = pending;
        pending = null;
        install(a, apk);
    }

    private static void offer(Activity a, File apk, String latest, String installed) {
        if (a.isFinishing() || a.isDestroyed() || offering) return;
        offering = true;
        new AlertDialog.Builder(a)
            .setOnDismissListener(d -> offering = false)
            .setTitle("Mise à jour prête")
            .setMessage("Forge Audio " + latest + " est téléchargée (vous avez la " + installed + "). L'installation redémarre l'application : "
                + "la lecture en cours s'arrête, vos playlists et votre compte sont conservés.")
            .setPositiveButton("Installer", (d, w) -> install(a, apk))
            .setNegativeButton("Plus tard", null)
            .setNeutralButton("Ignorer cette version", (d, w) -> a.getSharedPreferences("forge", Context.MODE_PRIVATE).edit().putString("updateSkipped", latest).apply())
            .show();
    }

    private static void install(Activity a, File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            pending = apk;
            new AlertDialog.Builder(a)
                .setTitle("Autoriser les mises à jour")
                .setMessage("Pour s'installer, la mise à jour a besoin d'une autorisation, demandée une seule fois : activez « Autoriser cette source » pour Forge Audio, puis revenez.")
                .setPositiveButton("Ouvrir les réglages", (d, w) -> a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))))
                .setNegativeButton("Plus tard", (d, w) -> pending = null)
                .show();
            return;
        }
        try {
            PackageInstaller installer = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(a.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    copy(in, out);
                    session.fsync(out);
                }
                Intent done = new Intent(a, InstallReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                session.commit(PendingIntent.getBroadcast(a, id, done, flags).getIntentSender());
            }
            Toast.makeText(a, "Installation de la mise à jour…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(a, "Mise à jour impossible : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static File download(Context c, String version, long size, String sha256) throws Exception {
        File dir = new File(c.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cache");
        File apk = new File(dir, "ForgeAudio-" + version + ".apk");
        if (apk.isFile() && apk.length() == size && sha256.equalsIgnoreCase(sha256Of(apk))) return apk;
        File part = new File(dir, apk.getName() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "ForgeAudio-android.apk").openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            copy(in, out);
        } finally {
            conn.disconnect();
        }
        if ((size > 0 && part.length() != size) || sha256.isEmpty() || !sha256.equalsIgnoreCase(sha256Of(part))) {
            part.delete();
            throw new SecurityException("APK corrompu ou remplacé entre-temps");
        }
        if (!part.renameTo(apk)) throw new IllegalStateException("rename");
        return apk;
    }

    /** Same package, the announced version and the same signing key as the installed app. */
    private static boolean verified(Context c, File apk, String version) {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (archive == null || !c.getPackageName().equals(archive.packageName) || !version.equals(archive.versionName)) return false;
        try {
            return Arrays.equals(signers(pm.getPackageInfo(c.getPackageName(), flags)), signers(archive));
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo p) {
        if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) return p.signingInfo.getApkContentsSigners();
        return p.signatures;
    }

    /** Remove downloaded APKs that are installed now (or older), and partial downloads. */
    private static void cleanOld(Context c, String installed) {
        File[] files = new File(c.getCacheDir(), "updates").listFiles();
        if (files == null) return;
        for (File f : files) {
            String v = f.getName().replaceFirst("^ForgeAudio-", "").replaceFirst("\\.apk(\\.part)?$", "");
            if (f.getName().endsWith(".part") || !MainActivity.isNewer(v, installed)) f.delete();
        }
    }

    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readText(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Cache-Control", "no-cache");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0 && out.size() < 65536; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static String sha256Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(hex[(b >> 4) & 0xf]).append(hex[b & 0xf]);
        return sb.toString();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }
}
