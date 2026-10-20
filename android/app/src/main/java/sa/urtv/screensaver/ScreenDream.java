package sa.urtv.screensaver;

import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * The system screensaver: starts on the TV's own idle timer. OK on the remote turns the Quran channel's
 * live stream on and off (it starts off); the volume keys change the volume; any other key wakes the TV.
 */
public class ScreenDream extends DreamService {
    private WebView web;
    private QuranWindow quran;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);   // keys reach dispatchKeyEvent below instead of waking the TV at once
        setFullscreen(true);
        setScreenBright(true);
        FrameLayout root = new FrameLayout(this);
        web = ScreenView.create(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        quran = new QuranWindow(root);
        setContentView(root);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getAction() == KeyEvent.ACTION_UP) quran.toggle();
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.dispatchKeyEvent(e);
            default:
                if (e.getAction() == KeyEvent.ACTION_UP) wakeUp();
                return true;
        }
    }

    @Override
    public void onDetachedFromWindow() {
        if (quran != null) { quran.hide(); quran = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDetachedFromWindow();
    }
}
