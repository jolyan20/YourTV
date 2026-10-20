package sa.urtv.screensaver;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * What the household sets from the phone page, kept on this TV only (never uploaded): a message, tasks,
 * reminders, family occasions, the sleep hours and whether the Quran window is on. One JSON object per
 * section, in the app's own preferences. The PIN is made once, on this TV, and shown in its settings.
 */
final class ControlStore {
    static final int PORT = 8765;
    static final String[] SECTIONS = {"message", "tasks", "reminders", "family", "sleep"};
    static final String KEY_QURAN = "quran";
    private static final String FILE = "control";

    private ControlStore() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static synchronized String pin(Context c) {
        SharedPreferences p = prefs(c);
        String pin = p.getString("pin", null);
        if (pin == null) {
            pin = String.format(java.util.Locale.US, "%04d", new SecureRandom().nextInt(10000));
            p.edit().putString("pin", pin).apply();
        }
        return pin;
    }

    static JSONObject state(Context c) {
        SharedPreferences p = prefs(c);
        JSONObject o = new JSONObject();
        try {
            for (String s : SECTIONS) {
                String v = p.getString(s, null);
                if (v != null) o.put(s, new JSONObject("{\"v\":" + v + "}").get("v"));
            }
            o.put(KEY_QURAN, p.getBoolean(KEY_QURAN, false));
        } catch (Exception ignored) { }
        return o;
    }

    /** value is the section's JSON text, already parsed once by the caller */
    static void save(Context c, String section, String json) {
        prefs(c).edit().putString(section, json).apply();
    }

    static void setQuran(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_QURAN, on).apply();
    }

    static boolean quran(Context c) {
        return prefs(c).getBoolean(KEY_QURAN, false);
    }

    /** this TV's address on the home network, for the settings screen */
    static String address() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }

    static String url() {
        String ip = address();
        return ip == null ? null : "http://" + ip + ":" + PORT;
    }
}
