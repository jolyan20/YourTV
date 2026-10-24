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
    private static final String CRLF = "\r\n";
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
            boolean upload = path.startsWith("/api/upload") || path.startsWith("/api/device?");
            if (len < 0 || len > (upload ? Uploads.MAX : 200_000)) { reply(s, 413, "text/plain", "too large", false); return; }
            if (upload) s.setSoTimeout(90_000);   // a few megabytes from the phone, maybe over Tailscale
            byte[] body = new byte[len];
            for (int got = 0; got < len; ) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
            String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            if (path.contains("?")) path = path.substring(0, path.indexOf('?'));

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                reply(s, 200, "text/html; charset=utf-8", asset("control.html"), false); return;
            }
            if ("GET".equals(method) && "/manifest.json".equals(path)) {   // "add to home screen" on the phone
                reply(s, 200, "application/manifest+json", "{\"name\":\"تلفزيوني\",\"short_name\":\"تلفزيوني\",\"start_url\":\"/\","
                    + "\"display\":\"standalone\",\"background_color\":\"#0b1410\",\"theme_color\":\"#0b1410\",\"dir\":\"rtl\",\"lang\":\"ar\","
                    + "\"icons\":[{\"src\":\"/icon.png\",\"sizes\":\"512x512\",\"type\":\"image/png\"}]}", false); return;
            }
            if ("GET".equals(method) && "/icon.png".equals(path)) { replyBytes(s, "image/png", assetBytes("control-icon.png")); return; }
            if ("/api/public".equals(path)) {   // the screensaver page on this TV: file:// origin, so CORS here only
                if (!from.isLoopbackAddress()) { reply(s, 403, "text/plain", "local only", false); return; }
                if ("OPTIONS".equals(method)) { reply(s, 204, "text/plain", "", true); return; }
                reply(s, 200, "application/json; charset=utf-8", ControlStore.state(app).toString(), true); return;
            }
            if ("/api/autoquran".equals(path)) {   // the screensaver page: the Makkah stream for Jumu'ah and Taraweeh
                if (!from.isLoopbackAddress()) { reply(s, 403, "text/plain", "local only", false); return; }
                if ("OPTIONS".equals(method)) { reply(s, 204, "text/plain", "", true); return; }
                ControlStore.setQuran(app, new JSONObject(new String(body, StandardCharsets.UTF_8)).optBoolean("on"));
                reply(s, 200, "application/json", "{\"ok\":true}", true); return;
            }
            if ("/api/adhan".equals(path)) {   // the screensaver page: play the adhan video now
                if (!from.isLoopbackAddress()) { reply(s, 403, "text/plain", "local only", false); return; }
                if ("OPTIONS".equals(method)) { reply(s, 204, "text/plain", "", true); return; }
                JSONObject a = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String id = a.optString("id");
                if (!id.matches("[A-Za-z0-9_-]{11}")) { reply(s, 400, "text/plain", "id", true); return; }
                ControlStore.prefs(app).edit().putString("adhanPlay", new JSONObject().put("id", id)
                    .put("start", Math.max(0, a.optInt("start"))).put("end", Math.min(600, a.optInt("end", 240)))
                    .put("at", System.currentTimeMillis()).toString()).apply();
                reply(s, 200, "application/json", "{\"ok\":true}", true); return;
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

            if ("/api/pair-receive".equals(path) && "POST".equals(method)) {
                long wait = lockWait(who);
                if (wait > 0) { reply(s, 429, "application/json", "{\"error\":\"locked\"}", false); return; }
                JSONObject req = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String pin = req.optString("pin"), newKey = req.optString("key");
                if (!ControlStore.pinMatches(app, pin) || !newKey.matches("[0-9a-f]{64}")) {
                    failed(who);
                    Thread.sleep(1000);
                    reply(s, 401, "application/json", "{\"error\":\"pin\"}", false); return;
                }
                String existing = ControlStore.prefs(app).getString("syncKey", "");
                if (!existing.isEmpty() && !existing.equals(newKey)) {
                    reply(s, 409, "application/json", "{\"error\":\"paired elsewhere\"}", false); return;
                }
                synchronized (this) { fails.remove(who); }
                ControlStore.prefs(app).edit().putString("syncKey", newKey).apply();
                ControlSync.addPeer(app, who);
                reply(s, 200, "application/json", "{\"ok\":true}", false); return;
            }
            if ("GET".equals(method) && path.startsWith("/file/")) {   // the screensaver page on this TV: the adhan, a photo
                String name = path.substring(6);
                byte[] f = from.isLoopbackAddress() && Uploads.okName(name) ? Uploads.read(app, name) : null;
                if (f == null) { reply(s, 404, "text/plain", "none", false); return; }
                replyBytes(s, Uploads.type(app, name), f); return;
            }
            if ("GET".equals(method) && "/api/syncfile".equals(path)) {   // the sister TV takes a file (ControlSync)
                if (!ControlSync.keyMatches(app, get(h, "x-sync", ""))) { failed(who); reply(s, 401, "text/plain", "sync", false); return; }
                String name = query.startsWith("name=") ? query.substring(5) : "";
                byte[] f = Uploads.okName(name) ? Uploads.read(app, name) : null;
                if (f == null) { reply(s, 404, "text/plain", "none", false); return; }
                replyBytes(s, Uploads.type(app, name), f); return;
            }
            if ("GET".equals(method) && "/api/sync".equals(path)) {   // the household's other TV (ControlSync), its own key
                if (!ControlSync.keyMatches(app, get(h, "x-sync", ""))) { failed(who); reply(s, 401, "text/plain", "sync", false); return; }
                reply(s, 200, "application/json; charset=utf-8", ControlSync.state(app).toString(), false); return;
            }
            String key = get(h, "x-key", "");
            boolean browserAuth = ControlStore.sessionValid(app, key);
            boolean peerAuth = ControlSync.isPeer(app, who) && ControlSync.keyMatches(app, get(h, "x-sync", ""));
            if (!browserAuth && !peerAuth) { reply(s, 401, "application/json", "{\"error\":\"session\"}", false); return; }
            // Remote management is only available to an authenticated phone on this hub,
            // never to a peer through a proxy (prevents chaining arbitrary devices).
            if (browserAuth && "GET".equals(method) && "/api/devices".equals(path)) {
                reply(s, 200, "application/json; charset=utf-8", ControlSync.devices(app).toString(), false); return;
            }
            if (browserAuth && "POST".equals(method) && "/api/pair".equals(path)) {
                JSONObject data = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String host = data.optString("ip");
                try {
                    ControlSync.join(app, host, data.optString("pin"), data.optString("name"));
                    reply(s, 200, "application/json", "{\"ok\":true}", false);
                } catch (Exception ex) {
                    reply(s, 400, "application/json", "{\"error\":\"pair failed; verify TV PIN and address\"}", false);
                }
                return;
            }
            if (browserAuth && "/api/device".equals(path)) {
                // A device request may only target a TV explicitly paired by the owner.
                String host = "", target = "";
                for (String kv : query.split("&")) {
                    if (kv.startsWith("ip=")) host = java.net.URLDecoder.decode(kv.substring(3), "UTF-8");
                    if (kv.startsWith("target=")) target = java.net.URLDecoder.decode(kv.substring(7), "UTF-8");
                }
                try {
                    byte[] result = ControlSync.remote(app, host, target, method, body, get(h, "content-type", "application/json"));
                    if (result.length > 2_000_000) throw new Exception("too large");
                    replyBytes(s, "application/json; charset=utf-8", result);
                } catch (Exception ex) {
                    reply(s, 502, "application/json", "{\"error\":\"device offline or not paired\"}", false);
                }
                return;
            }
            if ("GET".equals(method) && "/api/prefs".equals(path)) {
                reply(s, 200, "application/json; charset=utf-8", ControlPrefs.read(app).toString(), false); return;
            }
            if ("GET".equals(method) && "/api/teams".equals(path)) {
                String q = query.startsWith("q=") ? java.net.URLDecoder.decode(query.substring(2), "UTF-8") : "";
                reply(s, 200, "application/json; charset=utf-8", ControlPrefs.searchTeams(app, q).toString(), false); return;
            }
            if ("POST".equals(method) && upload) {   // ?name=adhan-main|adhan-fajr|fam-<id> [&del=1], the file as the body
                String name = "", del = "";
                for (String kv : query.split("&")) {
                    if (kv.startsWith("name=")) name = kv.substring(5);
                    if (kv.startsWith("del=")) del = kv.substring(4);
                }
                String type = get(h, "content-type", "").split(";")[0].trim().toLowerCase();
                boolean audio = name.startsWith("adhan-");
                if (!Uploads.okName(name)) { reply(s, 400, "application/json", "{\"error\":\"name\"}", false); return; }
                if ("1".equals(del)) { Uploads.delete(app, name, System.currentTimeMillis()); reply(s, 200, "application/json", "{\"ok\":true}", false); return; }
                if (body.length < 1000 || !(audio ? type.startsWith("audio/") : type.matches("image/(jpeg|png|webp)"))) {
                    reply(s, 400, "application/json", "{\"error\":\"type\"}", false); return;
                }
                Uploads.save(app, name, body, System.currentTimeMillis(), type);
                reply(s, 200, "application/json", "{\"ok\":true}", false); return;
            }
            if ("GET".equals(method) && "/api/shows".equals(path)) {
                String q = query.startsWith("q=") ? java.net.URLDecoder.decode(query.substring(2), "UTF-8") : "";
                reply(s, 200, "application/json; charset=utf-8", ControlPrefs.searchShows(app, q).toString(), false); return;
            }
            if ("POST".equals(method) && "/api/prefs".equals(path)) {
                String err = ControlPrefs.apply(app, new JSONObject(new String(body, StandardCharsets.UTF_8)));
                reply(s, err == null ? 200 : 400, "application/json", err == null ? "{\"ok\":true}" : "{\"error\":\"" + err + "\"}", false); return;
            }
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
        return new String(assetBytes(name), StandardCharsets.UTF_8);
    }

    private byte[] assetBytes(String name) throws java.io.IOException {
        try (InputStream in = app.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void replyBytes(Socket s, String type, byte[] body) throws java.io.IOException {
        String head = "HTTP/1.1 200 X" + CRLF + "Content-Type: " + type + CRLF + "Content-Length: " + body.length
            + CRLF + "X-Content-Type-Options: nosniff" + CRLF + "Cache-Control: max-age=86400" + CRLF + "Connection: close" + CRLF + CRLF;
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        s.close();
    }

    private static void reply(Socket s, int code, String type, String text, boolean cors) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + (cors ? "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET" : "")
            + "\r\nX-Frame-Options: DENY\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer"
            + "\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; frame-ancestors 'none'"
            + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        s.close();
    }
}
