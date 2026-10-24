package cl.chronoremote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * TV: muestra lo que manda un teléfono (foto, video o música). Se controla desde el teléfono
 * (pausa, adelantar, retroceder, cerrar) o con el control de la TV. Avisa al teléfono cómo va.
 *   view (teléfono → TV) {a: toggle | seek (ms, relativo) | to (0..1) | close}
 *   viewState (TV → teléfonos) {on, kind, title, pos, dur, play}
 */
public class ViewerActivity extends Activity {

    static volatile ViewerActivity instance;
    private final Handler h = new Handler(Looper.getMainLooper());
    private VideoView video;
    private ImageView image;
    private TextView title;
    private String kind = "", name = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        title = U.text(this, "", 20, U.FG, true);
        title.setPadding(U.dp(this, 22), U.dp(this, 12), U.dp(this, 22), U.dp(this, 12));
        title.setBackground(U.round(0xAA000000, U.dp(this, 24)));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.setMargins(U.dp(this, 36), U.dp(this, 28), 0, 0);
        root.addView(title, tl);
        setContentView(root);
        instance = this;
        show(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); show(i); }

    private void show(Intent i) {
        kind = i.getStringExtra("kind");
        if (kind == null) kind = "";
        name = i.getStringExtra("title");
        if (name == null) name = "";
        String url = i.getStringExtra("url");
        title.setText(("image".equals(kind) ? "🖼  " : "audio".equals(kind) ? "♪  " : "▶  ") + name);
        title.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);
        h.removeCallbacks(hideTitle);
        h.postDelayed(hideTitle, 4000);
        if ("image".equals(kind)) {
            video.stopPlayback();
            video.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            image.setImageDrawable(null);
            new Thread(() -> {
                Bitmap bm = load(url);
                h.post(() -> { if (bm != null) image.setImageBitmap(bm); else fail(); });
            }).start();
        } else {
            image.setVisibility("audio".equals(kind) ? View.VISIBLE : View.GONE);
            if ("audio".equals(kind)) image.setImageResource(getResources().getIdentifier("chronos", "drawable", getPackageName()));
            video.setVisibility(View.VISIBLE);
            video.setVideoURI(Uri.parse(url));
            video.setOnPreparedListener(MediaPlayer::start);
            video.setOnErrorListener((mp, w, x) -> { fail(); return true; });
            video.setOnCompletionListener(mp -> report());
            video.requestFocus();
        }
        h.removeCallbacks(tick);
        h.post(tick);
    }

    private void fail() {
        if (TvService.instance != null) TvService.instance.broadcast(TvService.msg("toast", "msg", "La TV no pudo abrir lo que mandaste"));
    }

    private final Runnable hideTitle = () -> title.animate().alpha(0f).setDuration(400).start();

    private Bitmap load(String url) {
        if (url != null && url.startsWith("file:")) {   // recibido y guardado en la TV
            try {
                String p = Uri.parse(url).getPath();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(p, o);
                int s = 1;
                while (o.outWidth / s > 3840 || o.outHeight / s > 2160) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                return BitmapFactory.decodeFile(p, o);
            } catch (Exception e) { return null; }
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            try (InputStream in = c.getInputStream()) {
                byte[] all = readAll(in);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(all, 0, all.length, o);
                int s = 1;
                while (o.outWidth / s > 3840 || o.outHeight / s > 2160) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                return BitmapFactory.decodeByteArray(all, 0, all.length, o);
            }
        } catch (Exception e) { return null; }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** Cada segundo avisa a los teléfonos cómo va (para su barra de avance). */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            report();
            h.postDelayed(this, 1000);
        }
    };

    private void report() {
        if (TvService.instance == null) return;
        boolean media = !"image".equals(kind);
        TvService.instance.broadcast(TvService.msg("viewState", "on", true, "kind", kind, "title", name,
                "pos", media ? video.getCurrentPosition() : 0, "dur", media ? Math.max(0, video.getDuration()) : 0,
                "play", media && video.isPlaying()));
    }

    static void command(JSONObject m) {
        ViewerActivity a = instance;
        if (a == null) return;
        a.h.post(() -> {
            switch (m.optString("a")) {
                case "toggle": if (a.video.isPlaying()) a.video.pause(); else a.video.start(); break;
                case "seek": a.video.seekTo(Math.max(0, a.video.getCurrentPosition() + m.optInt("ms"))); break;
                case "to": a.video.seekTo((int) (m.optDouble("f") * Math.max(0, a.video.getDuration()))); break;
                case "close": a.finish(); return;
                default: break;
            }
            a.report();
        });
    }

    void mediaKey(int code) {
        h.post(() -> {
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { if (video.isPlaying()) video.pause(); else video.start(); }
            else if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_NEXT) video.seekTo(video.getCurrentPosition() + 10000);
            else if (code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) video.seekTo(Math.max(0, video.getCurrentPosition() - 10000));
            else if (code == KeyEvent.KEYCODE_MEDIA_STOP) finish();
            report();
        });
    }

    /** Flechas y OK desde el teléfono: OK pausa, izquierda/derecha retroceden o adelantan, Atrás cierra. */
    boolean key(String k) {
        switch (k) {
            case "ok": mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return true;
            case "left": mediaKey(KeyEvent.KEYCODE_MEDIA_REWIND); return true;
            case "right": mediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); return true;
            case "back": h.post(this::finish); return true;
            default: return false;
        }
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) { mediaKey(KeyEvent.KEYCODE_MEDIA_REWIND); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { mediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); return true; }
        return super.onKeyDown(code, e);
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { video.stopPlayback(); } catch (Exception ignored) { }
        if (instance == this) instance = null;
        if (TvService.instance != null) TvService.instance.broadcast(TvService.msg("viewState", "on", false));
        super.onDestroy();
    }
}
