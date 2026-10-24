package cl.chronoremote;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pantalla de la TV al estilo ChronOS: barra con el reloj, apps fijadas y todas las apps en tarjetas grandes.
 * Se maneja con el control de la TV o con los teléfonos.
 */
public class TvActivity extends Activity {

    static volatile TvActivity instance;
    static volatile boolean visible;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView clock, date, phonesChip, notice;
    private LinearLayout content, setupCard;

    private int dp(float v) { return U.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        instance = this;
        TvService.start(this);
        // como ChronOS: fondo naranjo, carpeta verde arriba (reloj a la derecha) y abajo (logo a la izquierda)
        FrameLayout root = new FrameLayout(this);
        root.setBackground(FolderBar.orange());

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(48), dp(104), dp(48), dp(110));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        sv.addView(content);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        FolderBar topBar = new FolderBar(this, true, true, 26, 94, 330);
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setGravity(Gravity.END);
        clock = U.text(this, "", 44, U.FG, true);
        clock.setShadowLayer(dp(4), 0, dp(1), 0x55000000);
        date = U.text(this, "", 16, 0xEEFFFFFF, false);
        tcol.addView(clock);
        tcol.addView(date);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tl.rightMargin = dp(40);
        tl.topMargin = dp(6);
        topBar.addView(tcol, tl);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, dp(110), Gravity.TOP));

        FolderBar bottomBar = new FolderBar(this, false, false, 22, 78, 300);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("chronos", "drawable", getPackageName()));
        brand.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView name = U.text(this, "Chrono", 30, U.FG, true);
        name.setPadding(dp(12), 0, 0, 0);
        name.setShadowLayer(dp(4), 0, dp(1), 0x55000000);
        brand.addView(name);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, dp(78), Gravity.BOTTOM | Gravity.START);
        bl.leftMargin = dp(36);
        bottomBar.addView(brand, bl);
        phonesChip = U.text(this, "", 15, U.FG, false);
        phonesChip.setPadding(dp(16), dp(4), dp(16), dp(4));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, dp(22), Gravity.BOTTOM | Gravity.END);
        pl.rightMargin = dp(36);
        bottomBar.addView(phonesChip, pl);
        root.addView(bottomBar, new FrameLayout.LayoutParams(-1, dp(96), Gravity.BOTTOM));

        notice = U.text(this, "", 18, U.FG, true);
        notice.setPadding(dp(22), dp(12), dp(22), dp(12));
        notice.setBackground(U.round(0xEE0B2A27, dp(26)));
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        nl.bottomMargin = dp(36);
        root.addView(notice, nl);

        setContentView(root);
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        TvService.onChange = () -> h.post(this::updatePhones);
        updatePhones();
        tick.run();
        reload();   // pudo cambiar el permiso o instalarse una app
        if (TvService.instance != null) TvService.instance.stateChanged();
        if (!Channels.allowed(this) && !getSharedPreferences("tv", MODE_PRIVATE).getBoolean("askedEpg", false)) {
            getSharedPreferences("tv", MODE_PRIVATE).edit().putBoolean("askedEpg", true).apply();
            requestPermissions(new String[]{Channels.PERM_TV}, 5);
        }
        if (getIntent().getBooleanExtra("setup", false)) { getIntent().removeExtra("setup"); if (setupCard != null) setupCard.requestFocus(); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) { reload(); }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); }

    /** Es el inicio: Atrás no lo cierra. */
    @Override public void onBackPressed() { }

    @Override protected void onPause() {
        visible = false;
        h.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            clock.setText(new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(TvActivity.this) ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
            String d = new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es")).format(now);
            date.setText(d.substring(0, 1).toUpperCase() + d.substring(1));
            h.postDelayed(this, 15000);
        }
    };

    private void updatePhones() {
        List<String> p = TvService.phones;
        phonesChip.setText(p.isEmpty() ? "📱  Sin teléfonos" : "📱  " + (p.size() == 1 ? p.get(0) : p.size() + " teléfonos"));
    }

    /** Aviso corto abajo (un teléfono se conectó, etc.). */
    static void notice(String s) {
        TvActivity a = instance;
        if (a == null) return;
        a.h.post(() -> {
            a.notice.setText(s);
            a.notice.setVisibility(View.VISIBLE);
            a.notice.setAlpha(0f);
            a.notice.animate().alpha(1f).setDuration(200).start();
            a.h.removeCallbacks(a.hideNotice);
            a.h.postDelayed(a.hideNotice, 3500);
        });
    }

    private final Runnable hideNotice = () -> notice.animate().alpha(0f).setDuration(300).withEndAction(() -> notice.setVisibility(View.GONE)).start();

    // ---------------- contenido ----------------

    void reload() {
        h.post(() -> {
            View f = getCurrentFocus();
            String keep = f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
            content.removeAllViews();
            setupCard = null;
            boolean homeOk = TvAccess.instance != null || TvService.usageAccess(this) || !TvService.replaceHome(this);
            if (!android.provider.Settings.canDrawOverlays(this) || !Channels.allowed(this) || !homeOk || (!Updater.lite(this) && TvAccess.instance == null)) content.addView(setup());
            List<ResolveInfo> all = TvService.tvApps(this);
            List<String> pins = TvService.pinList(this);
            List<ResolveInfo> pinned = new ArrayList<>();
            for (String p : pins) for (ResolveInfo r : all) if (r.activityInfo.packageName.equals(p)) pinned.add(r);
            if (!pinned.isEmpty()) {
                content.addView(title("Fijadas"));
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                hs.setClipToPadding(false);
                LinearLayout row = new LinearLayout(this);
                row.setPadding(0, dp(10), 0, dp(14));
                for (ResolveInfo r : pinned) row.addView(card(r, true));
                hs.addView(row);
                content.addView(hs);
            }
            content.addView(title("Todas las apps"));
            int cols = 5;
            LinearLayout row = null;
            for (int i = 0; i < all.size(); i++) {
                if (i % cols == 0) {
                    row = new LinearLayout(this);
                    row.setPadding(0, dp(10), 0, dp(8));
                    content.addView(row);
                }
                row.addView(card(all.get(i), false));
            }
            content.addView(title("Ajustes"));
            LinearLayout opts = new LinearLayout(this);
            opts.setPadding(0, dp(10), 0, 0);
            opts.addView(button("Abrir al prender la TV: " + (TvService.autoStart(this) ? "sí" : "no"), () -> {
                getSharedPreferences("tv", MODE_PRIVATE).edit().putBoolean("autostart", !TvService.autoStart(this)).apply();
                if (TvService.instance != null) TvService.instance.stateChanged();
                reload();
            }));
            opts.addView(button("Buscar actualización (" + Updater.installed(this) + ")", () -> { notice("Buscando actualización…"); Updater.update(this, null); }));
            opts.addView(button("Chrono como inicio de la TV: " + (TvService.replaceHome(this) ? "sí" : "no"), () -> {
                getSharedPreferences("tv", MODE_PRIVATE).edit().putBoolean("replaceHome", !TvService.replaceHome(this)).apply();
                reload();
            }));
            opts.addView(button("Abrir el inicio de Google TV", () -> {
                TvService.stockAllowedUntil = android.os.SystemClock.uptimeMillis() + 120_000;   // 2 minutos sin taparlo
                for (String p : TvService.STOCK_HOMES) {
                    Intent li = getPackageManager().getLaunchIntentForPackage(p);
                    if (li == null) li = getPackageManager().getLeanbackLaunchIntentForPackage(p);
                    if (li != null) { try { startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Exception ignored) { } }
                }
                notice("No encontré el inicio de Google TV");
            }));
            content.addView(opts);
            if (keep != null) {
                View v = content.findViewWithTag(keep);
                if (v != null) v.requestFocus();
            } else if (content.getChildCount() > 0) content.post(() -> {
                View first = content.findFocus();
                if (first == null) content.requestFocus(View.FOCUS_DOWN);
            });
        });
    }

    private TextView title(String s) {
        TextView t = U.text(this, s, 21, U.FG, true);
        t.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
        t.setPadding(dp(4), dp(18), 0, 0);
        return t;
    }

    /** Tarjeta de una app: banner 16:9 (o el ícono sobre color), con el nombre. Al enfocarse, crece y brilla. */
    private View card(ResolveInfo r, boolean pinned) {
        PackageManager pm = getPackageManager();
        String pkg = r.activityInfo.packageName;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);
        box.setClickable(true);
        box.setTag((pinned ? "p:" : "a:") + pkg);
        FrameLayout art = new FrameLayout(this);
        art.setClipToOutline(true);
        GradientDrawable bg = U.round(0x40FFFFFF, dp(16));   // tarjeta de vidrio claro sobre el naranjo
        bg.setStroke(dp(1), 0x66FFFFFF);
        art.setBackground(bg);
        Drawable banner = null;
        try { banner = r.activityInfo.loadBanner(pm); } catch (Exception ignored) { }
        if (banner == null) try { banner = pm.getApplicationBanner(r.activityInfo.packageName); } catch (Exception ignored) { }
        ImageView iv = new ImageView(this);
        if (banner != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
            art.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            iv.setImageDrawable(r.loadIcon(pm));
            art.addView(iv, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        }
        int w = dp(208), hh = dp(117);
        box.addView(art, new LinearLayout.LayoutParams(w, hh));
        TextView label = U.text(this, String.valueOf(r.loadLabel(pm)), 15, 0xEEFFFFFF, false);
        label.setShadowLayer(dp(3), 0, dp(1), 0x66000000);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(8), 0, 0);
        box.addView(label, new LinearLayout.LayoutParams(w, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(22));
        box.setLayoutParams(lp);
        box.setOnFocusChangeListener((v, on) -> {
            v.animate().scaleX(on ? 1.1f : 1f).scaleY(on ? 1.1f : 1f).translationZ(on ? dp(12) : 0).setDuration(150).start();
            GradientDrawable g = U.round(0x00000000, dp(16));
            if (on) g.setStroke(dp(4), 0xFFFFFFFF);   // enfocada: borde blanco grueso, como en ChronOS
            art.setForeground(on ? g : null);
            label.setTextColor(on ? U.FG : 0xEEFFFFFF);
        });
        box.setOnClickListener(v -> TvService.launch(this, pkg));
        box.setOnLongClickListener(v -> {   // mantener OK: fijar o quitar de "Fijadas"
            List<String> pins = TvService.pinList(this);
            boolean on = !pins.contains(pkg);
            pins.remove(pkg);
            if (on) pins.add(pkg);
            getSharedPreferences("tv", MODE_PRIVATE).edit().putString("pins", new org.json.JSONArray(pins).toString()).apply();
            notice(on ? "Fijada en el inicio" : "Quitada de Fijadas");
            reload();
            if (TvService.instance != null) TvService.instance.broadcast(TvService.msg("pins", "pins", new org.json.JSONArray(pins)));
            return true;
        });
        return box;
    }

    /** Tarjeta "Prepara la TV": los permisos que hacen falta para manejarla desde el teléfono. */
    private View setup() {
        setupCard = new LinearLayout(this);
        setupCard.setOrientation(LinearLayout.VERTICAL);
        setupCard.setPadding(dp(24), dp(18), dp(24), dp(18));
        setupCard.setBackground(U.card(this, 20));
        setupCard.addView(U.text(this, "Prepara la TV para el teléfono", 20, U.FG, true));
        TextView d = U.text(this, "Para que el teléfono pueda cambiar de app aunque estés viendo otra cosa, usar Atrás, las flechas y escribir, "
                + "activa estos permisos una vez.", 15, U.MUTED, false);
        d.setPadding(0, dp(6), 0, dp(12));
        setupCard.addView(d);
        LinearLayout btns = new LinearLayout(this);
        boolean ov = Settings.canDrawOverlays(this), ac = TvAccess.instance != null;
        btns.addView(button((ov ? "✓ " : "1. ") + "Mostrar sobre otras apps", () -> {
            if (!openSettings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()))))
                if (!openSettings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)))
                    notice("No pude abrir ese ajuste en esta TV");
        }));
        boolean inst = getPackageManager().canRequestPackageInstalls();
        btns.addView(button((inst ? "✓ " : "") + "Instalar actualizaciones", () -> {
            if (!openSettings(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + getPackageName()))))
                if (!openSettings(new Intent(Settings.ACTION_SECURITY_SETTINGS))) notice("No pude abrir ese ajuste");
        }));
        boolean us = TvService.usageAccess(this);
        btns.addView(button((us ? "✓ " : "") + "Uso de apps (Chrono como inicio)", () -> {
            if (!openSettings(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) notice("Esta TV no tiene ese ajuste: usa Accesibilidad");
        }));
        boolean ch = Channels.allowed(this);
        btns.addView(button((ch ? "✓ " : "") + "Canales de TV", () -> requestPermissions(new String[]{Channels.PERM_TV}, 5)));
        if (!Updater.lite(this)) btns.addView(button((ac ? "✓ " : "2. ") + "Accesibilidad → Chrono Remote", () -> {
            if (!openSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) notice("No pude abrir Accesibilidad");
        }));
        setupCard.addView(btns);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        setupCard.setLayoutParams(lp);
        return setupCard;
    }

    private boolean openSettings(Intent i) {
        try { startActivity(i); return true; } catch (Exception e) { return false; }
    }

    private View button(String s, Runnable r) {
        TextView b = U.text(this, s, 16, U.FG, true);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(U.round(0x33FFFFFF, dp(22)));
        b.setOnFocusChangeListener((v, on) -> v.setBackground(U.round(on ? FolderBar.GREEN : 0x33FFFFFF, dp(22))));
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(14));
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------- flechas desde el teléfono (en esta pantalla funcionan siempre) ----------------

    boolean key(String k) {
        int code;
        switch (k) {
            case "up": code = KeyEvent.KEYCODE_DPAD_UP; break;
            case "down": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "left": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "right": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "ok": code = KeyEvent.KEYCODE_DPAD_CENTER; break;
            case "back": return true;   // ya estamos en el inicio
            default: return false;
        }
        h.post(() -> {
            View f = getCurrentFocus();
            if (f == null) { content.requestFocus(View.FOCUS_DOWN); return; }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER) { f.performClick(); return; }
            int dir = code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
                    : code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
            View n = f.focusSearch(dir);
            if (n != null) n.requestFocus(dir);
        });
        return true;
    }
}
