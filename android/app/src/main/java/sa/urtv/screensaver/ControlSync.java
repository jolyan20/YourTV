package sa.urtv.screensaver;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
    static final String[] SECTIONS = {"tasks", "reminders", "family", "sleep", "display", "series", "bedtime", "wird", "screens"};

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
                JSONObject o = new JSONObject(get(peer, key));
                JSONObject secs = o.optJSONObject("sections");
                for (String s : SECTIONS) {
                    JSONObject x = secs == null ? null : secs.optJSONObject(s);
                    if (x == null || !x.has("v")) continue;
                    long at = x.optLong("at");
                    if (at > ControlStore.prefs(c).getLong("at:" + s, 0)) ControlStore.saveFrom(c, s, x.get("v").toString(), at);
                }
                JSONObject pr = o.optJSONObject("prefs");
                if (pr != null && pr.optLong("at") > ControlStore.prefs(c).getLong("at:prefs", 0)
                        && ControlPrefs.apply(c, pr.getJSONObject("v"), false) == null)
                    ControlStore.prefs(c).edit().putLong("at:prefs", pr.optLong("at")).apply();
                return;   // one TV that answered is enough
            } catch (Exception ignored) { }   // that TV is off or away: try the next
        }
    }

    /** a plain GET over a socket (the app allows no other clear-text connections), home network or Tailscale only */
    private static String get(String host, String key) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, ControlStore.PORT), 4000);
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream();
            out.write(("GET /api/sync HTTP/1.0\r\nHost: " + host + "\r\nX-Sync: " + key + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 4_000_000) b.write(buf, 0, n);
            String all = b.toString("UTF-8");
            if (!all.startsWith("HTTP/1.1 200") && !all.startsWith("HTTP/1.0 200")) throw new Exception("status");
            return all.substring(all.indexOf("\r\n\r\n") + 4);
        }
    }
}
