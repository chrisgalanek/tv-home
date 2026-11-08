package com.mihir.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: the art slideshow (ArtSlideshow). Left / right on the remote step through
 * pictures; any other button wakes the TV. Chosen in the TV's Settings → Screen saver, or
 * set over ADB by build-and-install.sh.
 */
public class ArtDream extends DreamService {
    private ArtSlideshow show;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Interactive so the remote's arrows can step through pictures (ArtSlideshow.handleKey);
        // any other button still wakes the TV (dispatchKeyEvent below).
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        ArtSlideshow.fullBrightness(getWindow());
        show = new ArtSlideshow(this);
        setContentView(show.view());
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (show != null && show.handleKey(e)) return true;
        int k = e.getKeyCode();
        if (k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || k == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == android.view.KeyEvent.ACTION_UP) wakeUp();
        return true;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        show.start();
    }

    @Override
    public void onDreamingStopped() {
        show.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (show != null) show.stop();
        super.onDetachedFromWindow();
    }
}
