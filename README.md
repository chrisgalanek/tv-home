# tv-home

**Make a slow, ad-filled Android TV fast and clean again.** Debloat it over Wi-Fi and give it a
tiny Apple TV-style home screen with rotating nature wallpapers. You can hand the whole thing to
Claude Code with one prompt, or do it by hand with the scripts here.

![Before: Google TV home with a sponsored hero. After: clean dock over a Lake Louise wallpaper](before-after.png)

On a 2 GB TCL Google TV this disabled 25 bloat apps and cut the home screen's memory use from
192 MB to 83 MB. Everything is reversible: packages are disabled, never uninstalled.

## Do it with Claude Code (easiest)

1. On the TV: Settings → System → About → press OK on **Build** 7 times → back → Developer options
   → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.
2. Clone this repo, open Claude Code inside it (so it can reuse the launcher and scripts), and paste:

   ```text
   My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
   disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
   about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
   screen with a dock of my favourite apps and rotating nature wallpapers. Back up before changing
   anything, check every step with screenshots from the TV, and give me a restore script.

   Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
   Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
   test the Home button from inside an app.
   ```

3. Accept "Allow USB debugging?" on the TV when asked, then answer a few questions: which apps you
   use, which go in the dock, whether you want wallpapers.

The prompt is short on purpose; Claude works out the details. The gotchas below are there in case it gets stuck.

## Do it by hand (macOS)

```bash
./setup.sh                                                  # Python ADB client, JDK 17, Android SDK
tools/find-tv.sh                                            # finds the TV, saves its IP
tools/venv/bin/python -I tools/tv.py --probe                # model, Android version
tools/disable.sh examples/tcl-google-tv-g07/debloat.txt     # or your own list, see examples/
launcher/build-and-install.sh                               # build + install the launcher
```

Undo everything with `tools/restore.sh <the same list>`.

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen. 4.7 MB including the wallpapers; plain Android, no network, no background services |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Dock apps, hidden apps, Live TV app, grid columns |
| [tools/tv.py](tools/tv.py) | Pure-Python ADB client: shell, batch, screenshots, chunked APK push |
| [tools/find-tv.sh](tools/find-tv.sh) | Finds Android TVs on your Wi-Fi |
| [tools/type.sh](tools/type.sh) | Type into the TV's on-screen keyboard from your laptop (`tools/type.sh` alone prompts hidden, for passwords) |
| [tools/disable.sh](tools/disable.sh), [tools/restore.sh](tools/restore.sh) | Apply / undo a debloat list |
| [examples/](examples/) | Debloat lists per TV model |

## The launcher

- Clock, greeting and date top-left. Top-right, a **Search** pill (see below) and round icon
  buttons whose label slides out on focus: **Get apps** (the Play Store; new installs appear in the grid on their own),
  **Screensaver** (the art slideshow now, starting on the current picture; any button returns),
  **Sleep** (the TV's own sleep timer, 15 to 120 minutes; shown on Sony sets, since only the
  manufacturer's app may turn the TV off; while a timer runs the button shows the time left, read
  from Android's alarm list, which needs the DUMP and usage-access permissions the install script
  grants), **Wi-Fi** (shows the network name when Location is on),
  **Inputs** (HDMI/AV/Live TV dropdown; the remote's Menu key opens it too) and **Settings**.
- In any app, hold **Up** on the remote and a transparent bar slides down from the top with the same
  buttons (Home, Search, Get apps, Screensaver, Sleep, Wi-Fi, Inputs, Settings); Back, Down or a few
  idle seconds close it, and a short press of Up still goes to the app. After Sleep, Wi-Fi,
  Settings and the like it returns you to the app you were in (Home and Inputs stay home). It's an accessibility
  service, so switch it on once: Settings → System → Accessibility → **Home: top bar**.
- A dock of your favourite apps over a glow coloured from the focused app's banner. If you've
  watched something in that app, its artwork fades in behind the dock with **Continue · App**, the
  episode and the show; otherwise just the app's name shows. The top of the next row peeks in at
  the bottom of the screen; press Down for it and for every other app in a grid (6 across).
- **Recently watched**: the last 10 shows you played, in a row under the dock (press Down), with a
  small app icon in the corner and a progress bar when the app reports how far in you are. When an
  app sends no artwork, the launcher looks the show up on TMDB (exact title matches only) and uses
  its backdrop, if Search has a key. Apps
  report what's playing (title, show, artwork) for the remote and Assistant; the launcher's
  notification listener records it, and the install script grants that access over ADB. It only
  covers apps that publish those details, and history starts when the launcher is installed. OK
  goes back into the show while its app is still running, otherwise opens the app; long-press for
  Search in the app, Search all apps, or Remove from list. Some apps (Peacock) report only the
  episode title, not the show: long-press **Choose the show**, search for it and pick it, and the
  tile gets the show name and the episode's picture. Later episodes from that app are matched to
  shows you've picked on their own. Stored on the TV only.
- The background crossfades to a new picture after a random 2 to 10 minutes, in shuffled order
  (every picture once before any repeats, shared with the screensaver). Move up to the top bar and
  **‹ Previous picture** and **Next picture ›** fade in at its left end (Left from Search); they fade
  out again when you move down. By default it's 90 pictures of
  historic Christian art: paintings (Fra Angelico, Leonardo, Michelangelo, Caravaggio, Rembrandt),
  sculpture (Michelangelo's Pietà, David and Moses, Bernini, the Veit Stoss altarpiece), stained
  glass, mosaics and icons, and churches inside and out, from St Mary's Basilica in Kraków to St
  Peter's. Each has a small caption above the dock (title, artist and date, where it is), and bright
  pictures are dimmed so the clock and dock stay readable. Set `wallpaper_set` in `config.xml` to
  `nature` for the 12 landscape photos, or `both`. Pictures are 1920×1080 JPEGs in
  `launcher/assets/wallpapers/`; credits in `launcher/WALLPAPER-CREDITS.md`.
- New pictures arrive on their own: once a day the launcher adds up to 6 freely licensed pictures
  of Christian paintings, sculpture, stained glass, mosaics and churches inside and out from
  Wikimedia Commons (photos from its "Quality images"), framed like the built-in set and captioned
  with title, photographer or artist, and licence. It keeps the newest 150 (on the TV in the app's
  files, with credits.tsv listing each source).
- The screensaver shows the same pictures full screen and at full brightness: it starts on the one
  the home screen is showing, then crossfades to another every 40 seconds, each shown whole, with
  its caption and the time in the bottom corners. Left and right on the remote step to the previous and next picture
  (round ‹ › buttons fade in at the edges, then away); any other button wakes the TV. The
  Screensaver button in the top bar works the same way. The install script sets it as the TV's screensaver ("Christian art");
  to go back to Google's Ambient mode, run
  `tools/batch.sh <<< 'settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop'`.
- Deterministic D-pad focus, remembers the last tile, picks up newly installed apps on return.

### Search

Type a movie, show or person and the **Top result** comes first, big (smaller while the keyboard
is up, so it stays above it) (the exact title match, most popular first, so the original beats its spin-offs)
with its backdrop and description; the rest follow in rows by kind (**Shows**, **Movies**,
**People**) with two-line titles. Titles without artwork show their name on a colour. Pick a person
to see their movies and shows.

Pick a title to see where it streams. One big button opens the best choice among your installed
apps (one that includes it with your subscription or for free, preferring one that can open the
title itself). **Other ways to watch** lists the rest as smaller buttons with the service's logo
and Subscription, Free, Rent or Buy. Apps you have are a see-through light green that fades out to the right; services you don't have are outlined, marked **Get**, and open
the Play Store searching for that app. **▶ Watch on …** opens the title itself: for Apple TV, Hulu,
Tubi, Paramount+ and Prime Video, the launcher looks the title up on
[Wikidata](https://www.wikidata.org/), which records many titles' IDs on those services, and opens
the app on it. **▶ Open in …** opens the app instead (on its search for the title, if it supports
that), either because the app can't take a link or because Wikidata doesn't have that title's ID
yet. Back on the remote returns to the results. Data comes from [TMDB](https://www.themoviedb.org/), with streaming availability from
JustWatch, so Search is the one part of the launcher that goes online.

It needs a free TMDB API key: make an account, go to Settings → API, request a key, and save the
API key (or the longer read access token) on its own in `launcher/tmdb-api-key.txt`. That file is
git-ignored; `launcher/build-and-install.sh` builds the key into the APK.

Change the dock on the TV: long-press OK on any app. In the grid you get **Add to dock** (up to 7
apps); on a dock app, **Move left**, **Move right** and **Remove from dock**. Both menus also have
**App info** (force stop, clear data), and apps you installed yourself also get **Uninstall**,
which opens Android's own confirmation. Apps that came with the TV can only be disabled, from App info. Changes are saved on the TV and survive
reinstalls. `config.xml` only sets the dock the launcher starts with; to go back to it, run
`tools/batch.sh <<< 'pm clear com.mihir.tvhome'`.

## Gotchas we hit

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) has a higher HOME priority than any third-party
  launcher. The install script disables it; `restore.sh` re-enables it.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The install script does this.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.
- **Unsplash blocks scripted downloads** with a bot check. The wallpapers come from Wikimedia
  Commons Featured Pictures instead.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11). Add yours to `examples/`.

## Credits

Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are
from Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Code is MIT, see [LICENSE](LICENSE).
