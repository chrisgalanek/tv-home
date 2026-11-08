package com.mihir.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: the art slideshow (ArtSlideshow). Chosen in the TV's Settings → Screen saver, or
 * set over ADB by build-and-install.sh.
 */
public class ArtDream extends DreamService {
    private ArtSlideshow show;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        ArtSlideshow.fullBrightness(getWindow());
        show = new ArtSlideshow(this);
        setContentView(show.view());
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
