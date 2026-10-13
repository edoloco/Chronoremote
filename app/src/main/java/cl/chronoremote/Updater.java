package cl.chronoremote;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Actualizaciones desde GitHub (versiones publicadas del repositorio). La misma app sirve para la TV y el teléfono.
 * Desde el teléfono se puede actualizar también la TV: allá aparece la confirmación de Android.
 */
final class Updater {

    interface Progress { void on(String msg, boolean done); }

    static final String APK = "ChronoRemote.apk", APK_LITE = "ChronoRemote-liviana.apk";

    /** Versión liviana: no trae el servicio de accesibilidad (así Play Protect deja instalarla). */
    static boolean lite(Context c) {
        try { c.getPackageManager().getServiceInfo(new android.content.ComponentName(c, TvAccess.class), 0); return false; }
        catch (Exception e) { return true; }
    }

    static String apkName(Context c) { return lite(c) ? APK_LITE : APK; }

    private Updater() { }

    static String installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    static String repo(Context c) {
        int id = c.getResources().getIdentifier("repo", "string", c.getPackageName());
        return id == 0 ? "" : c.getString(id);
    }

    static boolean newer(String a, String b) {
        String[] x = clean(a).split("\\."), y = clean(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static String clean(String v) { if (v == null) return "0"; if (v.startsWith("v")) v = v.substring(1); return v; }
    private static long num(String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0; } }

    /** {tag, url} de la versión más alta publicada. */
    static String[] latest(Context c) throws IOException {
        String repo = repo(c);
        if (repo.isEmpty()) throw new IOException("Esta versión no sabe de qué repositorio viene");
        HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + "/releases?per_page=20").openConnection();
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "ChronoRemote");
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        if (h.getResponseCode() != 200) throw new IOException("GitHub respondió " + h.getResponseCode());
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) { byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
        try {
            JSONArray rels = new JSONArray(new String(b.toByteArray(), StandardCharsets.UTF_8));
            String[] best = null;
            for (int k = 0; k < rels.length(); k++) {
                JSONObject o = rels.getJSONObject(k);
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
                JSONArray as = o.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    JSONObject a = as.getJSONObject(i);
                    if (!apkName(c).equals(a.optString("name"))) continue;
                    String tag = o.optString("tag_name");
                    if (best == null || newer(tag, best[0])) best = new String[]{tag, a.optString("browser_download_url")};
                }
            }
            if (best == null) throw new IOException("No hay versiones publicadas");
            return best;
        } catch (org.json.JSONException e) { throw new IOException("Respuesta inválida de GitHub"); }
    }

    /** Revisa, baja e instala (Android pide confirmar). */
    static void run(Context c, Progress p) {
        new Thread(() -> {
            try {
                String[] r = latest(c);
                if (!newer(r[0], installed(c))) { p.on("Ya tiene la última versión (" + clean(r[0]) + ")", true); return; }
                p.on("Descargando " + clean(r[0]) + "…", false);
                File f = download(c, r[1]);
                p.on("Confirma la actualización en la pantalla", false);
                install(c, f);
            } catch (Exception e) {
                p.on("No se pudo actualizar: " + e.getMessage(), true);
            }
        }, "actualizar").start();
    }

    /** TV: la actualización la pidió un teléfono; se le cuenta cómo va. */
    static void update(Context c, TvServer.Peer peer) {
        run(c, (msg, done) -> peer.send(TvService.msg("toast", "msg", "TV: " + msg)));
    }

    private static File download(Context c, String url) throws IOException {
        File dir = new File(c.getCacheDir(), "actualizar");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, APK);
        URL u = new URL(url);
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection h = (HttpURLConnection) u.openConnection();
            h.setInstanceFollowRedirects(false);
            h.setRequestProperty("User-Agent", "ChronoRemote");
            h.setConnectTimeout(10000);
            h.setReadTimeout(30000);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) { u = new URL(u, h.getHeaderField("Location")); continue; }
            if (code != 200) throw new IOException("la descarga respondió " + code);
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return f;
        }
        throw new IOException("demasiadas redirecciones");
    }

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
            s.commit(PendingIntent.getBroadcast(c, 77, new Intent(c, InstallReceiver.class), flags).getIntentSender());
        }
    }

    /** Respuesta del instalador: muestra la confirmación de Android o avisa si falló. */
    public static class InstallReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) return;
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (U.isTv(c)) TvService.startUi(c, confirm);
                else try { c.startActivity(confirm); } catch (Exception ignored) { }
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "No se pudo actualizar" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
                if (TvService.instance != null) TvService.instance.broadcast(TvService.msg("toast", "msg", "TV: no se pudo actualizar" + (msg != null ? " (" + msg + ")" : "")));
            }
        }
    }
}
