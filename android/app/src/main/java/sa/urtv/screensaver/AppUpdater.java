package sa.urtv.screensaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * Download the latest signed APKs and hand them to Android's own installer.
 * Android requires the person's confirmation: never uninstall/clear a package or its data.
 * Updates run in place, provided the old and new signing certificates match.
 */
final class AppUpdater {
    private static final String RELEASE_API =
        "https://api.github.com/repos/jolyan20/YourTV/releases/tags/apk-latest";
    private static final String RELEASE_BASE =
        "https://github.com/jolyan20/YourTV/releases/download/apk-latest/";
    private static final String[] APK = {"urtv-home.apk", "urtv-screensaver.apk"};
    private static final String[] PACKAGES = {"sa.urtv.home", "sa.urtv.screensaver"};
    private static final String[] LABELS = {"الواجهة الرئيسية", "شاشة التوقف"};
    private static final long MAX_APK = 30_000_000;
    private static final int MAX_JSON = 2_000_000;

    private final Activity activity;
    private boolean checking;

    AppUpdater(Activity activity) { this.activity = activity; }

    void check() {
        if (checking) return;
        checking = true;
        final AlertDialog wait = new AlertDialog.Builder(activity,
            android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("تحديث تطبيق تلفزيونك")
            .setMessage("جارٍ فحص الإصدار الرسمي وتحميل الملفات الآمنة…")
            .setCancelable(false).create();
        wait.show();
        new Thread(() -> {
            try {
                final File[] updates = fetchVerifiedApks();
                activity.runOnUiThread(() -> {
                    checking = false;
                    wait.dismiss();
                    if (!activity.isFinishing() && !activity.isDestroyed()) showAvailable(updates);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    checking = false;
                    wait.dismiss();
                    if (!activity.isFinishing() && !activity.isDestroyed())
                        notice("تعذر التحديث", "تعذر تنزيل تحديث موثوق أو التحقق من توقيعه. لم تُعدّل التطبيقات أو معلوماتها.\n\n"
                            + safeReason(e));
                });
            }
        }, "yourtv-apk-update").start();
    }

    /** Discover release assets by exact name and URL; never accept a third-party APK URL. */
    private File[] fetchVerifiedApks() throws Exception {
        JSONObject release = new JSONObject(new String(read(RELEASE_API, MAX_JSON), "UTF-8"));
        if (release.optBoolean("draft") || release.optBoolean("prerelease"))
            throw new SecurityException("الإصدار غير معتمد");
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new SecurityException("لا توجد ملفات إصدار");
        File folder = new File(activity.getCacheDir(), "apk-updates");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new Exception("تعذر إنشاء مجلد مؤقت");

        final File[] ready = new File[APK.length];
        for (int i = 0; i < APK.length; i++) {
            final String url = RELEASE_BASE + APK[i];
            long size = -1;
            for (int k = 0; k < assets.length(); k++) {
                JSONObject asset = assets.getJSONObject(k);
                if (APK[i].equals(asset.optString("name")) &&
                    url.equals(asset.optString("browser_download_url"))) {
                    size = asset.optLong("size", -1);
                    break;
                }
            }
            if (size < 1024 || size > MAX_APK) throw new SecurityException("ملف إصدار غير صالح: " + APK[i]);
            File apk = new File(folder, APK[i]);
            File temp = new File(folder, APK[i] + ".download.apk");
            try {
                byte[] bytes = read(url, MAX_APK);
                if (bytes.length != size) throw new SecurityException("حجم الملف لا يطابق GitHub");
                try (FileOutputStream stream = new FileOutputStream(temp)) {
                    stream.write(bytes);
                    stream.getFD().sync();
                }
                verify(temp, PACKAGES[i]);
                if (apk.exists() && !apk.delete()) throw new Exception("تعذر استبدال الملف المؤقت");
                if (!temp.renameTo(apk)) throw new Exception("تعذر حفظ الملف المؤقت");
                PackageInfo archived = archiveInfo(apk);
                long latest = code(archived);
                long current = installedCode(PACKAGES[i]);
                if (latest > current) ready[i] = apk;
            } finally {
                if (temp.exists()) temp.delete();
            }
        }
        return ready;
    }

    private void verify(File file, String expectedPackage) throws Exception {
        PackageInfo archive = archiveInfo(file);
        if (archive == null || !expectedPackage.equals(archive.packageName))
            throw new SecurityException("معرّف التطبيق لا يطابق الإصدار الحالي");
        // Both apps are signed with the same release keystore. Verify against the
        // installed target when present, or against this app for a new home install.
        PackageManager pm = activity.getPackageManager();
        PackageInfo trusted;
        try {
            trusted = pm.getPackageInfo(expectedPackage, certFlags());
        } catch (PackageManager.NameNotFoundException e) {
            trusted = pm.getPackageInfo(activity.getPackageName(), certFlags());
        }
        Signature[] accepted = signatures(trusted);
        Signature[] downloaded = signatures(archive);
        if (accepted.length != 1 || downloaded.length != 1 ||
            !Arrays.equals(accepted[0].toByteArray(), downloaded[0].toByteArray()))
            throw new SecurityException("توقيع التطبيق مختلف؛ تم إيقاف التحديث لحماية المعلومات");
    }

    private PackageInfo archiveInfo(File file) throws Exception {
        PackageInfo pkg = activity.getPackageManager().getPackageArchiveInfo(
            file.getAbsolutePath(), certFlags());
        if (pkg == null) throw new SecurityException("حزمة Android غير صالحة");
        return pkg;
    }

    private static int certFlags() {
        return Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private static Signature[] signatures(PackageInfo pkg) {
        if (Build.VERSION.SDK_INT >= 28 && pkg.signingInfo != null) {
            return pkg.signingInfo.hasMultipleSigners()
                ? pkg.signingInfo.getApkContentsSigners() : pkg.signingInfo.getSigningCertificateHistory();
        }
        return pkg.signatures == null ? new Signature[0] : pkg.signatures;
    }

    private long installedCode(String id) {
        try { return code(activity.getPackageManager().getPackageInfo(id, 0)); }
        catch (PackageManager.NameNotFoundException e) { return -1; }
    }

    private static long code(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private void showAvailable(final File[] ready) {
        int count = 0;
        for (File file : ready) if (file != null) count++;
        if (count == 0) {
            notice("التطبيق محدّث", "الإصداران المثبّتان لا يحتاجان إلى تحديث. إعداداتك محفوظة.");
            return;
        }
        final int[] index = new int[count];
        final String[] choices = new String[count];
        int n = 0;
        for (int i = 0; i < ready.length; i++) {
            if (ready[i] != null) {
                index[n] = i;
                choices[n] = "تثبيت تحديث " + LABELS[i];
                n++;
            }
        }
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("تحديث تلفزيونك")
            .setMessage("تم تنزيل حزم رسمية والتحقق من توقيعها. اختر التطبيق الذي تريد تحديثه. "
                + "قد تحتاج إلى الموافقة على السماح بالتثبيت؛ لا تحذف الإصدار القديم. "
                + "بعد التثبيت، ارجع إلى هذا الزر لتحديث التطبيق الثاني إن وُجد.")
            .setItems(choices, (d, which) -> install(ready[index[which]], PACKAGES[index[which]]))
            .setNegativeButton("لاحقًا", null)
            .show();
    }

    private void install(File apk, String pkg) {
        if (!apk.isFile()) { notice("خطأ", "ملف التحديث غير موجود؛ أعد الفحص."); return; }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            notice("السماح بالتثبيت", "فعّل «السماح من هذا المصدر» لتطبيق تلفزيونك، "
                + "ثم ارجع واضغط «تحديث التطبيق» مرة أخرى.");
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            } catch (ActivityNotFoundException e) {
                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }
            return;
        }
        try {
            Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updates/" + Uri.encode(apk.getName()));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            notice("تعذر فتح التثبيت", "تعذر فتح مثبّت Android على هذا التلفزيون. "
                + "احتُفظ بالإصدار المثبت ومعلوماتك؛ يمكنك التثبيت يدويًا.");
        }
    }

    private void notice(String title, String message) {
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setMessage(message).setPositiveButton("حسنًا", null).show();
    }

    private static String safeReason(Exception e) {
        if (e instanceof SecurityException) return e.getMessage();
        return "تحقق من اتصال الإنترنت ومساحة تخزين التلفزيون، ثم حاول مرة أخرى.";
    }

    private static byte[] read(String source, long limit) throws Exception {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(source).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
            con.setRequestProperty("User-Agent", "YourTV-Android-Updater");
            con.setRequestProperty("Accept", "application/vnd.github+json");
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            int len = con.getContentLength();
            if (len > limit) throw new SecurityException("ملف التحديث كبير جدًا");
            try (InputStream in = con.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16_384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > limit) throw new SecurityException("ملف التحديث تجاوز الحد");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
