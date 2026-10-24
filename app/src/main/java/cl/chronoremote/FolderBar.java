package cl.chronoremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;

/**
 * Barra "carpeta" como la action bar de ChronOS: una franja pegada a un borde y una pestaña más alta con un corte
 * en diagonal (arriba: la pestaña del reloj a la derecha; abajo: la del logo a la izquierda). Verde ChronOS.
 */
final class FolderBar extends FrameLayout {

    static final int GREEN = 0xFF2FA866, GREEN_LIGHT = 0xFF47C87E, GREEN_DARK = 0xFF1E7F4A, ORANGE = 0xFFF57C1F;

    private final boolean top, tabRight;
    private final float strip, tabH, tabW, diag;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG), gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();

    FolderBar(Context c, boolean top, boolean tabRight, float stripDp, float tabHDp, float tabWDp) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        this.top = top;
        this.tabRight = tabRight;
        strip = stripDp * d;
        tabH = tabHDp * d;
        tabW = tabWDp * d;
        diag = 52 * d;
        setWillNotDraw(false);
        setClipChildren(false);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2 * d);
        edge.setColor(GREEN_DARK);
        shadow.setColor(0x01000000);
        shadow.setShadowLayer(14 * d, 0, (top ? 5 : -5) * d, 0x66000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // la sombra difuminada
    }

    float tabW() { return tabW; }

    private float y(float depth) { return top ? depth : getHeight() - depth; }

    @Override protected void onDraw(Canvas c) {
        float W = getWidth();
        shape.reset();
        // franja a lo ancho y pestaña con corte en diagonal (como la barra de ChronOS)
        if (tabRight) {
            shape.moveTo(0, y(0)); shape.lineTo(W, y(0)); shape.lineTo(W, y(tabH));
            shape.lineTo(W - tabW, y(tabH)); shape.lineTo(W - tabW - diag, y(strip)); shape.lineTo(0, y(strip));
        } else {
            shape.moveTo(0, y(0)); shape.lineTo(W, y(0)); shape.lineTo(W, y(strip));
            shape.lineTo(tabW + diag, y(strip)); shape.lineTo(tabW, y(tabH)); shape.lineTo(0, y(tabH));
        }
        shape.close();
        c.drawPath(shape, shadow);
        fill.setShader(new LinearGradient(0, y(0), 0, y(tabH), GREEN_LIGHT, GREEN, Shader.TileMode.CLAMP));
        c.drawPath(shape, fill);
        c.save();
        c.clipPath(shape);
        gloss.setShader(new LinearGradient(0, y(0), 0, y(strip * 1.4f), 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, Math.min(y(0), y(tabH)), W, Math.max(y(0), y(tabH)), gloss);
        c.restore();
        c.drawPath(shape, edge);
    }

    /** Fondo naranjo de ChronOS: degradado con un brillo suave. */
    static Drawable orange() {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), g = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                float W = getBounds().width(), H = getBounds().height();
                p.setShader(new LinearGradient(0, 0, W, H, new int[]{0xFFFFA54A, ORANGE, 0xFFD9481A}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, p);
                g.setShader(new RadialGradient(W * 0.72f, H * 0.3f, Math.max(W, H) * 0.55f, 0x66FFF1C8, 0x00FFF1C8, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, g);
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        };
    }
}
