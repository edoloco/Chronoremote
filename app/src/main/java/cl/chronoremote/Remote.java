package cl.chronoremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Teléfono (extensión): busca la TV en el WiFi, se conecta y le manda órdenes.
 * Recuerda la última TV para reconectarse rápido; también se puede escribir su IP a mano.
 */
final class Remote {

    interface Listener { void onMessage(JSONObject m); void onState(boolean connected, String info); }

    private static Remote inst;
    static synchronized Remote get(Context c) { if (inst == null) inst = new Remote(c.getApplicationContext()); return inst; }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final NsdManager nsd;
    private WifiManager.MulticastLock lock;
    private NsdManager.DiscoveryListener disc;
    private volatile Socket sock;
    private volatile BufferedWriter w;
    private volatile boolean started, connecting, resolving;
    volatile String tvName = "", info = "Buscando la TV…";
    /** Último estado de la TV (permisos, volumen…). */
    volatile JSONObject tv = new JSONObject();

    private Remote(Context c) {
        app = c;
        prefs = c.getSharedPreferences("remote", Context.MODE_PRIVATE);
        nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
    }

    void add(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void remove(Listener l) { listeners.remove(l); }

    boolean connected() { return sock != null; }

    void start() {
        if (started) return;
        started = true;
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { lock = wm.createMulticastLock("chronoremote"); lock.setReferenceCounted(false); lock.acquire(); }
        } catch (Exception ignored) { }
        main.postDelayed(beat, 5000);
        search();
    }

    /** IP escrita a mano (vacía = buscar sola). */
    void setManualIp(String ip) {
        prefs.edit().putString("manualIp", ip == null ? "" : ip.trim()).apply();
        drop(sock);
        search();
    }

    String manualIp() { return prefs.getString("manualIp", ""); }

    private void search() {
        if (sock != null) return;
        state(false, "Buscando la TV…");
        String manual = manualIp(), last = prefs.getString("lastIp", "");
        if (!manual.isEmpty()) { connect(manual); return; }
        if (!last.isEmpty()) connect(last);
        discover();
        main.removeCallbacks(retry);
        main.postDelayed(retry, 6000);
    }

    private final Runnable retry = () -> { if (sock == null && started) { stopDiscovery(); search(); } };

    private void discover() {
        if (nsd == null || disc != null || !manualIp().isEmpty()) return;
        disc = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { disc = null; }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                if (sock != null || resolving || s.getServiceType() == null || !s.getServiceType().contains("chronoremote")) return;
                resolving = true;
                try {
                    nsd.resolveService(s, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo i, int e) { resolving = false; }
                        @Override public void onServiceResolved(NsdServiceInfo i) {
                            resolving = false;
                            InetAddress a = i.getHost();
                            if (a != null && sock == null) connect(a.getHostAddress());
                        }
                    });
                } catch (Exception e) { resolving = false; }
            }
        };
        try { nsd.discoverServices(U.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, disc); } catch (Exception e) { disc = null; }
    }

    private void stopDiscovery() {
        if (disc != null) { try { nsd.stopServiceDiscovery(disc); } catch (Exception ignored) { } disc = null; }
        resolving = false;
    }

    private void connect(String ip) {
        if (connecting || sock != null) return;
        connecting = true;
        new Thread(() -> {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(ip, U.PORT), 3000);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                s.setSoTimeout(20000);
                w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                sock = s;
                connecting = false;
                prefs.edit().putString("lastIp", ip).apply();
                main.post(this::stopDiscovery);
                send("hello", "name", Build.MANUFACTURER + " " + Build.MODEL);
                state(true, "Conectado");
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("{\"t\":\"hb\"")) continue;
                    try {
                        JSONObject m = new JSONObject(line);
                        if ("tv".equals(m.optString("t"))) { tv = m; tvName = m.optString("name"); }
                        main.post(() -> { for (Listener l : listeners) l.onMessage(m); });
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) {
            } finally {
                connecting = false;
                drop(s);
            }
        }, "remote-link").start();
    }

    private void drop(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) { }
        if (sock == s) {
            sock = null;
            w = null;
            state(false, "Se perdió la TV. Buscando…");
            main.postDelayed(this::search, 1500);
        }
    }

    private void state(boolean on, String i) {
        info = i;
        main.post(() -> { for (Listener l : listeners) l.onState(on, i); });
    }

    private final Runnable beat = new Runnable() {
        @Override public void run() {
            if (sock != null) send("hb");
            main.postDelayed(this, 5000);
        }
    };

    void send(JSONObject m) {
        final String line = m.toString();
        writer.execute(() -> {
            BufferedWriter ww = w;
            if (ww == null) return;
            try { ww.write(line); ww.write('\n'); ww.flush(); } catch (Exception e) { drop(sock); }
        });
    }

    void send(String type, Object... kv) {
        JSONObject m = new JSONObject();
        try {
            m.put("t", type);
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        } catch (Exception ignored) { }
        send(m);
    }

    /** IP de este teléfono en la red de la TV (la que usa la conexión). */
    String localIp() {
        Socket s = sock;
        if (s != null && s.getLocalAddress() != null) return s.getLocalAddress().getHostAddress();
        return myIp();
    }

    /** IP de este teléfono en el WiFi (para que la TV baje los archivos que se le mandan). */
    static String myIp() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }
}
