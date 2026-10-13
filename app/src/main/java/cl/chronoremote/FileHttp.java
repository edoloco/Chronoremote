package cl.chronoremote;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teléfono: servidor web pequeñito para que la TV baje la foto, el video o la música que se le manda.
 * Cada archivo tiene una dirección secreta; acepta pedidos por partes (para adelantar videos).
 */
final class FileHttp {

    private static ServerSocket server;
    private static final Map<String, Uri> files = new ConcurrentHashMap<>();
    private static final Map<String, String> types = new ConcurrentHashMap<>();
    private static Context app;

    private FileHttp() { }

    /** Dirección para que la TV baje este archivo (null si no se pudo abrir el servidor). */
    static synchronized String share(Context c, Uri uri, String mime, String ip) {
        app = c.getApplicationContext();
        if (server == null) {
            try {
                server = new ServerSocket(0);
                new Thread(FileHttp::loop, "archivos").start();
            } catch (Exception e) { return null; }
        }
        String key = UUID.randomUUID().toString().replace("-", "");
        files.put(key, uri);
        types.put(key, mime == null ? "application/octet-stream" : mime);
        return "http://" + ip + ":" + server.getLocalPort() + "/" + key;
    }

    private static void loop() {
        while (true) {
            try {
                Socket s = server.accept();
                new Thread(() -> serve(s), "archivo").start();
            } catch (Exception e) { return; }
        }
    }

    private static void serve(Socket s) {
        try (Socket sock = s) {
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String req = in.readLine();
            if (req == null) return;
            String[] parts = req.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            boolean head = req.startsWith("HEAD");
            long from = 0, to = -1;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String l = line.toLowerCase();
                if (l.startsWith("range:") && l.contains("bytes=")) {
                    String r = l.substring(l.indexOf("bytes=") + 6).trim();
                    int d = r.indexOf('-');
                    try {
                        from = Long.parseLong(r.substring(0, d));
                        String e = r.substring(d + 1);
                        if (!e.isEmpty()) to = Long.parseLong(e);
                    } catch (Exception ignored) { from = 0; to = -1; }
                }
            }
            String key = path.startsWith("/") ? path.substring(1) : path;
            int q = key.indexOf('?');
            if (q >= 0) key = key.substring(0, q);
            Uri uri = files.get(key);
            OutputStream out = sock.getOutputStream();
            if (uri == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)); return; }
            try (ParcelFileDescriptor pfd = app.getContentResolver().openFileDescriptor(uri, "r")) {
                long size = pfd == null ? -1 : pfd.getStatSize();
                if (pfd == null) return;
                try (InputStream fin = new FileInputStream(pfd.getFileDescriptor())) {
                    boolean partial = from > 0 || to >= 0;
                    if (size >= 0 && (to < 0 || to >= size)) to = size - 1;
                    long len = size >= 0 ? to - from + 1 : -1;
                    StringBuilder hd = new StringBuilder();
                    hd.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
                    hd.append("Content-Type: ").append(types.get(key)).append("\r\n");
                    hd.append("Accept-Ranges: bytes\r\n");
                    if (len >= 0) hd.append("Content-Length: ").append(len).append("\r\n");
                    if (partial && size >= 0) hd.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(size).append("\r\n");
                    hd.append("Connection: close\r\n\r\n");
                    out.write(hd.toString().getBytes(StandardCharsets.ISO_8859_1));
                    if (head) return;
                    long skipped = 0;
                    while (skipped < from) { long k = fin.skip(from - skipped); if (k <= 0) break; skipped += k; }
                    byte[] buf = new byte[65536];
                    long left = len < 0 ? Long.MAX_VALUE : len;
                    int n;
                    while (left > 0 && (n = fin.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                        out.write(buf, 0, n);
                        left -= n;
                    }
                    out.flush();
                }
            }
        } catch (Exception ignored) { }
    }
}
