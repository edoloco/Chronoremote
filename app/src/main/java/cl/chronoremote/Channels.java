package cl.chronoremote;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TV: canales de la TV (antena / cable de la app de TV del fabricante) y entradas (HDMI).
 * Los canales se leen de la guía de Android (permiso "canales y programas de TV") y se abren con la app de TV del sistema.
 *   channels (teléfono → TV)            → channelList {channels:[{id,num,name,logo}], inputs:[{id,name}], cur, perm}
 *   tune {id} · chan {d: +1/-1} · input {id}
 */
final class Channels {

    static final String PERM_TV = "com.android.providers.tv.permission.READ_EPG_DATA";

    private Channels() { }

    private static final class Ch { long id; String num, name; double sort; }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERM_TV) == PackageManager.PERMISSION_GRANTED;
    }

    private static List<Ch> list(Context c) {
        List<Ch> out = new ArrayList<>();
        if (!allowed(c)) return out;
        ContentResolver cr = c.getContentResolver();
        String[] cols = {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_BROWSABLE};
        try (Cursor k = cr.query(TvContract.Channels.CONTENT_URI, cols, null, null, null)) {
            while (k != null && k.moveToNext()) {
                if (!k.isNull(3) && k.getInt(3) == 0) continue;   // canales ocultos por el usuario
                Ch ch = new Ch();
                ch.id = k.getLong(0);
                ch.num = k.getString(1) == null ? "" : k.getString(1);
                ch.name = k.getString(2) == null ? "" : k.getString(2);
                ch.sort = sortKey(ch.num);
                out.add(ch);
            }
        } catch (Exception ignored) {
            // sin la columna "browsable" (algunas TV la protegen): se lee sin ella
            try (Cursor k = cr.query(TvContract.Channels.CONTENT_URI, new String[]{cols[0], cols[1], cols[2]}, null, null, null)) {
                while (k != null && k.moveToNext()) {
                    Ch ch = new Ch();
                    ch.id = k.getLong(0);
                    ch.num = k.getString(1) == null ? "" : k.getString(1);
                    ch.name = k.getString(2) == null ? "" : k.getString(2);
                    ch.sort = sortKey(ch.num);
                    out.add(ch);
                }
            } catch (Exception ignored2) { }
        }
        Collections.sort(out, (a, b) -> a.sort != b.sort ? Double.compare(a.sort, b.sort) : a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** "7.1" → 7.1 ; "13-2" → 13.2 ; sin número → al final. */
    private static double sortKey(String n) {
        try { return Double.parseDouble(n.replace('-', '.').replaceAll("[^0-9.]", "")); } catch (Exception e) { return 1e9; }
    }

    private static String logo(Context c, long id) {
        try (InputStream in = c.getContentResolver().openInputStream(TvContract.buildChannelLogoUri(id))) {
            if (in == null) return "";
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b == null) return "";
            float s = Math.min(1f, 96f / Math.max(b.getWidth(), b.getHeight()));
            return U.png(Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * s)), Math.max(1, (int) (b.getHeight() * s)), true));
        } catch (Exception e) { return ""; }
    }

    static JSONObject message(Context c) {
        JSONObject m = TvService.msg("channelList", "perm", allowed(c), "cur", prefs(c).getLong("curCh", -1));
        try {
            JSONArray chs = new JSONArray();
            for (Ch ch : list(c)) {
                JSONObject o = new JSONObject();
                o.put("id", ch.id); o.put("num", ch.num); o.put("name", ch.name); o.put("logo", logo(c, ch.id));
                chs.put(o);
            }
            m.put("channels", chs);
            JSONArray ins = new JSONArray();
            TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim != null) for (TvInputInfo ti : tim.getTvInputList()) {
                if (!ti.isPassthroughInput()) continue;   // HDMI, AV…
                JSONObject o = new JSONObject();
                o.put("id", ti.getId());
                CharSequence l = ti.loadLabel(c);
                o.put("name", l == null ? "Entrada" : l.toString());
                ins.put(o);
            }
            m.put("inputs", ins);
        } catch (Exception ignored) { }
        return m;
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("tv", Context.MODE_PRIVATE); }

    static void tune(Context c, long id) {
        prefs(c).edit().putLong("curCh", id).apply();
        TvService.startUi(c, new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Canal siguiente / anterior (desde el último que se puso desde aquí). */
    static boolean step(Context c, int d) {
        List<Ch> l = list(c);
        if (l.isEmpty()) return false;
        long cur = prefs(c).getLong("curCh", -1);
        int i = -1;
        for (int k = 0; k < l.size(); k++) if (l.get(k).id == cur) i = k;
        int n = i < 0 ? 0 : (i + d + l.size()) % l.size();
        tune(c, l.get(n).id);
        if (TvService.instance != null) TvService.instance.broadcast(TvService.msg("chanNow", "id", l.get(n).id,
                "num", l.get(n).num, "name", l.get(n).name));
        return true;
    }

    static void input(Context c, String id) {
        Uri u = TvContract.buildChannelUriForPassthroughInput(id);
        TvService.startUi(c, new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
