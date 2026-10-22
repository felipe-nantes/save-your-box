package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The phone remote: a small web server on the home network (port PORT, main process). The settings screen shows a QR
 * code with the TV's address and a key; the phone's browser opens assets/remote.html, which sends the keys, text, apps
 * to open, the sleep timer and photos for the screensaver. Only addresses on the home network, and only with the key
 * (kept as a cookie after the first visit). Keys and text go through RemoteInput (the TV's own ADB).
 */
public class RemoteServer extends Service {
    static final int PORT = 8765;
    static final String SLEEP = "io.freemytv.SLEEP";

    private static ServerSocket server;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    /** Starts the server (from the home screen, the settings and after boot). */
    static void start(Context c) {
        Intent i = new Intent(c, RemoteServer.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) Foreground.start(c, i); else c.startService(i);
        } catch (RuntimeException e) {
            AppLog.w("phone remote: " + e.getMessage());
        }
    }

    /** The address for the QR code ("http://192.168.0.20:8765/?k=…"), or null while there's no network or key yet. */
    static String url(Context c) {
        String ip = address(), key = readKey(c);
        return ip == null || key == null ? null : "http://" + ip + ":" + PORT + "/?k=" + key;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) Foreground.show(this);
        synchronized (RemoteServer.class) {
            if (server == null) {
                key(this);
                try {
                    server = new ServerSocket(PORT);
                    final ServerSocket s = server;
                    new Thread(() -> accept(s), "phone-remote").start();
                } catch (IOException e) {
                    AppLog.w("phone remote: port " + PORT + ": " + e.getMessage());
                    server = null;
                }
            }
        }
        return START_STICKY;
    }

    private void accept(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                final Socket socket = s.accept();
                pool.execute(() -> serve(socket));
            } catch (IOException e) {
                if (!s.isClosed()) AppLog.w("phone remote: " + e.getMessage());
            }
        }
    }

    // MARK: - HTTP (one request per connection)

    private static final int MAX_HEADER = 16 * 1024;

    private void serve(Socket socket) {
        try (Socket sock = socket) {
            sock.setSoTimeout(30_000);
            InetAddress from = sock.getInetAddress();
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            if (!(from.isSiteLocalAddress() || from.isLinkLocalAddress() || from.isLoopbackAddress())) {
                reply(out, 403, "text/plain", "home network only".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2) return;
            String method = request[0], target = request[1];
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            String query = q < 0 ? "" : target.substring(q + 1);
            // The key comes in the QR code's address (?k=), then from the page with each request (X-Key, kept on the
            // phone) or as a cookie. The page opens straight from the QR code: a phone's camera opens it from another
            // app, and browsers drop SameSite=Strict cookies across that redirect.
            String key = readKey(this);
            boolean fromQr = same(param(query, "k"), key);
            if (!fromQr && !same(headers.get("x-key"), key) && !same(cookie(headers.get("cookie")), key)) {
                reply(out, 403, "text/html; charset=utf-8", denied(), "Cache-Control: no-store\r\n");
                return;
            }
            if (fromQr && method.equals("GET") && path.equals("/")) {
                reply(out, 200, "text/html; charset=utf-8", page(), "Cache-Control: no-store\r\nSet-Cookie: fmtv=" + key
                        + "; Max-Age=31536000; Path=/; HttpOnly; SameSite=Lax\r\n");
                return;
            }
            int length = 0;
            try {
                length = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
            } catch (NumberFormatException ignored) {
            }
            if (length < 0 || length > Photos.MAX_BYTES) {
                reply(out, 413, "text/plain", "too large".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            route(method, path, in, length, out);
        } catch (IOException | RuntimeException e) {
            AppLog.w("phone remote: " + e.getMessage());
        }
    }

    private void route(String method, String path, InputStream in, int length, OutputStream out) throws IOException {
        Context c = this;
        if (method.equals("GET") && path.equals("/")) {
            reply(out, 200, "text/html; charset=utf-8", page(), "Cache-Control: no-store\r\n");
            return;
        }
        if (method.equals("GET") && path.equals("/state")) {
            reply(out, 200, "application/json", state().getBytes(StandardCharsets.UTF_8), "Cache-Control: no-store\r\n");
            return;
        }
        if (!method.equals("POST")) {
            reply(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        if (path.equals("/photo")) {
            boolean ok = Photos.save(c, in, length);
            ok(out, ok ? 200 : 415);
            return;
        }
        String body = new String(readBody(in, Math.min(length, 4096)), StandardCharsets.UTF_8).trim();
        try {
            switch (path) {
                case "/key": RemoteInput.key(c, body); break;
                case "/text": RemoteInput.text(c, body); break;
                case "/open": RemoteInput.open(c, body); break;
                case "/sleep": RemoteInput.sleep(c); break;
                case "/timer": setTimer(c, Integer.parseInt(body)); break;
                case "/photos/clear": Photos.clear(c); break;
                default:
                    reply(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), null);
                    return;
            }
            ok(out, 200);
        } catch (IOException | NumberFormatException e) {
            AppLog.w("phone remote " + path + ": " + e.getMessage());
            reply(out, 503, "text/plain", String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8), null);
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;   // how much of "\r\n\r\n" has come
        while (b.size() < MAX_HEADER) {
            int x = in.read();
            if (x < 0) return null;
            b.write(x);
            if (x == '\r') state = state == 2 ? 3 : 1;
            else if (x == '\n') state = state == 1 ? 2 : state == 3 ? 4 : 0;
            else state = 0;
            if (state == 4) return b.toString("ISO-8859-1");
        }
        return null;
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int at = 0;
        while (at < length) {
            int n = in.read(body, at, length - at);
            if (n < 0) break;
            at += n;
        }
        return body;
    }

    private static void ok(OutputStream out, int status) throws IOException {
        reply(out, status, "text/plain", new byte[0], null);
    }

    private static void reply(OutputStream out, int status, String type, byte[] body, String extra) throws IOException {
        String reason = status == 200 ? "OK" : status == 302 ? "Found" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n" + (extra == null ? "" : extra) + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String param(String query, String name) {
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                } catch (IOException | IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static final Pattern COOKIE = Pattern.compile("(?:^|;\\s*)fmtv=([A-Za-z0-9]+)");

    private static String cookie(String header) {
        if (header == null) return null;
        Matcher m = COOKIE.matcher(header);
        return m.find() ? m.group(1) : null;
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // MARK: - The page and its state

    /** assets/remote.html with its {{name}}s in the TV's language. */
    private byte[] page() throws IOException {
        Map<String, String> text = pageText();
        String html;
        try (InputStream in = getAssets().open("remote.html")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) b.write(buffer, 0, n);
            html = b.toString("UTF-8");
        }
        Matcher m = Pattern.compile("\\{\\{([^}]+)\\}\\}").matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String t = text.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(escape(t != null ? t : m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString().replace("__LANG__", Ui.language).getBytes(StandardCharsets.UTF_8);
    }

    /** remote.html's {{name}} → its text ("#" is filled in by the page). */
    private static Map<String, String> pageText() {
        Map<String, String> t = new HashMap<>();
        t.put("noreply", Ui.tr("The TV didn't respond."));
        t.put("turnsoff", Ui.tr("Turns off in # min"));
        t.put("ontv", Ui.tr("Photos on the TV: #"));
        t.put("sending", Ui.tr("Sending photos… #"));
        t.put("confirmoff", Ui.tr("Turn off the TV and the box now?"));
        t.put("confirmdelete", Ui.tr("Delete all the photos?"));
        t.put("title", Ui.tr("Phone remote"));
        t.put("back", Ui.tr("Back"));
        t.put("home", Ui.tr("Home"));
        t.put("menu", Ui.tr("Menu"));
        t.put("search", Ui.tr("Search"));
        t.put("typehere", Ui.tr("Type here"));
        t.put("send", Ui.tr("Send"));
        t.put("apps", Ui.tr("Apps"));
        t.put("timer", Ui.tr("Sleep timer"));
        t.put("off", Ui.tr("Off"));
        t.put("power", Ui.tr("Turn off TV and box"));
        t.put("photos", Ui.tr("Screensaver photos"));
        t.put("add", Ui.tr("Add photos"));
        t.put("deleteall", Ui.tr("Delete all"));
        return t;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The timer (seconds left, 0 = off), the screensaver's photo count and the apps to open (by name). */
    private String state() {
        JSONObject o = new JSONObject();
        try {
            long at = sleepAt(this);
            o.put("timer", at > 0 ? Math.max(0, (at - System.currentTimeMillis()) / 1000) : 0);
            o.put("photos", Photos.list(this).size());
            JSONArray apps = new JSONArray();
            for (String[] app : apps(this)) apps.put(new JSONObject().put("c", app[0]).put("n", app[1]));
            o.put("apps", apps);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    /** The TV's apps that open from a launcher ({component, name}), by name; not Free My TV itself. */
    private static List<String[]> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String[]> byPkg = new HashMap<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || byPkg.containsKey(pkg)) continue;
                byPkg.put(pkg, new String[] {pkg + "/" + r.activityInfo.name, String.valueOf(r.loadLabel(pm))});
            }
        }
        List<String[]> out = new ArrayList<>(byPkg.values());
        Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    /** Without the key: the page kept on the phone tries again with it, else asks for the QR code. */
    private static byte[] denied() {
        String text = escape(Ui.tr("Scan the QR code in Free My TV's settings on the TV."));
        return ("<!doctype html><meta name=viewport content='width=device-width'><body style='font:18px system-ui;"
                + "background:#0b0b0f;color:#eee;padding:32px'><script>try{var k=localStorage.getItem('fmtv');"
                + "if(k&&location.search.indexOf('k='+k)<0){location.replace('/?k='+encodeURIComponent(k))}}catch(e){}"
                + "</script>" + text).getBytes(StandardCharsets.UTF_8);
    }

    // MARK: - Sleep timer

    private static File sleepFile(Context c) {
        return new File(c.getFilesDir(), "sleep-at");
    }

    /** When the timer puts the TV to sleep (ms), or 0. */
    static long sleepAt(Context c) {
        try (FileInputStream in = new FileInputStream(sleepFile(c))) {
            long at = Long.parseLong(new String(readBody(in, in.available()), StandardCharsets.UTF_8).trim());
            return at > System.currentTimeMillis() ? at : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** The timer in minutes from now; 0 turns it off. */
    static void setTimer(Context c, int minutes) throws IOException {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent sleep = PendingIntent.getBroadcast(c, 0, new Intent(c, HomeReceiver.class).setAction(SLEEP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (minutes <= 0) {
            alarms.cancel(sleep);
            sleepFile(c).delete();
            return;
        }
        long at = System.currentTimeMillis() + Math.min(minutes, 24 * 60) * 60_000L;
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, sleep);   // exact alarms need a permission on Android 12+
        try (FileOutputStream out = new FileOutputStream(sleepFile(c))) {
            out.write(String.valueOf(at).getBytes(StandardCharsets.UTF_8));
        }
    }

    // MARK: - The key in the QR code (in the app's storage: the settings screen, in another process, reads it)

    private static File keyFile(Context c) {
        return new File(c.getFilesDir(), "remote-key");
    }

    private static String readKey(Context c) {
        try (FileInputStream in = new FileInputStream(keyFile(c))) {
            String k = new String(readBody(in, in.available()), StandardCharsets.UTF_8).trim();
            return k.matches("[A-Za-z0-9]{16}") ? k : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static synchronized void key(Context c) {
        if (readKey(c) != null) return;
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder k = new StringBuilder();
        for (int i = 0; i < 16; i++) k.append(chars.charAt(random.nextInt(chars.length())));
        File tmp = new File(keyFile(c).getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(k.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            AppLog.w("phone remote key: " + e.getMessage());
            return;
        }
        if (!tmp.renameTo(keyFile(c))) AppLog.w("phone remote key: not saved");
    }

    /** The TV's address on the home network (Wi-Fi or Ethernet). */
    private static String address() {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface n = all.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                for (Enumeration<InetAddress> a = n.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ip = a.nextElement();
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return ip.getHostAddress();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    /** Android 8 and newer stop background services: there it runs as a foreground one, with a quiet notification. */
    private static final class Foreground {
        static void start(Context c, Intent i) {
            c.startForegroundService(i);
        }

        static void show(Service s) {
            android.app.NotificationManager nm = s.getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(new android.app.NotificationChannel("remote", Ui.tr("Phone remote"),
                    android.app.NotificationManager.IMPORTANCE_MIN));
            s.startForeground(1, new android.app.Notification.Builder(s, "remote")
                    .setSmallIcon(R.drawable.icon).setContentTitle(Ui.tr("Phone remote")).build());
        }
    }
}
