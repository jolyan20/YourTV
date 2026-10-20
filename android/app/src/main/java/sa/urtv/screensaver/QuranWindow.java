package sa.urtv.screensaver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * The Quran channel's live stream from YouTube in a small window at the top centre of the screensaver.
 * Off until the remote's OK turns it on; OK again turns it off. YouTube's player stays visible, as its
 * terms require. It runs in its own WebView with an https address, because YouTube will not play an
 * embed whose page has no web origin (the screensaver page itself is a file).
 */
final class QuranWindow {
    /** قناة القرآن الكريم (youtube.com/@SaudiQuranTv): its current live stream, whatever the video id */
    private static final String CHANNEL = "UCos52azQNBgW63_9uDJoPDA";
    private static final String ORIGIN = "https://jolyan20.github.io/YourTV/";

    private final FrameLayout root;
    private WebView player;

    QuranWindow(FrameLayout root) { this.root = root; }

    boolean isOn() { return player != null; }

    void toggle() {
        if (player == null) show(); else hide();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void show() {
        Context c = root.getContext();
        WebView w = new WebView(c);
        w.setBackgroundColor(0xFF000000);
        w.setFocusable(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setWebChromeClient(new WebChromeClient());
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // OK on the remote is the gesture
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(0xFF000000);
        frame.setCornerRadius(dp(c, 10));
        frame.setStroke((int) dp(c, 1.5f), 0x99DCB86F);
        w.setBackground(frame);
        w.setClipToOutline(true);
        int width = (int) (root.getWidth() * 0.25f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, width * 9 / 16, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = (int) (root.getHeight() * 0.035f);
        root.addView(w, lp);
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style>"
            + "</head><body><iframe allow='autoplay; encrypted-media' src='https://www.youtube.com/embed/live_stream?channel="
            + CHANNEL + "&autoplay=1&controls=0&modestbranding=1&rel=0&playsinline=1'></iframe></body></html>";
        w.loadDataWithBaseURL(ORIGIN, html, "text/html", "utf-8", null);
        player = w;
    }

    void hide() {
        if (player == null) return;
        root.removeView(player);
        player.destroy();
        player = null;
    }

    private static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }
}
