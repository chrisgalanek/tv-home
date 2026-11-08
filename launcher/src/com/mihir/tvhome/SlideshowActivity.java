package com.mihir.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/**
 * The screensaver on demand (the home screen's Screensaver button). Apps can't start the TV's
 * real screensaver, so this shows the same slideshow full screen. Left / right on the remote step
 * through pictures; any other button goes back.
 */
public class SlideshowActivity extends Activity {
    private ArtSlideshow show;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        ArtSlideshow.fullBrightness(getWindow());
        show = new ArtSlideshow(this);
        setContentView(show.view());
    }

    @Override
    protected void onResume() {
        super.onResume();
        show.start();
    }

    @Override
    protected void onPause() {
        show.stop();
        super.onPause();
        finish(); // leaving it (Home, another app) ends it, like a screensaver
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (show.handleKey(e)) return true; // left / right: previous / next picture
        // Any other button wakes it, like the real screensaver. Volume still works.
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
