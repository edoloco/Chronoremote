package cl.chronoremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV (central): recibe a los teléfonos. Varios teléfonos pueden estar conectados a la vez.
 * Mensajes JSON, uno por línea. Latido cada 5 s; 20 s sin noticias = teléfono perdido.
 */
final class TvServer {

    interface Handler { void onMessage(Peer p, JSONObject m); void onPeers(List<Peer> peers); }

    static final class Peer {
        final Socket s;
        final BufferedWriter w;
        final ExecutorService out = Executors.newSingleThreadExecutor();
        volatile String name = "Teléfono";
        Peer(Socket s) throws Exception {
            this.s = s;
            w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }
        void send(JSONObject m) {
            final String line = m.toString();
            out.execute(() -> {
                try { w.write(line); w.write('\n'); w.flush(); } catch (Exception e) { close(); }
            });
        }
        void close() { try { s.close(); } catch (Exception ignored) { } }
    }

    private final Context app;
    private final Handler handler;
    private final android.os.Handler main = new android.os.Handler(Looper.getMainLooper());
    final List<Peer> peers = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;
    private NsdManager.RegistrationListener reg;
    private volatile boolean running;

    TvServer(Context c, Handler h) { app = c.getApplicationContext(); handler = h; }

    void start() {
        if (running) return;
        running = true;
        new Thread(this::acceptLoop, "tv-server").start();
        register();
        main.postDelayed(beat, 5000);
    }

    void stop() {
        running = false;
        main.removeCallbacks(beat);
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        for (Peer p : peers) p.close();
        try { if (reg != null) ((NsdManager) app.getSystemService(Context.NSD_SERVICE)).unregisterService(reg); } catch (Exception ignored) { }
        reg = null;
    }

    private final Runnable beat = new Runnable() {
        @Override public void run() {
            JSONObject hb = new JSONObject();
            try { hb.put("t", "hb"); } catch (Exception ignored) { }
            broadcast(hb);
            if (running) main.postDelayed(this, 5000);
        }
    };

    void broadcast(JSONObject m) { for (Peer p : peers) p.send(m); }

    private void acceptLoop() {
        while (running) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(U.PORT));
                server = ss;
                while (running) {
                    Socket s = ss.accept();
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    s.setSoTimeout(20000);
                    Peer p = new Peer(s);
                    peers.add(p);
                    changed();
                    new Thread(() -> read(p), "tv-peer").start();
                }
            } catch (Exception e) {
                if (!running) return;
                try { Thread.sleep(2000); } catch (Exception ignored) { }
            }
        }
    }

    private void read(Peer p) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(p.s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("{\"t\":\"hb\"")) continue;
                try {
                    JSONObject m = new JSONObject(line);
                    main.post(() -> handler.onMessage(p, m));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        p.close();
        peers.remove(p);
        changed();
    }

    private void changed() { main.post(() -> handler.onPeers(peers)); }

    /** Se anuncia en el WiFi para que los teléfonos la encuentren solos. */
    private void register() {
        NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("ChronoTV " + Build.MODEL);
        info.setServiceType(U.NSD_TYPE);
        info.setPort(U.PORT);
        reg = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo s, int e) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) { }
            @Override public void onServiceRegistered(NsdServiceInfo s) { }
            @Override public void onServiceUnregistered(NsdServiceInfo s) { }
        };
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg); } catch (Exception ignored) { }
    }
}
