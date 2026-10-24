package cl.chronoremote;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * TV: recibe fotos, videos y música que manda un teléfono (el teléfono los EMPUJA a la TV, así no depende de que la
 * TV pueda entrar al teléfono). Se guardan en la caché de la TV y se abren en ViewerActivity.
 *   Puerto 47831. Encabezado: una línea JSON {kind, title, name, size}; después, los bytes.
 *   Respuesta: una línea JSON {ok} (o {ok:false, err}).
 */
final class FileRecv {

    static final int PORT = U.PORT + 1;
    private static ServerSocket server;

    private FileRecv() { }

    static synchronized void start(Context c) {
        if (server != null) return;
        final Context app = c.getApplicationContext();
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT));
            server = ss;
        } catch (Exception e) { return; }
        new Thread(() -> {
            while (server != null) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> receive(app, s), "recibir").start();
                } catch (Exception e) { break; }
            }
        }, "recibir-archivos").start();
    }

    private static String line(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') { b.write(c); if (b.size() > 65536) throw new Exception("encabezado"); }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void receive(Context app, Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            JSONObject h = new JSONObject(line(in));
            String kind = h.optString("kind"), name = new File(h.optString("name", "archivo")).getName();
            long size = h.optLong("size", -1);
            File dir = new File(app.getCacheDir(), "recibidos");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            // se guardan solo los últimos (no llenar la TV)
            File[] old = dir.listFiles();
            if (old != null && old.length > 6) {
                java.util.Arrays.sort(old, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < old.length - 6; i++) //noinspection ResultOfMethodCallIgnored
                    old[i].delete();
            }
            File f = new File(dir, System.currentTimeMillis() + "_" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
            TvActivity.notice("Recibiendo " + h.optString("title", name) + "…");
            long got = 0;
            try (OutputStream fo = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((size < 0 || got < size) && (n = in.read(buf, 0, (int) (size < 0 ? buf.length : Math.min(buf.length, size - got)))) > 0) {
                    fo.write(buf, 0, n);
                    got += n;
                }
            }
            if (size >= 0 && got < size) { //noinspection ResultOfMethodCallIgnored
                f.delete(); out.write("{\"ok\":false,\"err\":\"se cortó\"}\n".getBytes(StandardCharsets.UTF_8)); return; }
            out.write("{\"ok\":true}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            TvService.startUi(app, new Intent(app, ViewerActivity.class).putExtra("url", Uri.fromFile(f).toString()).putExtra("kind", kind)
                    .putExtra("title", h.optString("title", name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) { }
    }
}
