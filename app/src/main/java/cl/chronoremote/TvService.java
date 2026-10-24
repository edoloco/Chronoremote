package cl.chronoremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV (central): servicio que siempre está escuchando a los teléfonos, aunque en la TV haya otra app al frente.
 * Abre apps, cambia el volumen, controla lo que se reproduce, muestra lo que mandan los teléfonos y,
 * con el permiso de accesibilidad, también Atrás, las flechas y escribir.
 */
public class TvService extends Service implements TvServer.Handler {

    static volatile TvService instance;
    private TvServer server;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private String appsSig = "";
    private JSONArray appsCache;
    /** Para la pantalla de la TV: teléfonos conectados. */
    static volatile List<String> phones = new ArrayList<>();
    static Runnable onChange;

    static void start(Context c) {
        try { c.startForegroundService(new Intent(c, TvService.class)); } catch (Exception ignored) { }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel("tv") == null)
            nm.createNotificationChannel(new NotificationChannel("tv", "Control desde el teléfono", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "tv")
                .setSmallIcon(getResources().getIdentifier("ic_stat", "drawable", getPackageName()))
                .setContentTitle("Chrono Remote")
                .setContentText("Esperando a los teléfonos")
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29)
                Service.class.getMethod("startForeground", int.class, Notification.class, int.class).invoke(this, 1, n, 16 /* CONNECTED_DEVICE */);
            else startForeground(1, n);
        } catch (Exception e) { try { startForeground(1, n); } catch (Exception ignored) { } }
        if (server == null) {
            instance = this;
            server = new TvServer(this, this);
            server.start();
        }
        if (screenOn == null) {
            // al prender la TV (salir del reposo), Chrono Remote queda a la vista
            screenOn = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { openOnPower(c); }
            };
            registerReceiver(screenOn, new android.content.IntentFilter(Intent.ACTION_SCREEN_ON));
        }
        if (i != null && i.getBooleanExtra("boot", false)) openOnPower(this);
        main.removeCallbacks(homeGuard);
        main.postDelayed(homeGuard, 1500);
        return START_STICKY;
    }

    // ---------------- Chrono como inicio de la TV ----------------
    // Google TV no deja elegir otro inicio desde sus ajustes: cuando aparece su inicio (botón Inicio del control,
    // al salir de una app…), Chrono se pone encima al tiro. Se nota con "Uso de apps" o con la accesibilidad.

    static final String[] STOCK_HOMES = {"com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher", "com.google.android.apps.tv.dreamx", "com.android.tv.launcher",
            "com.mitv.tvhome", "com.mitv.tvhome.atv", "com.tcl.tvlauncher", "com.sony.dtv.launcher", "com.hisense.tvlauncher"};
    /** Hasta cuándo se deja ver el inicio de Google TV (si se pidió a propósito). */
    static volatile long stockAllowedUntil;

    static boolean replaceHome(Context c) { return c.getSharedPreferences("tv", MODE_PRIVATE).getBoolean("replaceHome", true); }

    static boolean isStockHome(String pkg) {
        if (pkg == null) return false;
        for (String s : STOCK_HOMES) if (pkg.equals(s)) return true;
        return false;
    }

    /** Se mostró el inicio de la TV: Chrono pasa adelante (salvo que se haya pedido ver el de Google). */
    static void onForeground(Context c, String pkg) {
        if (!isStockHome(pkg) || !replaceHome(c) || TvActivity.visible || SystemClock.uptimeMillis() < stockAllowedUntil) return;
        startUi(c, new Intent(c, TvActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    static boolean usageAccess(Context c) {
        try {
            android.app.AppOpsManager ao = (android.app.AppOpsManager) c.getSystemService(APP_OPS_SERVICE);
            return ao.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    private String lastFront = "";

    /** Revisa (con "Uso de apps") qué está al frente; con la accesibilidad no hace falta (avisa ella). */
    private final Runnable homeGuard = new Runnable() {
        @Override public void run() {
            main.postDelayed(this, 700);
            if (TvAccess.instance != null || !replaceHome(TvService.this) || !usageAccess(TvService.this)) return;
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return;
            worker.execute(() -> {
                try {
                    android.app.usage.UsageStatsManager us = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                    long now = System.currentTimeMillis();
                    android.app.usage.UsageEvents ev = us.queryEvents(now - 4000, now);
                    android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
                    String front = null;
                    while (ev.hasNextEvent()) {
                        ev.getNextEvent(e);
                        if (e.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) front = e.getPackageName();
                    }
                    if (front != null) {
                        lastFront = front;
                        final String f = front;
                        main.post(() -> onForeground(TvService.this, f));
                    }
                } catch (Exception ignored) { }
            });
        }
    };

    private android.content.BroadcastReceiver screenOn;

    static boolean autoStart(Context c) { return c.getSharedPreferences("tv", MODE_PRIVATE).getBoolean("autostart", true); }

    /** Se abre la pantalla de Chrono Remote unos segundos después de prender la TV (cuando el inicio de Google ya cargó). */
    static void openOnPower(Context c) {
        if (!autoStart(c)) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!TvActivity.visible)
                startUi(c, new Intent(c, TvActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }, 3500);
    }

    @Override public void onDestroy() {
        try { if (screenOn != null) unregisterReceiver(screenOn); } catch (Exception ignored) { }
        if (server != null) server.stop();
        server = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    void broadcast(JSONObject m) { if (server != null) server.broadcast(m); }

    static JSONObject msg(String type, Object... kv) {
        JSONObject m = new JSONObject();
        try {
            m.put("t", type);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        } catch (Exception ignored) { }
        return m;
    }

    @Override public void onPeers(List<TvServer.Peer> peers) {
        List<String> names = new ArrayList<>();
        for (TvServer.Peer p : peers) names.add(p.name);
        phones = names;
        if (onChange != null) onChange.run();
    }

    // ---------------- órdenes de los teléfonos ----------------

    @Override public void onMessage(TvServer.Peer p, JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "hello":
                p.name = m.optString("name", "Teléfono");
                onPeers(server.peers);
                p.send(state());
                p.send(volume());
                TvActivity.notice("Conectado: " + p.name);
                break;
            case "apps": {
                String have = m.optString("sig");
                worker.execute(() -> {
                    JSONArray list = apps();
                    if (appsSig.equals(have)) p.send(msg("appsSame"));
                    else p.send(msg("appsList", "sig", appsSig, "list", list, "pins", pins()));
                });
                break;
            }
            case "launch": launch(this, m.optString("pkg")); break;
            case "voice": voiceSearch(this, m.optString("q"), m.optString("where")); break;
            case "uninstall": {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + m.optString("pkg"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startUi(this, i);
                break;
            }
            case "pin": {
                setPin(m.optString("pkg"), m.optBoolean("on"));
                broadcast(msg("pins", "pins", pins()));
                if (TvActivity.instance != null) TvActivity.instance.reload();
                break;
            }
            case "vol": volumeCmd(m); break;
            case "media": mediaKey(m.optString("k")); break;
            case "nav": nav(p, m.optString("k")); break;
            case "text": {
                if (TvAccess.instance == null || !TvAccess.instance.typeText(m.optString("s"), m.optBoolean("enter"))) p.send(msg("toast", "msg",
                        TvAccess.instance == null ? "Para escribir en la TV activa Chrono Remote en Accesibilidad de la TV" : "No hay dónde escribir en la TV"));
                break;
            }
            case "open": open(m); break;
            case "view": ViewerActivity.command(m); break;
            case "update": Updater.update(this, p); break;
            case "channels": worker.execute(() -> p.send(Channels.message(this))); break;
            case "tune": Channels.tune(this, m.optLong("id")); break;
            case "chan":
                worker.execute(() -> { if (!Channels.step(this, m.optInt("d"))) p.send(msg("toast", "msg", Channels.allowed(this)
                        ? "La TV no tiene canales guardados (búscalos en la app de TV de la TV)"
                        : "Permite \"Canales\" en la TV: tarjeta Prepara la TV")); });
                break;
            case "input": Channels.input(this, m.optString("id")); break;
            case "autostart":
                getSharedPreferences("tv", MODE_PRIVATE).edit().putBoolean("autostart", m.optBoolean("on")).apply();
                broadcast(state());
                break;
            case "setup": startUi(this, new Intent(this, TvActivity.class).putExtra("setup", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break;
            default: break;
        }
    }

    /** Lo que la TV puede hacer (para que el teléfono muestre o esconda botones y avise qué falta). */
    JSONObject state() {
        return msg("tv", "name", Build.MANUFACTURER + " " + Build.MODEL, "overlay", Settings.canDrawOverlays(this),
                "access", TvAccess.instance != null, "lite", Updater.lite(this), "auto", autoStart(this), "ver", Updater.installed(this));
    }

    void stateChanged() { broadcast(state()); }

    // ---------------- apps ----------------

    /** Apps que se pueden abrir en la TV, con su banner (o ícono). Se guarda una firma para no mandarlas de nuevo si no cambiaron. */
    private synchronized JSONArray apps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new HashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || found.containsKey(pkg)) continue;
                found.put(pkg, r);
            }
        }
        StringBuilder sig = new StringBuilder();
        List<String> pkgs = new ArrayList<>(found.keySet());
        Collections.sort(pkgs);
        for (String pkg : pkgs) {
            try { sig.append(pkg).append(pm.getPackageInfo(pkg, 0).lastUpdateTime).append(';'); } catch (Exception ignored) { }
        }
        String s = Integer.toHexString(sig.toString().hashCode());
        if (s.equals(appsSig) && appsCache != null) return appsCache;
        JSONArray out = new JSONArray();
        for (String pkg : pkgs) {
            ResolveInfo r = found.get(pkg);
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                o.put("label", String.valueOf(r.loadLabel(pm)));
                Drawable banner = null;
                try { banner = pm.getApplicationBanner(ai); } catch (Exception ignored) { }
                if (banner == null) try { banner = r.activityInfo.loadBanner(pm); } catch (Exception ignored) { }
                if (banner != null) o.put("banner", U.png(U.bitmap(banner, 320, 180)));
                o.put("icon", U.png(U.bitmap(r.loadIcon(pm), 128, 128)));
                o.put("sys", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                out.put(o);
            } catch (Exception ignored) { }
        }
        appsCache = out;
        appsSig = s;
        return out;
    }

    /** Lista de apps para la pantalla de la TV (sin imágenes en texto: las dibuja ella misma). */
    static List<ResolveInfo> tvApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, ResolveInfo> found = new HashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || found.containsKey(pkg)) continue;
                found.put(pkg, r);
            }
        }
        List<ResolveInfo> l = new ArrayList<>(found.values());
        Collections.sort(l, (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        return l;
    }

    // ---------------- fijadas (inicio de la TV) ----------------

    static List<String> pinList(Context c) {
        List<String> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences("tv", MODE_PRIVATE).getString("pins", "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        } catch (Exception ignored) { }
        return l;
    }

    private JSONArray pins() { return new JSONArray(pinList(this)); }

    private void setPin(String pkg, boolean on) {
        List<String> l = pinList(this);
        l.remove(pkg);
        if (on) l.add(pkg);
        getSharedPreferences("tv", MODE_PRIVATE).edit().putString("pins", new JSONArray(l).toString()).apply();
    }

    // ---------------- abrir apps (aunque en la TV haya otra app al frente) ----------------

    /**
     * Android no deja que una app escondida abra otra. Se puede si: Chrono Remote está a la vista en la TV,
     * tiene "Mostrar sobre otras apps" o tiene el permiso de accesibilidad.
     */
    static boolean canStartFromBackground(Context c) {
        return TvActivity.visible || Settings.canDrawOverlays(c) || TvAccess.instance != null;
    }

    /** Orden de voz del teléfono: buscar en YouTube o en la TV (la búsqueda de Google TV / el Asistente). */
    static void voiceSearch(Context c, String q, String where) {
        if (q == null || q.trim().isEmpty()) return;
        List<Intent> tries = new ArrayList<>();
        if ("youtube".equals(where)) {
            for (String yt : new String[]{"com.google.android.youtube.tv", "com.google.android.youtube.tvkids", "com.google.android.youtube"})
                tries.add(new Intent(Intent.ACTION_SEARCH).setPackage(yt).putExtra(android.app.SearchManager.QUERY, q).putExtra("query", q));
            tries.add(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))));
        }
        // búsqueda de la TV: la de Google TV / Android TV (con el Asistente) o, si no, la del sistema
        tries.add(new Intent("android.search.action.GLOBAL_SEARCH").putExtra(android.app.SearchManager.QUERY, q));
        tries.add(new Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.katniss").putExtra(android.app.SearchManager.QUERY, q));
        tries.add(new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q));
        PackageManager pm = c.getPackageManager();
        for (Intent i : tries) {
            if (i.resolveActivity(pm) == null) continue;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startUi(c, i);
            TvActivity.notice("🎤 " + q);
            return;
        }
        if (instance != null) instance.broadcast(msg("toast", "msg", "La TV no tiene dónde buscar eso"));
    }

    static void launch(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startUi(c, i);
    }

    /** Abre una pantalla en la TV. Si Android lo bloquea, se avisa al teléfono qué permiso falta. */
    static void startUi(Context c, Intent i) {
        Context ctx = TvAccess.instance != null ? TvAccess.instance : c;   // el servicio de accesibilidad sí puede abrir pantallas
        try { ctx.startActivity(i); } catch (Exception ignored) { }
        if (!canStartFromBackground(c) && instance != null)
            instance.broadcast(msg("toast", "msg", "Si la TV no cambió de app, falta un permiso: menú ⋯ → Preparar la TV"));
    }

    // ---------------- volumen y multimedia ----------------

    private JSONObject volume() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return msg("volNow", "f", 0);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return msg("volNow", "f", am.getStreamVolume(AudioManager.STREAM_MUSIC) / (double) max,
                "muted", am.isStreamMute(AudioManager.STREAM_MUSIC));
    }

    private void volumeCmd(JSONObject m) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (m.has("set")) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) Math.round(m.optDouble("set") * max), AudioManager.FLAG_SHOW_UI);
        } else {
            int d = m.optInt("d");
            int dir = d > 0 ? AudioManager.ADJUST_RAISE : d < 0 ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
        }
        broadcast(volume());
    }

    private void mediaKey(String k) {
        int code;
        switch (k) {
            case "play": code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "prev": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "ff": code = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; break;
            case "rw": code = KeyEvent.KEYCODE_MEDIA_REWIND; break;
            case "stop": code = KeyEvent.KEYCODE_MEDIA_STOP; break;
            default: return;
        }
        if (ViewerActivity.instance != null) { ViewerActivity.instance.mediaKey(code); return; }   // lo que mandó un teléfono
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    // ---------------- navegación ----------------

    private void nav(TvServer.Peer p, String k) {
        if ("home".equals(k)) {   // Inicio = Chrono (no el inicio de Google TV)
            stockAllowedUntil = 0;
            startUi(this, new Intent(this, TvActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return;
        }
        // en la pantalla de Chrono Remote las flechas funcionan siempre (es nuestra)
        if (TvActivity.visible && TvActivity.instance != null && TvActivity.instance.key(k)) return;
        if (ViewerActivity.instance != null && ViewerActivity.instance.key(k)) return;
        if (TvAccess.instance == null || !TvAccess.instance.nav(k))
            p.send(msg("toast", "msg", Updater.lite(this)
                    ? "En la TV está la versión liviana: Atrás y las flechas solo funcionan en Chrono Remote y en lo que mandes desde aquí"
                    : TvAccess.instance == null
                    ? "Para usar Atrás y las flechas dentro de otras apps, activa Chrono Remote en Accesibilidad de la TV (menú ⋯ → Preparar la TV)"
                    : "Esa app no deja moverse con las flechas desde el teléfono"));
    }

    // ---------------- contenido que mandan los teléfonos ----------------

    private void open(JSONObject m) {
        String kind = m.optString("kind"), url = m.optString("url");
        if ("link".equals(kind)) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // los enlaces de YouTube se abren en la app de YouTube de la TV
            String yt = youtubeId(url);
            if (yt != null) {
                Intent y = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + yt)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (y.resolveActivity(getPackageManager()) != null) i = y;
            }
            if (i.resolveActivity(getPackageManager()) == null) {
                broadcast(msg("toast", "msg", "La TV no tiene una app para abrir ese enlace"));
                return;
            }
            startUi(this, i);
            return;
        }
        startUi(this, new Intent(this, ViewerActivity.class).putExtra("url", url).putExtra("kind", kind)
                .putExtra("title", m.optString("title")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    static String youtubeId(String url) {
        try {
            Uri u = Uri.parse(url);
            String h = u.getHost() == null ? "" : u.getHost();
            if (h.endsWith("youtu.be")) return u.getLastPathSegment();
            if (h.contains("youtube.com")) {
                String v = u.getQueryParameter("v");
                if (v != null) return v;
                List<String> seg = u.getPathSegments();
                if (seg.size() >= 2 && ("shorts".equals(seg.get(0)) || "live".equals(seg.get(0)) || "embed".equals(seg.get(0)))) return seg.get(1);
            }
        } catch (Exception ignored) { }
        return null;
    }

    static boolean accessEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(new ComponentName(c, TvAccess.class).flattenToString());
    }
}
