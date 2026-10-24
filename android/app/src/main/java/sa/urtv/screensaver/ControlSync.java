package sa.urtv.screensaver;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.security.SecureRandom;
import org.json.JSONArray;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The household's TVs share one set of data: tasks, reminders, family occasions, the screens, the wird, my series,
 * bedtime, sleep hours, the screen options, and the city and followed teams. Every minute each TV asks the others
 * (home network or Tailscale, set once from the computer: optimize/saver.sh sync) for what they hold, and keeps
 * whichever copy of each part was changed last. The message on the screen and game time stay with each TV.
 * The TVs prove themselves to each other with a shared random key; the phone's PIN is not involved.
 */
final class ControlSync {
    static final String[] SECTIONS = {"tasks", "reminders", "family", "sleep", "display", "series", "bedtime", "wird", "screens", "tmdb"};

    private ControlSync() {}

    static boolean keyMatches(Context c, String given) {
        String k = ControlStore.prefs(c).getString("syncKey", "");
        return k.length() >= 32 && given != null && MessageDigest.isEqual(k.getBytes(), given.getBytes());
    }

    /** what this TV holds, with when each part last changed */
    static JSONObject state(Context c) throws Exception {
        JSONObject out = new JSONObject(), secs = new JSONObject();
        for (String s : SECTIONS) {
            String v = ControlStore.prefs(c).getString(s, null);
            if (v != null) secs.put(s, new JSONObject().put("v", new JSONObject("{\"v\":" + v + "}").get("v"))
                .put("at", ControlStore.prefs(c).getLong("at:" + s, 0)));
        }
        out.put("sections", secs);
        out.put("files", Uploads.list(c));
        JSONObject pr = ControlPrefs.read(c);
        out.put("prefs", new JSONObject().put("at", ControlStore.prefs(c).getLong("at:prefs", 0))
            .put("v", new JSONObject().put("city", pr.getJSONArray("city").getString(0)).put("teams", pr.getJSONArray("teams"))));
        return out;
    }

    /** ask the other TVs; keep each part that one of them changed later */
    static void pull(Context c) {
        String key = ControlStore.prefs(c).getString("syncKey", ""), peers = ControlStore.prefs(c).getString("syncPeers", "");
        if (key.length() < 32 || peers.isEmpty()) return;
        for (String peer : peers.split(",")) {
            peer = peer.trim();
            if (!peer.matches("[0-9.]{7,15}")) continue;
            try {
                JSONObject o = new JSONObject(new String(fetch(peer, key, "/api/sync"), StandardCharsets.UTF_8));
                JSONObject secs = o.optJSONObject("sections");
                for (String s : SECTIONS) {
                    JSONObject x = secs == null ? null : secs.optJSONObject(s);
                    if (x == null || !x.has("v")) continue;
                    long at = x.optLong("at");
                    if (at > ControlStore.prefs(c).getLong("at:" + s, 0)) ControlStore.saveFrom(c, s, x.get("v").toString(), at);
                }
                JSONObject files = o.optJSONObject("files"), mine = Uploads.list(c);
                if (files != null) for (java.util.Iterator<String> it = files.keys(); it.hasNext(); ) {
                    String name = it.next();
                    JSONObject f = files.optJSONObject(name), m = mine.optJSONObject(name);
                    if (f == null || !Uploads.okName(name) || f.optLong("at") <= (m == null ? 0 : m.optLong("at"))) continue;
                    if (f.optLong("size") < 0) Uploads.delete(c, name, f.optLong("at"));
                    else Uploads.save(c, name, fetch(peer, key, "/api/syncfile?name=" + name), f.optLong("at"), f.optString("type"));
                }
                JSONObject pr = o.optJSONObject("prefs");
                if (pr != null && pr.optLong("at") > ControlStore.prefs(c).getLong("at:prefs", 0)
                        && ControlPrefs.apply(c, pr.getJSONObject("v"), false) == null)
                    ControlStore.prefs(c).edit().putLong("at:prefs", pr.optLong("at")).apply();
                // Every paired TV must be checked: otherwise a third screen can never contribute updates.
            } catch (Exception ignored) { }   // that TV is off or away: try the next
        }
    }


    /** Registered physical TVs, distinct from the rotating family/person views. The hub's list is local-only. */
    static synchronized JSONArray devices(Context c) {
        JSONArray out = new JSONArray();
        out.put(new JSONObject(java.util.Collections.singletonMap("id", "local")).put("name", "هذا التلفزيون"));
        try {
            JSONObject names = new JSONObject(ControlStore.prefs(c).getString("deviceNames", "{}"));
            String peers = ControlStore.prefs(c).getString("syncPeers", "");
            for (String raw : peers.split(",")) {
                String host = raw.trim();
                if (host.isEmpty() || !safeHost(c, host)) continue;
                out.put(new JSONObject().put("id", host).put("name", names.optString(host, "تلفزيون " + host)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static boolean safeHost(Context c, String ip) {
        if (ip == null || !ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        try {
            InetAddress a = InetAddress.getByName(ip);
            byte[] b = a.getAddress();
            if (b.length != 4 || a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) return false;
            boolean tail = (b[0] & 255) == 100 && (b[1] & 192) == 64;
            if (!a.isSiteLocalAddress() && !tail) return false;
            return !ip.equals(ControlStore.address()) && !ip.equals(ControlStore.tailscaleAddress());
        } catch (Exception ignored) { return false; }
    }

    static boolean isPeer(Context c, String ip) {
        if (!safeHost(c, ip)) return false;
        for (String peer : ControlStore.prefs(c).getString("syncPeers", "").split(","))
            if (peer.trim().equals(ip)) return true;
        return false;
    }

    static synchronized void addPeer(Context c, String ip) {
        if (isPeer(c, ip)) return;
        String peers = ControlStore.prefs(c).getString("syncPeers", "").trim();
        ControlStore.prefs(c).edit().putString("syncPeers", peers.isEmpty() ? ip : peers + "," + ip).apply();
    }

    /** Enter the PIN shown on the second/third TV once. No PIN is kept in the hub. */
    static synchronized void join(Context c, String ip, String pin, String name) throws Exception {
        if (!safeHost(c, ip) || pin == null || !pin.matches("[0-9]{6}")) throw new Exception("address or pin");
        String key = ControlStore.prefs(c).getString("syncKey", "");
        if (key.length() < 32) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            StringBuilder b = new StringBuilder();
            for (byte v : bytes) b.append(String.format(java.util.Locale.US, "%02x", v & 255));
            key = b.toString();
        }
        JSONObject request = new JSONObject().put("pin", pin).put("key", key);
        String answer = new String(exchange(ip, null, "/api/pair-receive", "POST",
            request.toString().getBytes(StandardCharsets.UTF_8), "application/json"), StandardCharsets.UTF_8);
        if (!new JSONObject(answer).optBoolean("ok")) throw new Exception("pairing refused");
        ControlStore.prefs(c).edit().putString("syncKey", key).apply();
        addPeer(c, ip);
        JSONObject names = new JSONObject(ControlStore.prefs(c).getString("deviceNames", "{}"));
        names.put(ip, name == null || name.trim().isEmpty() ? "تلفزيون " + ip : name.trim().substring(0, Math.min(30, name.trim().length())));
        ControlStore.prefs(c).edit().putString("deviceNames", names.toString()).apply();
    }

    /** Only the hub can relay control, to an already paired TV, using the common private key. */
    static byte[] remote(Context c, String ip, String target, String method, byte[] payload, String type) throws Exception {
        if (!isPeer(c, ip)) throw new Exception("unpaired screen");
        if (target == null || target.length() > 300 || target.indexOf('\r') >= 0 || target.indexOf('\n') >= 0)
            throw new Exception("invalid target");
        String base = target.split("\\?", 2)[0];
        boolean get = "GET".equals(method);
        boolean post = "POST".equals(method);
        if (get && !(base.equals("/api/state") || base.equals("/api/prefs") ||
                base.equals("/api/teams") || base.equals("/api/shows"))) throw new Exception("invalid GET");
        if (post && !(base.equals("/api/prefs") || base.equals("/api/quran") ||
                base.equals("/api/save") || base.equals("/api/upload"))) throw new Exception("invalid POST");
        if (!get && !post) throw new Exception("invalid method");
        if (target.contains("?") && !(base.equals("/api/teams") || base.equals("/api/shows") || base.equals("/api/upload")))
            throw new Exception("unexpected query");
        return exchange(ip, ControlStore.prefs(c).getString("syncKey", ""), target, method, payload, type);
    }

    /** Small internal HTTP client: an allowlisted peer only; no arbitrary host or endpoint proxy. */
    static byte[] exchange(String host, String key, String path, String method, byte[] body, String type) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, ControlStore.PORT), 5000);
            s.setSoTimeout(30000);
            OutputStream out = s.getOutputStream();
            byte[] payload = body == null ? new byte[0] : body;
            String headers = method + " " + path + " HTTP/1.1\\r\\nHost: " + host
                + "\\r\\nConnection: close\\r\\n"
                + (key == null ? "" : "X-Sync: " + key + "\\r\\n")
                + ("POST".equals(method) ? "Content-Type: " + (type == null ? "application/json" : type)
                    + "\\r\\nContent-Length: " + payload.length + "\\r\\n" : "")
                + "\\r\\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            if (payload.length > 0) out.write(payload);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && data.size() <= Uploads.MAX + 65536) data.write(buf, 0, n);
            byte[] all = data.toByteArray();
            int sep = -1;
            for (int i = 0; i + 3 < all.length; i++)
                if (all[i] == 13 && all[i+1] == 10 && all[i+2] == 13 && all[i+3] == 10) {sep = i+4; break;}
            if (sep < 0) throw new Exception("response headers");
            String line = new String(all, 0, Math.min(sep, 64), StandardCharsets.US_ASCII);
            if (!line.startsWith("HTTP/1.1 200") && !line.startsWith("HTTP/1.0 200")) throw new Exception("device offline or denied");
            return java.util.Arrays.copyOfRange(all, sep, all.length);
        }
    }

    /** a plain GET over a socket (the app allows no other clear-text connections), home network or Tailscale only */
    private static byte[] fetch(String host, String key, String path) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, ControlStore.PORT), 4000);
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: " + host + "\r\nX-Sync: " + key + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < Uploads.MAX + 65536) b.write(buf, 0, n);
            byte[] all = b.toByteArray();
            String head = new String(all, 0, Math.min(all.length, 16), StandardCharsets.ISO_8859_1);
            if (!head.startsWith("HTTP/1.1 200") && !head.startsWith("HTTP/1.0 200")) throw new Exception("status");
            for (int i = 0; i + 3 < all.length; i++)
                if (all[i] == 13 && all[i + 1] == 10 && all[i + 2] == 13 && all[i + 3] == 10) return java.util.Arrays.copyOfRange(all, i + 4, all.length);
            throw new Exception("head");
        }
    }
}
