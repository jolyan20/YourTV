package sa.urtv.screensaver;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * The household's own files, sent from the phone page and kept on this TV only (and its sister TV, ControlSync):
 * the adhan to play instead of the video ("adhan-main", "adhan-fajr"), and a photo for a family occasion
 * ("fam-<id>"). Names are checked; at most 12 MB each.
 */
final class Uploads {
    static final int MAX = 12_000_000;

    private Uploads() {}

    static boolean okName(String n) {
        return n != null && n.matches("adhan-main|adhan-fajr|fam-[A-Za-z0-9]{4,20}");
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "uploads");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static File file(Context c, String name) {
        return okName(name) ? new File(dir(c), name) : null;
    }

    static synchronized void save(Context c, String name, byte[] data, long at, String type) throws Exception {
        File f = file(c, name), tmp = new File(dir(c), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); }
        if (!tmp.renameTo(f)) throw new Exception("save");
        stamp(c, name, data.length, at, type);
    }

    static synchronized void delete(Context c, String name, long at) {
        File f = file(c, name);
        if (f != null) f.delete();
        stamp(c, name, -1, at, "");   // kept as "deleted", so the other TV deletes it too
    }

    private static void stamp(Context c, String name, long size, long at, String type) {
        try {
            JSONObject o = new JSONObject(ControlStore.prefs(c).getString("uploads", "{}"));
            o.put(name, new JSONObject().put("size", size).put("at", at).put("type", type));
            ControlStore.prefs(c).edit().putString("uploads", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** name -> {size (-1 = deleted), at} */
    static JSONObject list(Context c) {
        try { return new JSONObject(ControlStore.prefs(c).getString("uploads", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static byte[] read(Context c, String name) throws Exception {
        File f = file(c, name);
        if (f == null || !f.isFile()) return null;
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            for (int got = 0; got < b.length; ) { int n = in.read(b, got, b.length - got); if (n < 0) break; got += n; }
        }
        return b;
    }

    static String type(Context c, String name) {
        JSONObject f = list(c).optJSONObject(name);
        String t = f == null ? "" : f.optString("type");
        return t.matches("(audio|image)/[a-z0-9.+-]{2,30}") ? t : name.startsWith("fam-") ? "image/jpeg" : "audio/mpeg";
    }
}
