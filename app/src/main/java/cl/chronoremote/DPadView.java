package cl.chronoremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Cruceta del control: anillo con cuatro flechas y OK al centro. Mantener una flecha la repite. */
public class DPadView extends View {

    interface Sink { void key(String k); }

    private final Sink sink;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), center = new Paint(Paint.ANTI_ALIAS_FLAG),
            arrow = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String pressed;
    private final float d;

    public DPadView(Context c, Sink s) {
        super(c);
        sink = s;
        d = c.getResources().getDisplayMetrics().density;
        ring.setColor(0x22FFFFFF);
        center.setColor(0x33FFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.2f * d);
        edge.setColor(0x44FFFFFF);
        arrow.setColor(U.FG);
        arrow.setStyle(Paint.Style.FILL);
        arrow.setTextAlign(Paint.Align.CENTER);
        arrow.setTextSize(18 * d);
        arrow.setFakeBoldText(true);
    }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        int s = Math.min(w, h == 0 ? w : h);
        setMeasuredDimension(s, s);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), cx = w / 2, cy = getHeight() / 2f, R = w / 2 - 4 * d, r = R * 0.38f;
        c.drawCircle(cx, cy, R, ring);
        c.drawCircle(cx, cy, R, edge);
        if (pressed != null && !"ok".equals(pressed)) {
            float ax = "left".equals(pressed) ? -1 : "right".equals(pressed) ? 1 : 0, ay = "up".equals(pressed) ? -1 : "down".equals(pressed) ? 1 : 0;
            float gx = cx + ax * R * 0.68f, gy = cy + ay * R * 0.68f;
            glow.setShader(new RadialGradient(gx, gy, R * 0.45f, (U.ACCENT & 0x00FFFFFF) | 0x99000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawCircle(gx, gy, R * 0.45f, glow);
        }
        center.setColor("ok".equals(pressed) ? U.ACCENT : 0x33FFFFFF);
        c.drawCircle(cx, cy, r, center);
        c.drawCircle(cx, cy, r, edge);
        c.drawText("OK", cx, cy + arrow.getTextSize() * 0.35f, arrow);
        float a = R * 0.11f, m = R * 0.7f;
        tri(c, cx, cy - m, 0, -1, a);
        tri(c, cx, cy + m, 0, 1, a);
        tri(c, cx - m, cy, -1, 0, a);
        tri(c, cx + m, cy, 1, 0, a);
    }

    private void tri(Canvas c, float x, float y, float dx, float dy, float a) {
        Path p = new Path();
        p.moveTo(x + dx * a, y + dy * a);
        p.lineTo(x - dx * a + dy * a * 1.1f, y - dy * a + dx * a * 1.1f);
        p.lineTo(x - dx * a - dy * a * 1.1f, y - dy * a - dx * a * 1.1f);
        p.close();
        c.drawPath(p, arrow);
    }

    private String which(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, dx = x - cx, dy = y - cy;
        double dist = Math.hypot(dx, dy), R = getWidth() / 2f;
        if (dist > R * 1.05) return null;
        if (dist < R * 0.38) return "ok";
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? "right" : "left";
        return dy > 0 ? "down" : "up";
    }

    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (pressed == null || "ok".equals(pressed)) return;
            sink.key(pressed);
            postDelayed(this, 160);
        }
    };

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = which(e.getX(), e.getY());
                if (pressed == null) return false;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                sink.key(pressed);
                postDelayed(repeat, 450);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(repeat);
                pressed = null;
                invalidate();
                return true;
            default: return true;
        }
    }
}
