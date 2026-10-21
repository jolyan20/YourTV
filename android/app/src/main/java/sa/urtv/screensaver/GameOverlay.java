package sa.urtv.screensaver;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Game time (the PlayStation, or anything on the TV), started from the phone page: a small countdown in the
 * corner over whatever is on screen, HDMI included; amber for the last 5 minutes; at the end a full-screen
 * «انتهى وقت اللعب» and, if chosen on the phone, the TV goes back to its home screen.
 * Drawing over other apps needs the "display over other apps" permission; without it only the screensaver
 * page shows the countdown.
 */
final class GameOverlay {
    private static final long END_SHOW_MS = 20_000;
    private final Context c;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView chip;
    private View full;
    private long until;
    private boolean home;

    GameOverlay(Context c) {
        this.c = c.getApplicationContext();
        wm = (WindowManager) this.c.getSystemService(Context.WINDOW_SERVICE);
    }

    /** the phone page saved a new game time (or stopped it) */
    void update() {
        try {
            JSONObject g = new JSONObject(ControlStore.prefs(c).getString("game", "{}"));
            until = g.optLong("until");
            home = g.optBoolean("home", true);
        } catch (Exception e) {
            until = 0;
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void stop() {
        handler.removeCallbacks(tick);
        hideChip();
        hideFull();
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            long now = System.currentTimeMillis();
            if (until <= 0 || now > until + END_SHOW_MS || !canDraw()) { hideChip(); hideFull(); return; }
            if (now < until) {
                hideFull();
                showChip(until - now);
                handler.postDelayed(this, 1000 - now % 1000);
                return;
            }
            hideChip();
            String done = "gameDone";
            if (ControlStore.prefs(c).getLong(done, 0) != until) {   // once per game time, even after a restart
                ControlStore.prefs(c).edit().putLong(done, until).apply();
                showFull();
                if (home) goHome();
            }
            handler.postDelayed(this, until + END_SHOW_MS - now + 50);
        }
    };

    private boolean canDraw() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    private WindowManager.LayoutParams params(boolean whole) {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                               : WindowManager.LayoutParams.TYPE_PHONE;
        int size = whole ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = dp(28);
        lp.y = dp(22);
        return lp;
    }

    private void showChip(long left) {
        boolean warn = left <= 5 * 60_000;
        if (chip == null) {
            chip = new TextView(c);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(dp(16), dp(8), dp(16), dp(8));
            try { wm.addView(chip, params(false)); } catch (Exception e) { chip = null; return; }
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(30));
        bg.setColor(warn ? 0xE6B4561F : 0xCC1B1430);
        bg.setStroke(dp(2), warn ? 0xFFFFB15C : 0xFFC79BF2);
        chip.setBackground(bg);
        chip.setTextColor(0xFFFFFFFF);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, warn ? 26 : 18);
        chip.setText("🎮 " + (warn ? "باقي " : "وقت اللعب ") + clock(left));
    }

    private void showFull() {
        if (full != null) return;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xE6060C14);
        TextView t = new TextView(c);
        t.setText("🎮 انتهى وقت اللعب");
        t.setTextColor(0xFFFFE7AE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        t.setGravity(Gravity.CENTER);
        TextView s = new TextView(c);
        s.setText("حان وقت الراحة");
        s.setTextColor(0xFFD8CDB6);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(14), 0, 0);
        box.addView(t);
        box.addView(s);
        try { wm.addView(box, params(true)); full = box; } catch (Exception ignored) { }
    }

    private void goHome() {
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    private void hideChip() {
        if (chip != null) { try { wm.removeView(chip); } catch (Exception ignored) { } chip = null; }
    }

    private void hideFull() {
        if (full != null) { try { wm.removeView(full); } catch (Exception ignored) { } full = null; }
    }

    /** h:mm:ss in Arabic digits */
    private static String clock(long ms) {
        long t = (ms + 999) / 1000, h = t / 3600, m = t % 3600 / 60, s = t % 60;
        String x = (h > 0 ? h + ":" + two(m) : String.valueOf(m)) + ":" + two(s);
        StringBuilder out = new StringBuilder();
        for (char ch : x.toCharArray()) out.append(ch >= '0' && ch <= '9' ? (char) ('٠' + (ch - '0')) : ch);
        return "⁦" + out + "⁩";   // isolated, so it reads left to right inside the Arabic text
    }

    private static String two(long n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private int dp(int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
