package cl.chronoremote;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Accesibilidad de la TV (opcional): permite que el teléfono use Atrás, Inicio, las flechas, OK y escriba en
 * cualquier app. Las flechas mueven el foco de la app al frente (como el control remoto). No lee ni guarda nada.
 */
public class TvAccess extends AccessibilityService {

    static volatile TvAccess instance;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (TvService.instance != null) TvService.instance.stateChanged();
    }

    @Override public boolean onUnbind(android.content.Intent i) {
        if (instance == this) instance = null;
        if (TvService.instance != null) TvService.instance.stateChanged();
        return super.onUnbind(i);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    void home() { performGlobalAction(GLOBAL_ACTION_HOME); }

    boolean nav(String k) {
        switch (k) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "sleep": return performGlobalAction(8 /* GLOBAL_ACTION_LOCK_SCREEN: apaga la pantalla */);
            case "settings": return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            case "up": return move(View.FOCUS_UP);
            case "down": return move(View.FOCUS_DOWN);
            case "left": return move(View.FOCUS_LEFT);
            case "right": return move(View.FOCUS_RIGHT);
            case "ok": return click();
            default: return false;
        }
    }

    private AccessibilityNodeInfo focused() {
        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) f = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        return f;
    }

    /** Mueve el foco como lo haría la flecha del control remoto. */
    private boolean move(int dir) {
        AccessibilityNodeInfo f = focused();
        if (f == null) {
            // sin foco: se enfoca lo primero que se pueda enfocar
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo first = root == null ? null : firstFocusable(root, 0);
            return first != null && first.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        AccessibilityNodeInfo n = f.focusSearch(dir);
        if (n == null) {
            // sin vecino en esa dirección: se desplaza la lista que lo contiene
            AccessibilityNodeInfo p = f.getParent();
            int act = dir == View.FOCUS_DOWN || dir == View.FOCUS_RIGHT ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            for (int i = 0; p != null && i < 8; i++, p = p.getParent()) if (p.isScrollable()) return p.performAction(act);
            return false;
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
    }

    private AccessibilityNodeInfo firstFocusable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isFocusable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstFocusable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private boolean click() {
        AccessibilityNodeInfo f = focused();
        for (int i = 0; f != null && i < 6; i++, f = f.getParent())
            if (f.isClickable() && f.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return false;
    }

    /** Escribe en el campo de texto que tiene el foco (reemplaza lo escrito). enter = buscar/enviar. */
    boolean typeText(String s, boolean enter) {
        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null || !f.isEditable()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            f = root == null ? null : firstEditable(root, 0);
            if (f == null) return false;
            f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
        boolean ok = f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        if (enter && android.os.Build.VERSION.SDK_INT >= 30) {
            try { f.performAction(0x01020054 /* ACTION_IME_ENTER */); } catch (Exception ignored) { }
        }
        return ok;
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstEditable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }
}
