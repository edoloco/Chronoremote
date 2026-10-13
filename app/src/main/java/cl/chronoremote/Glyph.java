package cl.chronoremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Íconos de línea dibujados a mano (sin imágenes): estilo ChronOS. */
public class Glyph extends View {

    static final int BACK = 0, HOME = 1, POWER = 2, VOL_DOWN = 3, VOL_UP = 4, MUTE = 5, PREV = 6, RW = 7, PLAY = 8, PAUSE = 9,
            FF = 10, NEXT = 11, REMOTE = 12, APPS = 13, SEND = 14, MENU = 15, CLOSE = 16, IMAGE = 17, MUSIC = 18, LINK = 19, KEYBOARD = 20;

    private int kind;
    private final int ink;
    private final Paint st = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public Glyph(Context c, int kind, int ink) {
        super(c);
        this.kind = kind;
        this.ink = ink;
        d = c.getResources().getDisplayMetrics().density;
        st.setStyle(Paint.Style.STROKE);
        st.setStrokeWidth(2.2f * d);
        st.setStrokeCap(Paint.Cap.ROUND);
        st.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    void setKind(int k) { if (k != kind) { kind = k; invalidate(); } }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, u = Math.min(w, h) * 0.18f;
        st.setColor(isEnabled() ? ink : (ink & 0x00FFFFFF) | 0x55000000);
        fill.setColor(st.getColor());
        Path p = new Path();
        switch (kind) {
            case BACK:
                p.moveTo(cx + u * 0.2f, cy - u * 1.1f); p.lineTo(cx - u, cy); p.lineTo(cx + u * 0.2f, cy + u * 1.1f);
                c.drawPath(p, st);
                c.drawLine(cx - u, cy, cx + u * 1.2f, cy, st);
                break;
            case HOME:
                p.moveTo(cx - u * 1.2f, cy - u * 0.1f); p.lineTo(cx, cy - u * 1.2f); p.lineTo(cx + u * 1.2f, cy - u * 0.1f);
                c.drawPath(p, st);
                c.drawRoundRect(new RectF(cx - u * 0.85f, cy - u * 0.25f, cx + u * 0.85f, cy + u * 1.1f), 2 * d, 2 * d, st);
                break;
            case POWER:
                c.drawArc(new RectF(cx - u * 1.1f, cy - u * 1.0f, cx + u * 1.1f, cy + u * 1.2f), -60, 300, false, st);
                c.drawLine(cx, cy - u * 1.3f, cx, cy - u * 0.1f, st);
                break;
            case VOL_DOWN: case VOL_UP: case MUTE:
                p.moveTo(cx - u * 1.3f, cy - u * 0.45f); p.lineTo(cx - u * 0.7f, cy - u * 0.45f); p.lineTo(cx - u * 0.05f, cy - u * 1.05f);
                p.lineTo(cx - u * 0.05f, cy + u * 1.05f); p.lineTo(cx - u * 0.7f, cy + u * 0.45f); p.lineTo(cx - u * 1.3f, cy + u * 0.45f); p.close();
                c.drawPath(p, st);
                if (kind == MUTE) {
                    c.drawLine(cx + u * 0.45f, cy - u * 0.5f, cx + u * 1.35f, cy + u * 0.5f, st);
                    c.drawLine(cx + u * 1.35f, cy - u * 0.5f, cx + u * 0.45f, cy + u * 0.5f, st);
                } else {
                    c.drawLine(cx + u * 0.5f, cy, cx + u * 1.4f, cy, st);
                    if (kind == VOL_UP) c.drawLine(cx + u * 0.95f, cy - u * 0.45f, cx + u * 0.95f, cy + u * 0.45f, st);
                }
                break;
            case PLAY:
                p.moveTo(cx - u * 0.7f, cy - u * 1.1f); p.lineTo(cx + u * 1.1f, cy); p.lineTo(cx - u * 0.7f, cy + u * 1.1f); p.close();
                c.drawPath(p, fill);
                break;
            case PAUSE:
                c.drawRoundRect(new RectF(cx - u * 0.9f, cy - u, cx - u * 0.25f, cy + u), d, d, fill);
                c.drawRoundRect(new RectF(cx + u * 0.25f, cy - u, cx + u * 0.9f, cy + u), d, d, fill);
                break;
            case PREV: case NEXT: {
                float s = kind == NEXT ? 1 : -1;
                p.moveTo(cx - s * u * 0.9f, cy - u); p.lineTo(cx + s * u * 0.5f, cy); p.lineTo(cx - s * u * 0.9f, cy + u); p.close();
                c.drawPath(p, fill);
                c.drawLine(cx + s * u * 0.9f, cy - u, cx + s * u * 0.9f, cy + u, st);
                break;
            }
            case RW: case FF: {
                float s = kind == FF ? 1 : -1;
                for (int k = 0; k < 2; k++) {
                    Path t = new Path();
                    float o = (k == 0 ? -0.9f : 0.1f) * u * s;
                    t.moveTo(cx + o, cy - u * 0.8f); t.lineTo(cx + o + s * u * 0.9f, cy); t.lineTo(cx + o, cy + u * 0.8f); t.close();
                    c.drawPath(t, fill);
                }
                break;
            }
            case REMOTE:
                c.drawRoundRect(new RectF(cx - u * 0.7f, cy - u * 1.3f, cx + u * 0.7f, cy + u * 1.3f), 4 * d, 4 * d, st);
                c.drawCircle(cx, cy - u * 0.5f, u * 0.32f, st);
                c.drawCircle(cx - u * 0.3f, cy + u * 0.45f, 1.3f * d, fill);
                c.drawCircle(cx + u * 0.3f, cy + u * 0.45f, 1.3f * d, fill);
                c.drawCircle(cx, cy + u * 0.85f, 1.3f * d, fill);
                break;
            case APPS:
                for (int i = -1; i <= 1; i += 2) for (int j = -1; j <= 1; j += 2)
                    c.drawRoundRect(new RectF(cx + i * u * 0.6f - u * 0.45f, cy + j * u * 0.6f - u * 0.45f,
                            cx + i * u * 0.6f + u * 0.45f, cy + j * u * 0.6f + u * 0.45f), 2 * d, 2 * d, st);
                break;
            case SEND:   // TV con una flecha que entra
                c.drawRoundRect(new RectF(cx - u * 1.3f, cy - u * 0.9f, cx + u * 1.3f, cy + u * 0.8f), 3 * d, 3 * d, st);
                c.drawLine(cx - u * 0.5f, cy + u * 1.25f, cx + u * 0.5f, cy + u * 1.25f, st);
                c.drawLine(cx, cy - u * 0.5f, cx, cy + u * 0.35f, st);
                p.moveTo(cx - u * 0.4f, cy); p.lineTo(cx, cy + u * 0.4f); p.lineTo(cx + u * 0.4f, cy);
                c.drawPath(p, st);
                break;
            case MENU:
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * u * 0.75f, cy, 2.2f * d, fill);
                break;
            case CLOSE:
                c.drawLine(cx - u * 0.8f, cy - u * 0.8f, cx + u * 0.8f, cy + u * 0.8f, st);
                c.drawLine(cx + u * 0.8f, cy - u * 0.8f, cx - u * 0.8f, cy + u * 0.8f, st);
                break;
            case IMAGE:
                c.drawRoundRect(new RectF(cx - u * 1.3f, cy - u, cx + u * 1.3f, cy + u), 3 * d, 3 * d, st);
                p.moveTo(cx - u * 1.3f, cy + u * 0.7f); p.lineTo(cx - u * 0.3f, cy - u * 0.15f); p.lineTo(cx + u * 0.4f, cy + u * 0.5f); p.lineTo(cx + u * 1.3f, cy - u * 0.2f);
                c.drawPath(p, st);
                c.drawCircle(cx + u * 0.55f, cy - u * 0.45f, 2 * d, fill);
                break;
            case MUSIC:
                c.drawLine(cx + u * 0.6f, cy - u * 1.2f, cx + u * 0.6f, cy + u * 0.6f, st);
                c.drawLine(cx + u * 0.6f, cy - u * 1.2f, cx + u * 1.3f, cy - u * 0.8f, st);
                c.drawCircle(cx + u * 0.05f, cy + u * 0.7f, u * 0.55f, fill);
                break;
            case LINK:
                c.save();
                c.rotate(-45, cx, cy);
                c.drawRoundRect(new RectF(cx - u * 1.4f, cy - u * 0.5f, cx + u * 0.1f, cy + u * 0.5f), u * 0.5f, u * 0.5f, st);
                c.drawRoundRect(new RectF(cx - u * 0.1f, cy - u * 0.5f, cx + u * 1.4f, cy + u * 0.5f), u * 0.5f, u * 0.5f, st);
                c.restore();
                break;
            case KEYBOARD:
                c.drawRoundRect(new RectF(cx - u * 1.4f, cy - u * 0.85f, cx + u * 1.4f, cy + u * 0.85f), 3 * d, 3 * d, st);
                for (int i = -2; i <= 2; i++) c.drawCircle(cx + i * u * 0.5f, cy - u * 0.3f, 1.3f * d, fill);
                c.drawLine(cx - u * 0.7f, cy + u * 0.35f, cx + u * 0.7f, cy + u * 0.35f, st);
                break;
            default: break;
        }
    }
}
