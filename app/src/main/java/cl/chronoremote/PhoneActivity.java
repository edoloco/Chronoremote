package cl.chronoremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Teléfono: el control de la TV al estilo ChronOS. Abajo, la barra con tres secciones:
 * Control (cruceta, volumen, multimedia, escribir), Apps (las de la TV, para abrirlas) y Enviar (fotos, videos, música, enlaces).
 */
public class PhoneActivity extends Activity implements Remote.Listener, AtvRemote.Listener {

    private Remote remote;
    private TextView status, nowTitle;
    private View dot, hint, nowBar;
    private final View[] pages = new View[4];
    private final LinearLayout[] tabs = new LinearLayout[4];
    private int page;
    private LinearLayout appsBox;
    private SeekBar volBar, nowSeek;
    private Glyph nowPlay, muteBtn;
    private EditText textField, linkField;
    private boolean nowDragging;
    private JSONArray apps = new JSONArray(), pins = new JSONArray();
    private String appsSig = "";
    private final Map<String, Bitmap> art = new HashMap<>();
    private Intent pendingShare;

    private int dp(float v) { return U.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (U.isTv(this)) {   // la misma app en la TV abre la pantalla de la TV
            startActivity(new Intent(this, TvActivity.class));
            finish();
            return;
        }
        getWindow().setStatusBarColor(U.BG_TOP);
        getWindow().setNavigationBarColor(U.BG_BOTTOM);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        remote = Remote.get(this);
        loadAppsCache();
        build();
        remote.add(this);
        remote.start();
        atv = AtvRemote.get(this);
        atv.add(this);
        atv.start(remote.ip());
        onState(remote.connected(), remote.info);
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleShare(i); }

    @Override protected void onDestroy() { if (remote != null) remote.remove(this); if (atv != null) atv.remove(this); super.onDestroy(); }

    // ---------------- control total (protocolo de Android TV) ----------------

    private AtvRemote atv;
    private long lastAtvTip;

    @Override public void onAtvState(boolean ok, String info) {
        if (hint != null) hint.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (status != null && remote.connected()) status.setText("Conectado a " + (remote.tvName.isEmpty() ? "la TV" : remote.tvName) + (ok ? " · control total" : ""));
    }

    /** Vincular: la TV muestra un código y aquí se escribe. */
    private void pairFlow() {
        String ip = remote.ip();
        if (ip == null || ip.isEmpty()) { Toast.makeText(this, "Primero conecta la TV (o escribe su IP en el menú ⋯)", Toast.LENGTH_LONG).show(); return; }
        AlertDialog wait = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Vinculando…").setMessage("Pidiéndole a la TV un código. Mira la pantalla de la TV.").setCancelable(false).show();
        atv.startPairing(ip, err -> {
            wait.dismiss();
            if (err != null) {
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("No se pudo vincular")
                        .setMessage(err + "\n\nRevisa que la TV tenga Google TV o Android TV y que esté en el mismo WiFi.")
                        .setPositiveButton("Entendido", null).show();
                return;
            }
            EditText e = new EditText(this);
            e.setHint("Código de 6 caracteres");
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setTextSize(24);
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Código de la TV")
                    .setMessage("Escribe el código que apareció en la TV.")
                    .setView(e)
                    .setPositiveButton("Vincular", (d, w) -> atv.finishPairing(e.getText().toString(), err2 -> {
                        if (err2 == null) Toast.makeText(this, "Listo: control total de la TV", Toast.LENGTH_LONG).show();
                        else new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("No se pudo vincular")
                                .setMessage(err2).setPositiveButton("Probar de nuevo", (d2, w2) -> pairFlow()).setNegativeButton("Cancelar", null).show();
                    }))
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
    }

    /** Teclas de Android para cada botón del control. */
    private static int keyOf(String k) {
        switch (k) {
            case "up": return 19; case "down": return 20; case "left": return 21; case "right": return 22; case "ok": return 23;
            case "back": return 4; case "home": return 3; case "sleep": return 26; case "menu": return 82;
            case "play": return 85; case "next": return 87; case "prev": return 88; case "ff": return 90; case "rw": return 89;
            case "vup": return 24; case "vdown": return 25; case "mute": return 164; case "chup": return 166; case "chdown": return 167;
            default: return 0;
        }
    }

    /** Manda la tecla por el control total; si no está vinculado, devuelve false (se usa el camino de antes). */
    private boolean atvKey(String k) {
        int c = keyOf(k);
        return c != 0 && atv != null && atv.key(c);
    }

    // ---------------- armado de la pantalla ----------------

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(U.background());
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // cabecera: logo, nombre, estado de la conexión y menú
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(16), dp(8), dp(8));
        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("chronos", "drawable", getPackageName()));
        head.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(dp(10), 0, 0, 0);
        tcol.addView(U.text(this, "Chrono Remote", 19, U.FG, true));
        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(this);
        st.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        status = U.text(this, "", 13, U.MUTED, false);
        status.setPadding(dp(6), 0, 0, 0);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        st.addView(status);
        tcol.addView(st);
        head.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1));
        Glyph menu = new Glyph(this, Glyph.MENU, U.FG);
        menu.setOnClickListener(v -> menu());
        head.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        col.addView(head);

        FrameLayout body = new FrameLayout(this);
        pages[0] = controlPage();
        pages[1] = appsPage();
        pages[2] = channelsPage();
        pages[3] = sendPage();
        for (View p : pages) body.addView(p, new FrameLayout.LayoutParams(-1, -1));
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        nowBar = nowBar();
        nowBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.setMargins(dp(12), 0, dp(12), dp(6));
        col.addView(nowBar, nl);

        // barra de abajo (como la de ChronOS): tres secciones
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setBackground(U.card(this, 26));
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        int[] icons = {Glyph.REMOTE, Glyph.APPS, Glyph.TV, Glyph.SEND};
        String[] names = {"Control", "Apps", "Canales", "Enviar"};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(6), 0, dp(6));
            t.addView(new Glyph(this, icons[i], U.FG), new LinearLayout.LayoutParams(dp(28), dp(28)));
            t.addView(U.text(this, names[i], 12, U.FG, true));
            t.setOnClickListener(v -> showPage(k));
            tabs[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.setMargins(dp(12), 0, dp(12), dp(12));
        col.addView(bar, bl);

        setContentView(root);
        showPage(0);
    }

    private void showPage(int k) {
        page = k;
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == k ? View.VISIBLE : View.GONE);
            tabs[i].setBackground(i == k ? U.round(0x40FFFFFF, dp(20)) : null);
        }
        if (k == 1 && remote.connected()) remote.send("apps", "sig", appsSig);
        if (k == 2 && remote.connected()) remote.send("channels");
    }

    private ScrollView scroll(LinearLayout content) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(content);
        return sv;
    }

    /** Botón redondo con ícono y texto. */
    private View roundBtn(int glyph, String label, float size, Runnable r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout circ = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x26FFFFFF);
        g.setStroke(dp(1), 0x33FFFFFF);
        circ.setBackground(g);
        circ.addView(new Glyph(this, glyph, U.FG), new FrameLayout.LayoutParams(dp(size * 0.6f), dp(size * 0.6f), Gravity.CENTER));
        box.addView(circ, new LinearLayout.LayoutParams(dp(size), dp(size)));
        if (label != null) {
            TextView t = U.text(this, label, 12, U.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(4), 0, 0);
            box.addView(t);
        }
        box.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); r.run(); });
        return box;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        return r;
    }

    private void addSpaced(LinearLayout row, View v) { row.addView(v, new LinearLayout.LayoutParams(0, -2, 1)); }

    // ---------------- Control ----------------

    private View controlPage() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(16), dp(4), dp(16), dp(16));

        // aviso si la TV todavía no tiene el permiso de accesibilidad (Atrás y flechas en otras apps)
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(16), dp(12), dp(16), dp(12));
        h.setBackground(U.card(this, 18));
        TextView ht = U.text(this, "Control total: vincula este teléfono con la TV y maneja CUALQUIER app (YouTube, Netflix, ajustes…). Se hace una sola vez.", 13, U.FG, false);
        h.addView(ht);
        TextView hb = U.text(this, "Vincular con la TV  →", 14, U.ACCENT_SOFT, true);
        hb.setPadding(0, dp(6), 0, 0);
        h.addView(hb);
        h.setOnClickListener(v -> pairFlow());
        h.setVisibility(View.GONE);
        hint = h;
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.bottomMargin = dp(10);
        c.addView(h, hl);

        LinearLayout r1 = row();
        addSpaced(r1, roundBtn(Glyph.BACK, "Atrás", 54, () -> nav("back")));
        addSpaced(r1, roundBtn(Glyph.HOME, "Inicio", 54, () -> nav("home")));
        addSpaced(r1, roundBtn(Glyph.POWER, "Apagar", 54, () -> nav("sleep")));
        c.addView(r1, new LinearLayout.LayoutParams(-1, -2));

        DPadView pad = new DPadView(this, this::nav);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(270), dp(270));
        pl.setMargins(0, dp(14), 0, dp(14));
        c.addView(pad, pl);

        // volumen
        LinearLayout vol = new LinearLayout(this);
        vol.setGravity(Gravity.CENTER_VERTICAL);
        vol.setBackground(U.card(this, 24));
        vol.setPadding(dp(8), dp(4), dp(8), dp(4));
        Glyph vd = new Glyph(this, Glyph.VOL_DOWN, U.FG);
        vd.setOnClickListener(v -> { if (!atvKey("vdown")) remote.send("vol", "d", -1); });
        vol.addView(vd, new LinearLayout.LayoutParams(dp(46), dp(46)));
        volBar = new SeekBar(this);
        volBar.setMax(100);
        volBar.getProgressDrawable().setColorFilter(U.ACCENT, PorterDuff.Mode.SRC_IN);
        volBar.getThumb().setColorFilter(U.FG, PorterDuff.Mode.SRC_IN);
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { remote.send("vol", "set", s.getProgress() / 100.0); }
        });
        vol.addView(volBar, new LinearLayout.LayoutParams(0, -2, 1));
        Glyph vu = new Glyph(this, Glyph.VOL_UP, U.FG);
        vu.setOnClickListener(v -> { if (!atvKey("vup")) remote.send("vol", "d", 1); });
        vol.addView(vu, new LinearLayout.LayoutParams(dp(46), dp(46)));
        muteBtn = new Glyph(this, Glyph.MUTE, U.FG);
        muteBtn.setOnClickListener(v -> { if (!atvKey("mute")) remote.send("vol", "d", 0); });
        vol.addView(muteBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));
        c.addView(vol, new LinearLayout.LayoutParams(-1, -2));

        // multimedia: lo que se reproduce en la TV
        LinearLayout media = row();
        media.setPadding(0, dp(14), 0, dp(6));
        int[] g = {Glyph.PREV, Glyph.RW, Glyph.PLAY, Glyph.FF, Glyph.NEXT};
        String[] k = {"prev", "rw", "play", "ff", "next"};
        for (int i = 0; i < g.length; i++) {
            final String key = k[i];
            addSpaced(media, roundBtn(g[i], null, i == 2 ? 58 : 46, () -> { if (!atvKey(key)) remote.send("media", "k", key); }));
        }
        c.addView(media, new LinearLayout.LayoutParams(-1, -2));

        // canales
        LinearLayout chr = row();
        chr.setPadding(0, dp(4), 0, dp(4));
        addSpaced(chr, pill("Canal −", () -> { if (!atvKey("chdown")) remote.send("chan", "d", -1); }));
        addSpaced(chr, pill("Canales", () -> showPage(2)));
        addSpaced(chr, pill("Canal +", () -> { if (!atvKey("chup")) remote.send("chan", "d", 1); }));
        c.addView(chr, new LinearLayout.LayoutParams(-1, -2));

        // escribir en la TV
        LinearLayout tx = new LinearLayout(this);
        tx.setGravity(Gravity.CENTER_VERTICAL);
        tx.setBackground(U.card(this, 24));
        tx.setPadding(dp(14), 0, dp(4), 0);
        tx.addView(new Glyph(this, Glyph.KEYBOARD, U.MUTED), new LinearLayout.LayoutParams(dp(26), dp(26)));
        textField = field("Escribir en la TV (búsquedas)");
        textField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        textField.setOnEditorActionListener((v, a, e) -> { sendText(true); return true; });
        tx.addView(textField, new LinearLayout.LayoutParams(0, dp(52), 1));
        Glyph go = new Glyph(this, Glyph.SEND, U.FG);
        go.setOnClickListener(v -> sendText(true));
        tx.addView(go, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(10);
        c.addView(tx, tl);
        return scroll(c);
    }

    private View pill(String label, Runnable r) {
        TextView t = U.text(this, label, 15, U.FG, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(12), dp(8), dp(12));
        t.setBackground(U.card(this, 22));
        t.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); r.run(); });
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(4), 0, dp(4), 0);
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    // ---------------- Canales ----------------

    private LinearLayout chBox;
    private TextView chNow;
    private JSONObject chData;

    private View channelsPage() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(4), dp(14), dp(16));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(U.card(this, 24));
        top.setPadding(dp(6), dp(6), dp(6), dp(6));
        Glyph down = new Glyph(this, Glyph.PREV, U.FG);
        down.setOnClickListener(v -> remote.send("chan", "d", -1));
        top.addView(down, new LinearLayout.LayoutParams(dp(56), dp(56)));
        chNow = U.text(this, "Canal", 16, U.FG, true);
        chNow.setGravity(Gravity.CENTER);
        chNow.setSingleLine(true);
        chNow.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(chNow, new LinearLayout.LayoutParams(0, -2, 1));
        Glyph up = new Glyph(this, Glyph.NEXT, U.FG);
        up.setOnClickListener(v -> remote.send("chan", "d", 1));
        top.addView(up, new LinearLayout.LayoutParams(dp(56), dp(56)));
        c.addView(top, new LinearLayout.LayoutParams(-1, -2));
        chBox = new LinearLayout(this);
        chBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(chBox, new LinearLayout.LayoutParams(-1, -2));
        renderChannels();
        return scroll(c);
    }

    private void renderChannels() {
        if (chBox == null) return;
        chBox.removeAllViews();
        JSONObject d = chData;
        if (d == null) { chBox.addView(empty(remote.connected() ? "Cargando los canales…" : "Conéctate a la TV")); return; }
        JSONArray ins = d.optJSONArray("inputs");
        if (ins != null && ins.length() > 0) {
            chBox.addView(section("Entradas"));
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout r = new LinearLayout(this);
            for (int i = 0; i < ins.length(); i++) {
                JSONObject o = ins.optJSONObject(i);
                TextView t = U.text(this, o.optString("name"), 14, U.FG, true);
                t.setPadding(dp(16), dp(10), dp(16), dp(10));
                t.setBackground(U.card(this, 20));
                t.setOnClickListener(v -> remote.send("input", "id", o.optString("id")));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMarginEnd(dp(8));
                r.addView(t, lp);
            }
            hs.addView(r);
            chBox.addView(hs);
        }
        JSONArray chs = d.optJSONArray("channels");
        chBox.addView(section("Canales"));
        if (!d.optBoolean("perm")) {
            chBox.addView(empty("La TV todavía no deja ver sus canales. En la TV, en la tarjeta \"Prepara la TV\", toca \"Canales de TV\" y acepta."));
            return;
        }
        if (chs == null || chs.length() == 0) {
            chBox.addView(empty("La TV no tiene canales guardados. Búscalos primero en la app de TV de la TV (Canales / Live TV)."));
            return;
        }
        long cur = d.optLong("cur", -1);
        for (int i = 0; i < chs.length(); i++) {
            JSONObject o = chs.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(o.optLong("id") == cur ? U.round(0x4400B894, dp(16)) : U.card(this, 16));
            TextView num = U.text(this, o.optString("num"), 15, U.FG, true);
            num.setGravity(Gravity.CENTER);
            num.setBackground(U.round(0x33FFFFFF, dp(10)));
            row.addView(num, new LinearLayout.LayoutParams(dp(56), dp(40)));
            Bitmap lg = image("ch" + o.optLong("id"), o.optString("logo"));
            if (lg != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(lg);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(48), dp(32));
                ll.setMarginStart(dp(10));
                row.addView(iv, ll);
            }
            TextView nm = U.text(this, o.optString("name"), 15, U.FG, false);
            nm.setPadding(dp(12), 0, 0, 0);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                remote.send("tune", "id", o.optLong("id"));
                try { chData.put("cur", o.optLong("id")); } catch (Exception ignored) { }
                chNow.setText(o.optString("num") + "  " + o.optString("name"));
                renderChannels();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            chBox.addView(row, lp);
        }
    }

    private TextView empty(String s) {
        TextView t = U.text(this, s, 14, U.MUTED, false);
        t.setPadding(dp(8), dp(14), dp(8), dp(14));
        return t;
    }

    private EditText field(String hintText) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setHintTextColor(U.MUTED);
        e.setTextColor(U.FG);
        e.setTextSize(15);
        e.setBackground(null);
        e.setSingleLine(true);
        e.setPadding(dp(10), 0, dp(6), 0);
        return e;
    }

    private void nav(String k) {
        // Inicio = la pantalla de Chrono (con la tecla Inicio aparecería primero el inicio de Google TV)
        if ("home".equals(k) && remote.connected()) { remote.send("nav", "k", "home"); return; }
        if (atvKey(k)) return;   // control total: funciona en cualquier app de la TV
        // sin control total, en otras apps no llega nada: se avisa por qué y cómo arreglarlo
        if (atv != null && !atv.ready() && System.currentTimeMillis() - lastAtvTip > 8000) {
            lastAtvTip = System.currentTimeMillis();
            String ip = remote.ip();
            Toast.makeText(this, atv.paired(ip) ? "Control total: " + atv.info : "Para manejar otras apps de la TV toca \"Vincular con la TV\" (arriba)", Toast.LENGTH_LONG).show();
        }
        if (!remote.connected()) { Toast.makeText(this, "La TV no está conectada", Toast.LENGTH_SHORT).show(); return; }
        remote.send("nav", "k", k);
    }

    private void sendText(boolean enter) {
        String s = textField.getText().toString();
        if (s.isEmpty()) return;
        if (atv != null && atv.text(s)) {   // control total: se escribe en el campo abierto de la TV
            if (enter) atv.key(66);
            textField.setText("");
            return;
        }
        remote.send("text", "s", s, "enter", enter);
        Toast.makeText(this, "Enviado a la TV", Toast.LENGTH_SHORT).show();
    }

    // ---------------- Apps ----------------

    private View appsPage() {
        appsBox = new LinearLayout(this);
        appsBox.setOrientation(LinearLayout.VERTICAL);
        appsBox.setPadding(dp(12), 0, dp(12), dp(16));
        renderApps();
        return scroll(appsBox);
    }

    private void renderApps() {
        if (appsBox == null) return;
        appsBox.removeAllViews();
        if (apps.length() == 0) {
            TextView t = U.text(this, remote.connected() ? "Cargando las apps de la TV…" : "Conéctate a la TV para ver sus apps", 15, U.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(40), 0, 0);
            appsBox.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        List<JSONObject> pinned = new ArrayList<>(), rest = new ArrayList<>();
        List<String> pl = new ArrayList<>();
        for (int i = 0; i < pins.length(); i++) pl.add(pins.optString(i));
        for (String p : pl) for (int i = 0; i < apps.length(); i++) if (p.equals(apps.optJSONObject(i).optString("pkg"))) pinned.add(apps.optJSONObject(i));
        for (int i = 0; i < apps.length(); i++) rest.add(apps.optJSONObject(i));
        java.util.Collections.sort(rest, (a, b) -> a.optString("label").compareToIgnoreCase(b.optString("label")));
        if (!pinned.isEmpty()) { appsBox.addView(section("Fijadas en la TV")); grid(pinned); }
        appsBox.addView(section("Apps de la TV"));
        grid(rest);
    }

    private TextView section(String s) {
        TextView t = U.text(this, s, 14, U.MUTED, true);
        t.setPadding(dp(6), dp(14), 0, dp(6));
        return t;
    }

    private void grid(List<JSONObject> list) {
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                appsBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(appCard(list.get(i)), lp);
        }
        if (list.size() % 2 == 1 && row != null) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private View appCard(JSONObject a) {
        String pkg = a.optString("pkg"), label = a.optString("label");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        FrameLayout pic = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int h) {   // 16:9
                int ww = MeasureSpec.getSize(w);
                super.onMeasure(MeasureSpec.makeMeasureSpec(ww, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ww * 9 / 16, MeasureSpec.EXACTLY));
            }
        };
        pic.setBackground(U.round(0xFF16403A, dp(14)));
        pic.setClipToOutline(true);
        ImageView iv = new ImageView(this);
        Bitmap banner = image(pkg + ":b", a.optString("banner"));
        if (banner != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(banner);
            pic.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            iv.setImageBitmap(image(pkg + ":i", a.optString("icon")));
            pic.addView(iv, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        }
        box.addView(pic, new LinearLayout.LayoutParams(-1, -2));
        TextView t = U.text(this, label, 13, U.FG, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(4), dp(5), dp(4), 0);
        box.addView(t);
        box.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction(() -> v.animate().scaleX(1).scaleY(1).setDuration(120).start()).start();
            remote.send("launch", "pkg", pkg);
        });
        box.setOnLongClickListener(v -> { appMenu(pkg, label); return true; });
        return box;
    }

    private Bitmap image(String key, String b64) {
        if (art.containsKey(key)) return art.get(key);
        Bitmap b = U.fromPng(b64);
        art.put(key, b);
        return b;
    }

    private boolean isPinned(String pkg) {
        for (int i = 0; i < pins.length(); i++) if (pkg.equals(pins.optString(i))) return true;
        return false;
    }

    private void appMenu(String pkg, String label) {
        boolean pinned = isPinned(pkg);
        String[] opts = {"Abrir en la TV", pinned ? "Quitar de Fijadas" : "Fijar en el inicio de la TV", "Desinstalar de la TV"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(label)
                .setItems(opts, (d, w) -> {
                    if (w == 0) remote.send("launch", "pkg", pkg);
                    else if (w == 1) remote.send("pin", "pkg", pkg, "on", !pinned);
                    else {
                        remote.send("uninstall", "pkg", pkg);
                        Toast.makeText(this, "Confirma en la TV con su control", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private File cacheFile() { return new File(getFilesDir(), "apps_tv.json"); }

    private void loadAppsCache() {
        try {
            byte[] d = java.nio.file.Files.readAllBytes(cacheFile().toPath());
            JSONObject o = new JSONObject(new String(d, StandardCharsets.UTF_8));
            apps = o.optJSONArray("list") != null ? o.optJSONArray("list") : new JSONArray();
            pins = o.optJSONArray("pins") != null ? o.optJSONArray("pins") : new JSONArray();
            appsSig = o.optString("sig");
        } catch (Exception ignored) { }
    }

    private void saveAppsCache() {
        try (FileOutputStream o = new FileOutputStream(cacheFile())) {
            JSONObject j = new JSONObject();
            j.put("list", apps); j.put("pins", pins); j.put("sig", appsSig);
            o.write(j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    // ---------------- Enviar ----------------

    private static final int PICK_MEDIA = 11, PICK_AUDIO = 12;

    private View sendPage() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(4), dp(16), dp(16));
        c.addView(sendCard(Glyph.IMAGE, "Foto o video", "Se ve en grande en la TV", () -> pick("*/*", new String[]{"image/*", "video/*"}, PICK_MEDIA)));
        c.addView(sendCard(Glyph.MUSIC, "Música", "Suena en la TV; se controla desde aquí", () -> pick("audio/*", null, PICK_AUDIO)));

        LinearLayout link = new LinearLayout(this);
        link.setOrientation(LinearLayout.VERTICAL);
        link.setBackground(U.card(this, 20));
        link.setPadding(dp(16), dp(14), dp(12), dp(10));
        LinearLayout lh = new LinearLayout(this);
        lh.setGravity(Gravity.CENTER_VERTICAL);
        lh.addView(new Glyph(this, Glyph.LINK, U.FG), new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView lt = U.text(this, "Enlace (YouTube, web)", 16, U.FG, true);
        lt.setPadding(dp(12), 0, 0, 0);
        lh.addView(lt);
        link.addView(lh);
        LinearLayout lrow = new LinearLayout(this);
        lrow.setGravity(Gravity.CENTER_VERTICAL);
        linkField = field("Pega aquí el enlace");
        linkField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkField.setImeOptions(EditorInfo.IME_ACTION_GO);
        linkField.setOnEditorActionListener((v, a, e) -> { sendLink(linkField.getText().toString()); return true; });
        lrow.addView(linkField, new LinearLayout.LayoutParams(0, dp(48), 1));
        Glyph go = new Glyph(this, Glyph.SEND, U.FG);
        go.setOnClickListener(v -> sendLink(linkField.getText().toString()));
        lrow.addView(go, new LinearLayout.LayoutParams(dp(48), dp(48)));
        link.addView(lrow);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = dp(10);
        c.addView(link, ll);

        TextView tip = U.text(this, "También desde YouTube, Galería o cualquier app: Compartir → Chrono Remote.", 13, U.MUTED, false);
        tip.setPadding(dp(6), dp(16), dp(6), 0);
        c.addView(tip);
        return scroll(c);
    }

    private View sendCard(int glyph, String title, String sub, Runnable r) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(U.card(this, 20));
        b.setPadding(dp(16), dp(16), dp(16), dp(16));
        b.addView(new Glyph(this, glyph, U.FG), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), 0, 0, 0);
        t.addView(U.text(this, title, 16, U.FG, true));
        t.addView(U.text(this, sub, 13, U.MUTED, false));
        b.addView(t);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void pick(String type, String[] types, int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type);
        if (types != null) i.putExtra(Intent.EXTRA_MIME_TYPES, types);
        try { startActivityForResult(i, req); } catch (Exception e) { Toast.makeText(this, "No hay dónde elegir archivos", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        sendFile(data.getData(), getContentResolver().getType(data.getData()));
    }

    private void sendFile(Uri uri, String mime) {
        if (!remote.connected()) { Toast.makeText(this, "La TV no está conectada", Toast.LENGTH_SHORT).show(); return; }
        String ip = remote.localIp();
        if (ip == null) { Toast.makeText(this, "No sé la dirección de este teléfono en el WiFi", Toast.LENGTH_SHORT).show(); return; }
        if (mime == null) mime = "application/octet-stream";
        String kind = mime.startsWith("image") ? "image" : mime.startsWith("audio") ? "audio" : mime.startsWith("video") ? "video" : null;
        if (kind == null) { Toast.makeText(this, "La TV solo muestra fotos, videos y música", Toast.LENGTH_SHORT).show(); return; }
        String url = FileHttp.share(this, uri, mime, ip);
        if (url == null) { Toast.makeText(this, "No pude preparar el archivo", Toast.LENGTH_SHORT).show(); return; }
        remote.send("open", "url", url, "kind", kind, "title", nameOf(uri));
        Toast.makeText(this, "Abriendo en la TV…", Toast.LENGTH_SHORT).show();
    }

    private String nameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "";
    }

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private void sendLink(String text) {
        Matcher m = URL.matcher(text == null ? "" : text);
        if (!m.find()) { Toast.makeText(this, "No encontré un enlace", Toast.LENGTH_SHORT).show(); return; }
        if (!remote.connected()) { Toast.makeText(this, "La TV no está conectada", Toast.LENGTH_SHORT).show(); return; }
        remote.send("open", "url", m.group(), "kind", "link");
        Toast.makeText(this, "Abriendo en la TV…", Toast.LENGTH_SHORT).show();
    }

    /** Compartir → Chrono Remote desde otra app. Si la TV aún no se conecta, se manda apenas se conecte. */
    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        if (!remote.connected()) { pendingShare = i; Toast.makeText(this, "Buscando la TV para enviarlo…", Toast.LENGTH_SHORT).show(); return; }
        pendingShare = null;
        Uri stream = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream != null) sendFile(stream, i.getType() != null && !i.getType().endsWith("*") ? i.getType() : getContentResolver().getType(stream));
        else sendLink(i.getStringExtra(Intent.EXTRA_TEXT));
        setIntent(new Intent());
    }

    // ---------------- lo que se ve en la TV (lo mandado desde aquí) ----------------

    private View nowBar() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(U.round(0xEE0E3B35, dp(20)));
        b.setPadding(dp(12), dp(8), dp(8), dp(6));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        nowTitle = U.text(this, "", 14, U.FG, true);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(nowTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Glyph rw = new Glyph(this, Glyph.RW, U.FG);
        rw.setOnClickListener(v -> remote.send("view", "a", "seek", "ms", -10000));
        nowPlay = new Glyph(this, Glyph.PAUSE, U.FG);
        nowPlay.setOnClickListener(v -> remote.send("view", "a", "toggle"));
        Glyph ff = new Glyph(this, Glyph.FF, U.FG);
        ff.setOnClickListener(v -> remote.send("view", "a", "seek", "ms", 10000));
        Glyph close = new Glyph(this, Glyph.CLOSE, U.FG);
        close.setOnClickListener(v -> remote.send("view", "a", "close"));
        for (View v : new View[]{rw, nowPlay, ff, close}) top.addView(v, new LinearLayout.LayoutParams(dp(40), dp(40)));
        b.addView(top);
        nowSeek = new SeekBar(this);
        nowSeek.setMax(1000);
        nowSeek.getProgressDrawable().setColorFilter(U.ACCENT, PorterDuff.Mode.SRC_IN);
        nowSeek.getThumb().setColorFilter(U.FG, PorterDuff.Mode.SRC_IN);
        nowSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { }
            @Override public void onStartTrackingTouch(SeekBar s) { nowDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { nowDragging = false; remote.send("view", "a", "to", "f", s.getProgress() / 1000.0); }
        });
        b.addView(nowSeek);
        return b;
    }

    // ---------------- menú ⋯ ----------------

    private void menu() {
        boolean auto = remote.tv.optBoolean("auto", true);
        String[] opts = {"Vincular control total con la TV", "Preparar la TV (permisos)", "Actualizar este teléfono", "Actualizar la TV", "Conectar por dirección IP", "Acerca de",
                "Abrir Chrono al prender la TV: " + (auto ? "sí" : "no")};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setItems(opts, (d, w) -> {
                    switch (w - 1) {
                        case -1: pairFlow(); break;
                        case 0: setupTv(); break;
                        case 1: Updater.run(this, (msg, done) -> runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show())); break;
                        case 2:
                            if (!remote.connected()) { Toast.makeText(this, "La TV no está conectada", Toast.LENGTH_SHORT).show(); break; }
                            remote.send("update");
                            Toast.makeText(this, "La TV busca la actualización; confirma allá con su control", Toast.LENGTH_LONG).show();
                            break;
                        case 3: askIp(); break;
                        case 4: about(); break;
                        default:
                            remote.send("autostart", "on", !auto);
                            Toast.makeText(this, !auto ? "Chrono se abrirá al prender la TV" : "Chrono ya no se abrirá solo", Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .show();
    }

    private void setupTv() {
        if (!remote.connected()) { Toast.makeText(this, "La TV no está conectada", Toast.LENGTH_SHORT).show(); return; }
        remote.send("setup");
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Preparar la TV")
                .setMessage("En la TV apareció Chrono Remote con la tarjeta \"Prepara la TV\". Con el control de la TV:\n\n"
                        + "1. \"Mostrar sobre otras apps\": activa Chrono Remote (permite cambiar de app aunque estés viendo otra cosa).\n\n"
                        + "2. \"Accesibilidad\": entra a Chrono Remote y actívalo (Atrás, flechas y escribir en cualquier app).\n\n"
                        + "Si la TV dice \"ajuste restringido\", ve a Ajustes → Apps → Chrono Remote → Permitir ajustes restringidos, y repite.")
                .setPositiveButton("Entendido", null)
                .show();
    }

    private void askIp() {
        EditText e = new EditText(this);
        e.setHint("Ej: 192.168.1.50 (vacío = buscar sola)");
        e.setText(remote.manualIp());
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Dirección IP de la TV")
                .setMessage("Solo si no la encuentra sola. Está en la TV: Ajustes → Red → tu WiFi.")
                .setView(e)
                .setPositiveButton("Conectar", (d, w) -> remote.setManualIp(e.getText().toString()))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void about() {
        JSONObject tv = remote.tv;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Chrono Remote")
                .setMessage("Este teléfono: " + Updater.installed(this) + "\nTV: " + (remote.connected() ? tv.optString("ver", "?") + " · " + tv.optString("name") : "sin conexión")
                        + "\n\nLa TV es la central; los teléfonos la controlan. Instala la misma app en la TV y en cada teléfono, conectados al mismo WiFi.")
                .setPositiveButton("Cerrar", null)
                .show();
    }

    // ---------------- mensajes de la TV ----------------

    @Override public void onState(boolean on, String info) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? U.ACCENT : 0xFF8798A9);
        dot.setBackground(g);
        status.setText(on ? "Conectado a " + (remote.tvName.isEmpty() ? "la TV" : remote.tvName) : info);
        if (on) {
            atv.start(remote.ip());
            onAtvState(atv.ready(), atv.info);
            remote.send("apps", "sig", appsSig);
            if (pendingShare != null) handleShare(pendingShare);
        } else {
            nowBar.setVisibility(View.GONE);
            hint.setVisibility(atv != null && atv.ready() ? View.GONE : View.VISIBLE);
        }
    }

    @Override public void onMessage(JSONObject m) {
        switch (m.optString("t")) {
            case "tv":
                status.setText("Conectado a " + m.optString("name"));
                hint.setVisibility(atv.ready() ? View.GONE : View.VISIBLE);
                break;
            case "appsList":
                apps = m.optJSONArray("list") != null ? m.optJSONArray("list") : new JSONArray();
                pins = m.optJSONArray("pins") != null ? m.optJSONArray("pins") : new JSONArray();
                appsSig = m.optString("sig");
                art.clear();
                saveAppsCache();
                renderApps();
                break;
            case "appsSame": renderApps(); break;
            case "pins":
                pins = m.optJSONArray("pins") != null ? m.optJSONArray("pins") : new JSONArray();
                saveAppsCache();
                renderApps();
                break;
            case "volNow":
                volBar.setProgress((int) Math.round(m.optDouble("f") * 100));
                muteBtn.setAlpha(m.optBoolean("muted") ? 1f : 0.55f);
                break;
            case "viewState": {
                boolean on = m.optBoolean("on");
                nowBar.setVisibility(on ? View.VISIBLE : View.GONE);
                if (!on) break;
                nowTitle.setText(("image".equals(m.optString("kind")) ? "🖼  " : "audio".equals(m.optString("kind")) ? "♪  " : "▶  ") + m.optString("title"));
                boolean media = !"image".equals(m.optString("kind"));
                nowSeek.setVisibility(media ? View.VISIBLE : View.GONE);
                nowPlay.setKind(m.optBoolean("play") ? Glyph.PAUSE : Glyph.PLAY);
                long dur = m.optLong("dur");
                if (!nowDragging && dur > 0) nowSeek.setProgress((int) (m.optLong("pos") * 1000 / dur));
                break;
            }
            case "channelList":
                chData = m;
                renderChannels();
                break;
            case "chanNow":
                if (chNow != null) chNow.setText(m.optString("num") + "  " + m.optString("name"));
                if (chData != null) { try { chData.put("cur", m.optLong("id")); } catch (Exception ignored) { } renderChannels(); }
                break;
            case "toast": Toast.makeText(this, m.optString("msg"), Toast.LENGTH_LONG).show(); break;
            default: break;
        }
    }

    // botones de volumen del teléfono = volumen de la TV
    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (remote != null && remote.connected() && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            remote.send("vol", "d", code == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
            return true;
        }
        return super.onKeyDown(code, e);
    }
}
