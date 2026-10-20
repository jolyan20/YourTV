package sa.urtv.screensaver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A tiny web server on the home network (port 8765) for the phone page:
 *   GET  /                 the page (assets/control.html)
 *   GET  /api/public       the screensaver page's view of the settings, from this TV only (127.0.0.1)
 *   GET  /api/state        everything, with the PIN
 *   POST /api/save         {"section": "...", "value": ...} with the PIN
 *   POST /api/quran        {"on": true|false} with the PIN
 * The PIN comes in the X-Pin header. Only addresses on the home network are served.
 */
final class ControlServer implements Runnable {
    private final Context app;
    private ServerSocket server;
    private volatile boolean running;

    ControlServer(Context c) { app = c.getApplicationContext(); }

    synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this, "control-server");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    @Override
    public void run() {
        while (running) {
            try {
                server = new ServerSocket(ControlStore.PORT);
                while (running) {
                    final Socket s = server.accept();
                    new Thread(() -> handle(s), "control-request").start();
                }
            } catch (Exception e) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }   // port busy: try again
            } finally {
                try { if (server != null) server.close(); } catch (Exception ignored) { }
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            InetAddress from = s.getInetAddress();
            if (!(from.isLoopbackAddress() || from.isSiteLocalAddress() || from.isLinkLocalAddress())) { s.close(); return; }
            InputStream in = s.getInputStream();
            String head = readHead(in);
            if (head == null) { s.close(); return; }
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            String method = first[0], path = first.length > 1 ? first[1] : "/";
            Map<String, String> h = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int k = lines[i].indexOf(':');
                if (k > 0) h.put(lines[i].substring(0, k).trim().toLowerCase(), lines[i].substring(k + 1).trim());
            }
            int len = 0;
            try { len = Integer.parseInt(get(h, "content-length", "0")); } catch (Exception ignored) { }
            if (len > 200_000) { reply(s, 413, "text/plain", "too large"); return; }
            byte[] body = new byte[len];
            for (int got = 0; got < len; ) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
            String q = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            path = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;

            if ("OPTIONS".equals(method)) { reply(s, 204, "text/plain", ""); return; }
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                reply(s, 200, "text/html; charset=utf-8", asset("control.html")); return;
            }
            if ("GET".equals(method) && "/api/public".equals(path)) {
                if (!from.isLoopbackAddress()) { reply(s, 403, "text/plain", "local only"); return; }
                reply(s, 200, "application/json; charset=utf-8", ControlStore.state(app).toString()); return;
            }
            String pin = get(h, "x-pin", q.startsWith("pin=") ? q.substring(4) : "");
            if (!ControlStore.pin(app).equals(pin)) { reply(s, 401, "application/json", "{\"error\":\"pin\"}"); return; }
            if ("GET".equals(method) && "/api/state".equals(path)) {
                reply(s, 200, "application/json; charset=utf-8", ControlStore.state(app).toString()); return;
            }
            if ("POST".equals(method)) {
                JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
                if ("/api/quran".equals(path)) {
                    ControlStore.setQuran(app, o.optBoolean("on"));
                    reply(s, 200, "application/json", "{\"ok\":true}"); return;
                }
                if ("/api/save".equals(path)) {
                    String section = o.optString("section");
                    boolean known = false;
                    for (String x : ControlStore.SECTIONS) if (x.equals(section)) known = true;
                    Object v = o.opt("value");
                    if (!known || !(v instanceof JSONObject || v instanceof JSONArray || v == JSONObject.NULL)) {
                        reply(s, 400, "application/json", "{\"error\":\"section\"}"); return;
                    }
                    ControlStore.save(app, section, v.toString());
                    reply(s, 200, "application/json", "{\"ok\":true}"); return;
                }
            }
            reply(s, 404, "text/plain", "not found");
        } catch (Exception e) {
            try { reply(s, 500, "text/plain", "error"); } catch (Exception ignored) { }
        }
    }

    private static String get(Map<String, String> m, String k, String dflt) {
        String v = m.get(k);
        return v == null ? dflt : v;
    }

    private static String readHead(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (b.size() > 16384) return null;
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return b.toString("UTF-8");
        }
        return null;
    }

    private String asset(String name) throws java.io.IOException {
        try (InputStream in = app.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static void reply(Socket s, int code, String type, String text) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Content-Type, X-Pin\r\n"
            + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        s.close();
    }
}
