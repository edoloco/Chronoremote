package cl.chronoremote;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Base64;
import android.view.Gravity;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;

/** Utilidades comunes: rol del aparato, colores de ChronOS y dibujos simples. */
final class U {

    private U() { }

    // colores de ChronOS
    static final int BG_TOP = 0xFF0B1F22, BG_BOTTOM = 0xFF06312C, ACCENT = 0xFF00B894, ACCENT_SOFT = 0xFFA5D8BC,
            CARD = 0x26FFFFFF, CARD_STROKE = 0x33FFFFFF, FG = 0xFFF1F7F5, MUTED = 0xAAE0ECE8, ORANGE = 0xFFFF6D00;

    static final int PORT = 47830;
    static final String NSD_TYPE = "_chronoremote._tcp.";

    /** ¿Este aparato es la TV (central)? */
    static boolean isTv(Context c) {
        PackageManager pm = c.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable card(Context c, float radiusDp) {
        GradientDrawable g = round(CARD, dp(c, radiusDp));
        g.setStroke(dp(c, 1), CARD_STROKE);
        return g;
    }

    static GradientDrawable background() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{BG_TOP, BG_BOTTOM});
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    static Bitmap bitmap(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(cv);
        return b;
    }

    static String png(Bitmap b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
    }

    static Bitmap fromPng(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] d = Base64.decode(b64, Base64.DEFAULT);
            return android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length);
        } catch (Exception e) { return null; }
    }
}
