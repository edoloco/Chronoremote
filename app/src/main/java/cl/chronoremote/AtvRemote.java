package cl.chronoremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * Control TOTAL de la TV desde el teléfono, con el mismo protocolo que usa la app oficial "Google TV" (Android TV
 * Remote v2, servicio que viene en toda TV con Google TV / Android TV). Así las teclas llegan al sistema de la TV y
 * funcionan en CUALQUIER app (YouTube, Netflix, ajustes…), sin permisos especiales en la TV.
 *
 *  - Vincular (una vez): puerto 6467 con TLS y un certificado propio del teléfono; la TV muestra un código de 6
 *    letras/números y el teléfono lo confirma (hash SHA-256 de las dos llaves + el código).
 *  - Controlar: puerto 6466 con el mismo certificado; mensajes protobuf con su largo adelante (varint).
 * Los mensajes protobuf se arman a mano (sin librerías): solo los campos que hacen falta.
 */
final class AtvRemote {

    interface Listener { void onAtvState(boolean connected, String info); }

    private static final String ALIAS = "chronoremote-atv";
    static final int PAIR_PORT = 6467, REMOTE_PORT = 6466;
    /** Funciones pedidas a la TV (ping, teclas, IME, encendido, volumen, abrir enlaces): el valor que usa la app oficial. */
    private static final int FEATURES = 622;

    private static AtvRemote inst;
    static synchronized AtvRemote get(Context c) { if (inst == null) inst = new AtvRemote(c.getApplicationContext()); return inst; }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService out = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile SSLSocket sock;
    private volatile OutputStream os;
    private volatile boolean running, ready;
    private volatile String ip;
    private int imeCounter = -1, fieldCounter = -1;
    volatile String info = "Sin vincular";

    private AtvRemote(Context c) {
        app = c;
        prefs = c.getSharedPreferences("atv", Context.MODE_PRIVATE);
    }

    void add(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void remove(Listener l) { listeners.remove(l); }
    boolean ready() { return ready; }
    boolean paired(String tvIp) { return tvIp != null && prefs.getBoolean("paired:" + tvIp, false); }

    private void state(boolean ok, String i) {
        ready = ok;
        info = i;
        main.post(() -> { for (Listener l : listeners) l.onAtvState(ok, i); });
    }

    // ---------------- certificado del teléfono (se crea una vez y queda en el almacén seguro de Android) ----------------

    private KeyStore keyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            Calendar from = Calendar.getInstance(), to = Calendar.getInstance();
            from.add(Calendar.DAY_OF_YEAR, -1);
            to.add(Calendar.YEAR, 20);
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY | KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_MD5, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA224,
                            KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1, KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(false)
                    .setCertificateSubject(new X500Principal("CN=atvremote/" + Build.MODEL.replaceAll("[^A-Za-z0-9 ]", "")))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(from.getTime())
                    .setCertificateNotAfter(to.getTime())
                    .build());
            g.generateKeyPair();
        }
        return ks;
    }

    /** Conexión TLS que presenta el certificado del teléfono y acepta el de la TV (es propio, sin autoridad). */
    private SSLSocket open(String host, int port) throws Exception {
        KeyStore ks = keyStore();
        final PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        final Certificate[] chain = ks.getCertificateChain(ALIAS);
        final X509Certificate[] x = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) x[i] = (X509Certificate) chain[i];
        KeyManager km = new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String t, Principal[] p) { return new String[]{ALIAS}; }
            @Override public String chooseClientAlias(String[] t, Principal[] p, Socket s) { return ALIAS; }
            @Override public String[] getServerAliases(String t, Principal[] p) { return null; }
            @Override public String chooseServerAlias(String t, Principal[] p, Socket s) { return null; }
            @Override public X509Certificate[] getCertificateChain(String a) { return x; }
            @Override public PrivateKey getPrivateKey(String a) { return key; }
        };
        TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
            @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{km}, new TrustManager[]{tm}, null);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 4000);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
        s.setUseClientMode(true);
        s.setSoTimeout(15000);
        s.startHandshake();
        return s;
    }

    // ---------------- vincular ----------------

    private SSLSocket pairSock;

    /** Paso 1: pide vincular. La TV muestra un código. Corre en un hilo aparte; avisa por 'done' (null = bien, o el error). */
    void startPairing(String host, java.util.function.Consumer<String> done) {
        new Thread(() -> {
            String err = null;
            try {
                closePair();
                pairSock = open(host, PAIR_PORT);
                OutputStream o = pairSock.getOutputStream();
                InputStream in = pairSock.getInputStream();
                // PairingMessage{protocol_version=2, status=200, pairing_request(10){service_name=1, client_name=2}}
                write(o, pairing(new Pb().str(1, "atvremote").str(2, "Chrono Remote " + Build.MODEL), 10));
                checkStatus(read(in));
                // pairing_option(20): entrada con código hexadecimal de 6, rol "entrada"
                Pb enc = new Pb().num(1, 3).num(2, 6);
                write(o, pairing(new Pb().msg(1, enc).num(3, 1), 20));
                checkStatus(read(in));
                // pairing_configuration(30): encoding + client_role=entrada
                write(o, pairing(new Pb().msg(1, new Pb().num(1, 3).num(2, 6)).num(2, 1), 30));
                checkStatus(read(in));   // ahora la TV muestra el código
                ip = host;
            } catch (Exception e) {
                err = "No se pudo conectar con la TV para vincular (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")";
                closePair();
            }
            final String fe = err;
            main.post(() -> done.accept(fe));
        }, "atv-vincular").start();
    }

    /** Paso 2: el código que muestra la TV (6 caracteres). */
    void finishPairing(String code, java.util.function.Consumer<String> done) {
        new Thread(() -> {
            String err = null;
            try {
                String c = code.trim().toUpperCase().replaceAll("[^0-9A-F]", "");
                if (c.length() != 6) throw new IOException("El código tiene 6 letras o números (0-9, A-F)");
                RSAPublicKey mine = (RSAPublicKey) keyStore().getCertificate(ALIAS).getPublicKey();
                RSAPublicKey tv = (RSAPublicKey) pairSock.getSession().getPeerCertificates()[0].getPublicKey();
                MessageDigest h = MessageDigest.getInstance("SHA-256");
                h.update(unsigned(mine.getModulus()));
                h.update(unsigned(mine.getPublicExponent()));
                h.update(unsigned(tv.getModulus()));
                h.update(unsigned(tv.getPublicExponent()));
                h.update(hex(c.substring(2)));
                byte[] secret = h.digest();
                if ((secret[0] & 0xFF) != Integer.parseInt(c.substring(0, 2), 16)) throw new IOException("El código no es correcto: revísalo en la TV");
                OutputStream o = pairSock.getOutputStream();
                write(o, pairing(new Pb().bytes(1, secret), 40));
                checkStatus(read(pairSock.getInputStream()));
                prefs.edit().putBoolean("paired:" + ip, true).apply();
                closePair();
                main.post(() -> start(ip));
            } catch (Exception e) {
                err = e instanceof IOException && e.getMessage() != null ? e.getMessage() : "No se pudo vincular: " + e;
            }
            final String fe = err;
            main.post(() -> done.accept(fe));
        }, "atv-codigo").start();
    }

    private void closePair() { try { if (pairSock != null) pairSock.close(); } catch (Exception ignored) { } pairSock = null; }

    private static byte[] pairing(Pb inner, int field) {
        return new Pb().num(1, 2).num(2, 200).msg(field, inner).toBytes();
    }

    private static void checkStatus(byte[] m) throws IOException {
        long status = Pb.firstVarint(m, 2);
        if (status != 200) throw new IOException(status == 402 ? "Código incorrecto" : status == 401 ? "La TV no aceptó la configuración" : "La TV respondió con error " + status);
    }

    private static byte[] unsigned(BigInteger b) {
        byte[] a = b.toByteArray();
        if (a.length > 1 && a[0] == 0) return java.util.Arrays.copyOfRange(a, 1, a.length);
        return a;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    // ---------------- controlar ----------------

    /** Se conecta (y se reconecta solo) a la TV ya vinculada. */
    synchronized void start(String host) {
        if (host == null || !paired(host)) return;
        if (running && host.equals(ip)) return;
        stop();
        ip = host;
        running = true;
        new Thread(this::loop, "atv-control").start();
    }

    synchronized void stop() {
        running = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        sock = null;
        os = null;
    }

    private void loop() {
        int fails = 0;
        while (running) {
            try {
                state(false, "Conectando el control total…");
                SSLSocket s = open(ip, REMOTE_PORT);
                s.setSoTimeout(0);
                sock = s;
                os = s.getOutputStream();
                InputStream in = s.getInputStream();
                fails = 0;
                while (running) handle(read(in));
            } catch (javax.net.ssl.SSLException e) {
                // la TV olvidó el certificado (se restauró o se borró): hay que vincular de nuevo
                if (++fails >= 3) { prefs.edit().putBoolean("paired:" + ip, false).apply(); state(false, "Hay que vincular de nuevo"); running = false; break; }
            } catch (Exception ignored) {
                fails++;
            }
            ready = false;
            os = null;
            if (!running) break;
            state(false, "Sin control total (reintentando…)");
            try { Thread.sleep(Math.min(10000, 1500L * fails)); } catch (InterruptedException e) { break; }
        }
        ready = false;
    }

    private void handle(byte[] m) {
        List<long[]> f = Pb.fields(m);
        for (long[] x : f) {
            int field = (int) x[0];
            switch (field) {
                case 1:   // remote_configure: la TV pregunta quién somos
                    send(new Pb().msg(1, new Pb().num(1, FEATURES).msg(2, new Pb().str(1, Build.MODEL).str(2, Build.MANUFACTURER)
                            .num(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0"))).toBytes());
                    break;
                case 2:   // remote_set_active: quedamos activos
                    send(new Pb().msg(2, new Pb().num(1, FEATURES)).toBytes());
                    state(true, "Control total de la TV");
                    break;
                case 8: { // remote_ping_request: hay que contestar con el mismo valor
                    byte[] ping = Pb.firstBytes(m, 8);
                    long v = ping == null ? 0 : Pb.firstVarint(ping, 1);
                    send(new Pb().msg(9, new Pb().num(1, v)).toBytes());
                    break;
                }
                case 22: { // remote_ime_show_request: la TV abrió su teclado (campo de texto activo)
                    byte[] e = Pb.firstBytes(m, 22);
                    byte[] st = e == null ? null : Pb.firstBytes(e, 2);
                    if (st != null) { long fc = Pb.firstVarint(st, 1); if (fc >= 0) fieldCounter = (int) fc; if (imeCounter < 0) imeCounter = 0; }
                    break;
                }
                case 21: { // remote_ime_batch_edit: contadores del campo de texto de la TV
                    byte[] e = Pb.firstBytes(m, 21);
                    if (e != null) { imeCounter = (int) Pb.firstVarint(e, 1); fieldCounter = (int) Pb.firstVarint(e, 2); }
                    break;
                }
                default: break;
            }
        }
    }

    private void send(byte[] msg) {
        out.execute(() -> {
            OutputStream o = os;
            if (o == null) return;
            try { write(o, msg); } catch (Exception e) { try { if (sock != null) sock.close(); } catch (Exception ignored) { } }
        });
    }

    /** Una tecla de Android (KeyEvent.KEYCODE_…): llega al sistema de la TV, en cualquier app. */
    boolean key(int code) {
        if (!ready) return false;
        send(new Pb().msg(10, new Pb().num(1, code).num(2, 3 /* SHORT */)).toBytes());
        return true;
    }

    /** Tecla mantenida (por ejemplo, Inicio largo). */
    boolean longKey(int code) {
        if (!ready) return false;
        send(new Pb().msg(10, new Pb().num(1, code).num(2, 1)).toBytes());
        send(new Pb().msg(10, new Pb().num(1, code).num(2, 2)).toBytes());
        return true;
    }

    /** Abre un enlace en la TV (https://…, o market://launch?id=paquete para abrir una app). */
    boolean openLink(String link) {
        if (!ready) return false;
        send(new Pb().msg(90, new Pb().str(1, link)).toBytes());
        return true;
    }

    /** Escribe en el campo de texto que esté abierto en la TV (búsquedas). */
    boolean text(String s) {
        if (!ready || s.isEmpty()) return false;
        if (imeCounter >= 0) {   // como la app oficial: el texto completo de una vez
            send(new Pb().msg(21, new Pb().num(1, imeCounter).num(2, fieldCounter)
                    .msg(3, new Pb().num(1, 1).msg(2, new Pb().num(1, s.length() - 1).num(2, s.length() - 1).str(3, s)))).toBytes());
            return true;
        }
        // sin campo informado: letra por letra con teclas
        for (char ch : s.toCharArray()) {
            int code = charKey(ch);
            if (code == 0) continue;
            boolean upper = Character.isUpperCase(ch);
            if (upper) send(new Pb().msg(10, new Pb().num(1, 59).num(2, 1)).toBytes());
            send(new Pb().msg(10, new Pb().num(1, code).num(2, 3)).toBytes());
            if (upper) send(new Pb().msg(10, new Pb().num(1, 59).num(2, 2)).toBytes());
        }
        return true;
    }

    private static int charKey(char ch) {
        char c = Character.toLowerCase(ch);
        if (c >= 'a' && c <= 'z') return 29 + (c - 'a');
        if (c >= '0' && c <= '9') return 7 + (c - '0');
        switch (c) {
            case ' ': return 62; case '.': return 56; case ',': return 55; case '-': return 69; case '@': return 77;
            case '/': return 76; case '\n': return 66;
            default: return 0;
        }
    }

    // ---------------- protobuf mínimo ----------------

    /** Escribe un mensaje con su largo adelante (varint). */
    private static void write(OutputStream o, byte[] msg) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        Pb.varint(b, msg.length);
        b.write(msg);
        o.write(b.toByteArray());
        o.flush();
    }

    private static byte[] read(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int c = in.read();
            if (c < 0) throw new IOException("La TV cerró la conexión");
            len |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) throw new IOException("Mensaje inválido");
        }
        if (len > 1 << 20) throw new IOException("Mensaje demasiado grande");
        byte[] b = new byte[(int) len];
        int got = 0;
        while (got < len) {
            int n = in.read(b, got, (int) len - got);
            if (n < 0) throw new IOException("La TV cerró la conexión");
            got += n;
        }
        return b;
    }

    /** Armador y lector de protobuf (solo varint y largo-delimitado). */
    static final class Pb {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream();

        static void varint(ByteArrayOutputStream o, long v) {
            while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
            o.write((int) v);
        }

        Pb num(int field, long v) { varint(b, (long) field << 3); varint(b, v); return this; }
        Pb bytes(int field, byte[] v) { varint(b, ((long) field << 3) | 2); varint(b, v.length); b.write(v, 0, v.length); return this; }
        Pb str(int field, String s) { return bytes(field, s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        Pb msg(int field, Pb m) { return bytes(field, m.toBytes()); }
        byte[] toBytes() { return b.toByteArray(); }

        /** Campos de primer nivel: {número, tipo}. */
        static List<long[]> fields(byte[] m) {
            List<long[]> out = new ArrayList<>();
            int[] p = {0};
            try {
                while (p[0] < m.length) {
                    long key = readVar(m, p);
                    int f = (int) (key >>> 3), t = (int) (key & 7);
                    out.add(new long[]{f, t});
                    skip(m, p, t);
                }
            } catch (Exception ignored) { }
            return out;
        }

        static long firstVarint(byte[] m, int field) {
            int[] p = {0};
            try {
                while (p[0] < m.length) {
                    long key = readVar(m, p);
                    int f = (int) (key >>> 3), t = (int) (key & 7);
                    if (f == field && t == 0) return readVar(m, p);
                    skip(m, p, t);
                }
            } catch (Exception ignored) { }
            return -1;
        }

        static byte[] firstBytes(byte[] m, int field) {
            int[] p = {0};
            try {
                while (p[0] < m.length) {
                    long key = readVar(m, p);
                    int f = (int) (key >>> 3), t = (int) (key & 7);
                    if (f == field && t == 2) {
                        int len = (int) readVar(m, p);
                        return java.util.Arrays.copyOfRange(m, p[0], p[0] + len);
                    }
                    skip(m, p, t);
                }
            } catch (Exception ignored) { }
            return null;
        }

        private static long readVar(byte[] m, int[] p) {
            long v = 0;
            int shift = 0;
            while (true) {
                int c = m[p[0]++] & 0xFF;
                v |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) return v;
                shift += 7;
            }
        }

        private static void skip(byte[] m, int[] p, int t) {
            if (t == 0) readVar(m, p);
            else if (t == 2) { int len = (int) readVar(m, p); p[0] += len; }
            else if (t == 5) p[0] += 4;
            else if (t == 1) p[0] += 8;
            else throw new IllegalStateException("tipo " + t);
        }
    }
}
