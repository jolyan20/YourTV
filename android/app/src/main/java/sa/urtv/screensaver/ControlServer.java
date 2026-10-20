package sa.urtv.screensaver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * A tiny web server for the phone page (port 8765), reachable from the home network and from Tailscale only:
 *   GET  /               the page (assets/control.html)
 *   GET  /api/public     the screensaver page's view of the settings, from this TV itself (127.0.0.1)
 *   POST /api/login      {"pin": "123456"} -> {"key": session key}
 *   POST /api/logout     ends this phone's session
 *   GET  /api/state      everything                         } with the session key
 *   POST /api/save       {"section": "...", "value": ...}   } in the X-Key header
 *   POST /api/quran      {"on": true|false}                 }
 * Wrong PINs: 5 from one address locks that address for 15 minutes; 20 in an hour from anywhere locks
 * logins for an hour. No cross-site access (CORS only for /api/public on this TV), strict security headers.
 */
final class ControlServer implements Runnable {
    private static final int PER_ADDRESS = 5, GLOBAL = 20;
    private static final long ADDRESS_LOCK_MS = 15 * 60_000, GLOBAL_WINDOW_MS = 3600_000;

    private final Context app;
    private final Semaphore slots = new Semaphore(8);   // at most 8 requests at once
    private final Map<String, int[]> fails = new HashMap<>();          // address -> {count}
    private final Map<String, Long> lockedUntil = new HashMap<>();     // address -> time
    private final java.util.ArrayDeque<Long> recentFails = new java.util.ArrayDeque<>();
    private long globalLockUntil;
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
                    if (!ControlStore.allowed(s.getInetAddress()) || !slots.tryAcquire()) { s.close(); continue; }
                    new Thread(() -> { try { handle(s); } finally { slots.release(); } }, "control-request").start();
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
            String who = from.getHostAddress();
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
            if (len < 0 || len > 200_000) { reply(s, 413, "text/plain", "too large", false); return; }
            byte[] body = new byte[len];
            for (int got = 0; got < len; ) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
            if (path.contains("?")) path = path.substring(0, path.indexOf('?'));

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                reply(s, 200, "text/html; charset=utf-8", asset("control.html"), false); return;
            }
            if ("/api/public".equals(path)) {   // the screensaver page on this TV: file:// origin, so CORS here only
                if (!from.isLoopbackAddress()) { reply(s, 403, "text/plain", "local only", false); return; }
                if ("OPTIONS".equals(method)) { reply(s, 204, "text/plain", "", true); return; }
                reply(s, 200, "application/json; charset=utf-8", ControlStore.state(app).toString(), true); return;
            }
            if (!"POST".equals(method) && !"GET".equals(method)) { reply(s, 405, "text/plain", "method", false); return; }

            if ("/api/login".equals(path) && "POST".equals(method)) {
                long wait = lockWait(who);
                if (wait > 0) { reply(s, 429, "application/json", "{\"error\":\"locked\",\"minutes\":" + (wait / 60_000 + 1) + "}", false); return; }
                String pin = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("pin");
                if (!ControlStore.pinMatches(app, pin)) {
                    failed(who);
                    Thread.sleep(1000);   // slows guessing further
                    reply(s, 401, "application/json", "{\"error\":\"pin\"}", false); return;
                }
                synchronized (this) { fails.remove(who); }
                reply(s, 200, "application/json", new JSONObject().put("key", ControlStore.newSession(app)).toString(), false); return;
            }

            String key = get(h, "x-key", "");
            if (!ControlStore.sessionValid(app, key)) { reply(s, 401, "application/json", "{\"error\":\"session\"}", false); return; }
            if ("/api/logout".equals(path)) { ControlStore.endSession(app, key); reply(s, 200, "application/json", "{\"ok\":true}", false); return; }
            if ("GET".equals(method) && "/api/state".equals(path)) {
                reply(s, 200, "application/json; charset=utf-8", ControlStore.state(app).toString(), false); return;
            }
            if ("POST".equals(method)) {
                JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
                if ("/api/quran".equals(path)) {
                    ControlStore.setQuran(app, o.optBoolean("on"));
                    reply(s, 200, "application/json", "{\"ok\":true}", false); return;
                }
                if ("/api/save".equals(path)) {
                    String section = o.optString("section");
                    boolean known = false;
                    for (String x : ControlStore.SECTIONS) if (x.equals(section)) known = true;
                    Object v = o.opt("value");
                    if (!known || !(v instanceof JSONObject || v instanceof JSONArray)) {
                        reply(s, 400, "application/json", "{\"error\":\"section\"}", false); return;
                    }
                    ControlStore.save(app, section, v.toString());
                    reply(s, 200, "application/json", "{\"ok\":true}", false); return;
                }
            }
            reply(s, 404, "text/plain", "not found", false);
        } catch (Exception e) {
            try { reply(s, 400, "text/plain", "bad request", false); } catch (Exception ignored) { }
        }
    }

    /** ms until this address may try again (0 = now) */
    private synchronized long lockWait(String who) {
        long now = System.currentTimeMillis();
        if (globalLockUntil > now) return globalLockUntil - now;
        Long until = lockedUntil.get(who);
        return until != null && until > now ? until - now : 0;
    }

    private synchronized void failed(String who) {
        long now = System.currentTimeMillis();
        int[] n = fails.get(who);
        if (n == null) fails.put(who, n = new int[1]);
        if (++n[0] >= PER_ADDRESS) { lockedUntil.put(who, now + ADDRESS_LOCK_MS); n[0] = 0; }
        recentFails.addLast(now);
        while (!recentFails.isEmpty() && recentFails.peekFirst() < now - GLOBAL_WINDOW_MS) recentFails.removeFirst();
        if (recentFails.size() >= GLOBAL) { globalLockUntil = now + GLOBAL_WINDOW_MS; recentFails.clear(); }
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

    private static void reply(Socket s, int code, String type, String text, boolean cors) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + (cors ? "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET" : "")
            + "\r\nX-Frame-Options: DENY\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer"
            + "\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
            + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        s.close();
    }
}
