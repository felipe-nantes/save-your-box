# Save Your Box: notes for Claude

A home screen for Android TV boxes, Google TV and Android TV (Android 7.1 and newer) that replaces the stock one,
plus a settings app that switches between Save Your Box and the original home screen, and a phone remote. A plain
Android app in Java, built without Gradle by `build.sh`. Old no-name boxes (the RK322x "MXQ Pro") get their own
notes under "The code", and the BADBOX check and retro games their own sections.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: Git Bash (what Claude Code runs there; `build.sh` handles it) or WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude` and `longitude` (decimal
   degrees) and `city` for their city, `tmdb_region` (two-letter country), `show_crypto`, `currency` and `temperature` to match.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never commit
   it. If they paste it in the chat anyway, put it only in `config.properties`.
   Without a key everything works except the picks and the screensaver photos.
   The same goes for the optional `watchmode_key` (free, watchmode.com; makes picks open the title's page): theirs, secret, only in `config.properties`.
5. **Build:** `./build.sh` → `SaveYourBox.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r SaveYourBox.apk`. On TCL, also `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`
   (some firmware calls the operation `APP_AUTO_START`; this tries both):
   TCL's `TclAppBoot` blocks background jobs by default, so the switch (and the watchdog) never run. The log shows
   `forbid bind service ... ModeJob`. Some TCL firmware has neither (a 2024 MediaTek Google TV, Android 14, has no
   `TclAppBoot`): both names answer `Unknown operation string`, and nothing needs allowing.
   On a Mac, `adb connect` can fail with `No route to host` while the TV answers (`nc -z <IP> 5555` connects): macOS's
   Local Network privacy blocks the adb server that adb starts in the background. Start it from the same terminal
   instead (`adb kill-server; adb nodaemon server &`), or allow the terminal app under System Settings → Privacy &
   Security → Local Network.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On TCL, the remote's Netflix,
   YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons
   (*Save Your Box* → *Remote Buttons* picks what each opens); *Original* turns it off.
8. **They switch on the TV:** *Save Your Box* → *Home Screen* → *Save Your Box*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
   If the TV's Wi-Fi was saved during its first setup, the switch stops before changing anything and asks them to
   forget the network and connect again in Settings (Network & Internet); then the switch goes through. Saved
   networks out of range don't show in Settings: find the ones with `cname=com.google.android.tungsten.setupwraith`
   in `adb shell dumpsys wifi` (each block starts with its `ID:`), and with their OK remove them with
   `adb shell cmd wifi forget-network <id>`.
9. **Check:** `adb logcat -s SaveYourBox` shows what the switch did.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it.
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s SaveYourBox`, then have them switch back to
  *Original* once and confirm everything returned.

TVs that don't run Google TV or Android TV: the way is an Android box or a Google TV streamer on HDMI (Chromecast
with Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Set Save Your Box up on the streamer.

**Uninstalled while in Save Your Box mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Java 8 language level, minSdk 25 (Android 7.1), targetSdk 33. No Gradle, no libraries: only the Android SDK.
  Android 7.1 (tested on an RK322x "MXQ Pro" box) has no Typeface.Builder (Inter at its default weight, bold drawn
  by Android), no hardware bitmaps, no AdaptiveIconDrawable and no String.join (use TextUtils.join); each Android 8
  call sits in its own nested class (`Ui.Variable`, `Backdrop.Hardware`, `HomeApps.Adaptive`). Installing it there
  clears the default home screen (no HOME role before Android 10): until the first switch, HOME asks which to use.
  That box's adbd answers one TCP connection at a time: while the computer is connected, the app's switch fails with
  "the TV's ADB didn't respond". Run `adb disconnect` before they switch, and connect again after. The same goes
  right after `adb install -r`: the update's own check (`MY_PACKAGE_REPLACED` → `HomeMode.verify`) runs at once, so
  disconnect in the same command and wait a minute before connecting again.
  Such boxes may never run Android's idle compile job (`dumpsys package dexopt` shows `interpret-only` everywhere),
  so every app starts slowly: `cmd package compile -m speed <pkg>` for Save Your Box and the shelf apps, and
  `-m speed-profile` for big ones (`speed` ran out of memory on Spotify); 7.1 has no `bg-dexopt-job`. An update
  (`install -r`) drops Save Your Box back to `interpret-only`: compile it again. Its font is read once there (`Ui.inter`).
  Its clock has no battery: after a reboot it starts at 1970 until NTP answers, and its `ntp_server` was empty
  (set to `a.ntp.br`). Its old time zone data still has Brazil's summer time: São Paulo shows an hour ahead, so pick
  a UTC−3 zone without it. Plain shell can't (`SET_TIME_ZONE`): Home Settings → Time zone sets it through the app's
  ADB as root (`su`, Android 7 only, `HomeMode.setTimeZone`, checked with `getprop`); elsewhere it opens Date & time.
  While the computer holds the ADB it falls back too. Chrome is its WebView: never turn it off.
  Its HDMI out came at 4096x2160p-24 (/cache/display.cfg, the iface=4 line; live: /sys/class/display/HDMI/mode):
  the UI showed at 24 fps. 1920x1080p-60 fixed it (backup in /cache/display.cfg.bak). Its `su` mangles quoted
  `sh -c` commands: push a script to /data/local/tmp and run `su 0 sh` on it.
- The phone remote: `RemoteServer` (a service in the main process, port 8765; Android 8+ as a foreground service)
  serves `assets/remote.html`, its `{{name}}`s filled from `pageText()` (literal `Ui.tr` calls). Only site-local
  addresses, and only with the key in `files/remote-key` (the QR code on the settings screen's left, drawn by `Qr`,
  no library; after the first visit it's a cookie). `RemoteInput` sends keys through the app's own ADB: it finds the
  input device that sends DPAD_CENTER (`getevent -p` and its `.kl`; the shell's `input` group may write there) and
  writes the raw events with one `echo -ne` (checked once), else sendevent, else `input keyevent` (1-2 s on a slow
  box). It closes its connection after 5 s idle, for adbds that serve one connection. The sleep timer (AlarmManager →
  `HomeReceiver` SLEEP) and "turn off" send SLEEP and switch on `hdmi_control_auto_device_off_enabled`, so a box's
  standby turns the TV off over HDMI-CEC. Photos sent from the phone (`Photos`, files/photos, up to 60) replace the
  picks in the screensaver (`DreamView.showcase()`), with no caption. `HomeApps.BOX_APPS` adds the box's mirroring and
  USB player to the drawer.
  The RK322x box's firmware has no CEC driver (no /dev/cec, the HAL logs "open error!", physical address 0xFFFF):
  it can't reach the TV over HDMI. A TCL TV that isn't Android takes keys over the network instead (`TclTv`: TCP
  4123, TCL nScreen's XML `setKey`, found with SSDP and kept in files/tcl-tv). The page then shows Box | TV (the
  arrows go to the one picked), the volume goes to the TV, and "turn off" and the timer turn the TV off too
  (`TR_KEY_POWER`). Off, that TV leaves the network (no Wake-on-LAN): it turns on with its own button.
- `wallpaper=` packs their photo as `assets/wallpaper.jpg` (build.sh, from `out/assets`, so it stays out of git);
  `Backdrop.wallpaper` builds it once per process, sharp, and `HomeView.showBackdrop` uses it instead of the picks'.
  Android 9 and older have no RenderNode (`Recording` wraps it, with a Picture there) and no `cmd role`. On Android 8 to 11
  (`Ui.LEGACY`) the home screen skips the GPU layers, draws blurred shadows from cached bitmaps (`Shadows`) and
  rounds images with a shader instead of clipPath: their GPUs (a Mi Box 3's Mali-450) choked on those.
  Android 11 runs the screensaver as `ScreensaverAlone`, in the main process (its DreamActivity needs the dream in the
  app's default process, without a home activity); `res/values-v30/bools.xml` picks it, every other version keeps
  `Screensaver` in `:home`. Keep both in step.
  To check for newer APIs, compile against `platforms/android-25/android.jar`: only AGSL (`RuntimeShader`, guarded
  by a try/catch), `BitmapShader.setFilterMode` (guarded), `Recording.Node`, `Ui.Weighted` (the Android 9 font weight)
  and the Android 8 nested classes above should come up.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 8 to 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- Some TVs draw their UI at 1280×720 on a 1080p panel (TCL's 1 GB models: `ro.surface_flinger.max_graphics_width`
  and `_height`, `ro.config.low_ram`). There every pixel and every MB counts: `Backdrop` builds its images at the UI's
  size (and the picks use TMDB's w1280), and the shelf logos have a copy at the resting size and one at the focused
  size, so both draw pixel for pixel.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
- On-screen text: `Ui.tr("English")`, translated from `app/assets/i18n/<lang>.json` (keyed by the English; one
  HashMap lookup, English when a language lacks it). Text built at runtime is a pattern: `Ui.trf("{n} min ago", "n", 12)`,
  never concatenated words. A key `"context|Text"` is the same English where a language may need another word
  (`"unit|Automatic"`, `"format|h:mm"` holds a SimpleDateFormat pattern). A count with a word is
  `Ui.plural("plural|one:{n} season|other:{n} seasons", n)`: each catalogue gives one form per CLDR plural category of
  its language (`one`/`few`/`many`/`other` in Russian, Ukrainian, Polish; only `other` in Japanese, Chinese…), and
  ICU's rules pick one. Portuguese, Spanish, German and Russian are hand translations: the reference, edited by hand,
  never replaced with machine ones. Dates and numbers use `Ui.locale()`. Text
  drawn every frame is cached and made again when `Ui.generation` changes. After changing text, run
  `python3 tools/translate.py`: it rebuilds `en.json` from the code and lists what each language lacks; translate those
  in every catalogue (or `--fill` with `ANTHROPIC_API_KEY`, which sends only the strings), and keep placeholders as they
  are. Run `python3 tools/translate.py --check` to validate all catalogues without writing files.
  No right-to-left languages yet (the layout isn't mirrored); CJK and Devanagari use the system font.
  Comments in English, short, only where the why isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
  What the person changes on the TV (`Prefs`: Home Settings, Shelf, Remote Buttons) wins over those first values.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`) use each brand's key codes: they run only on TCL,
  Vestel, KTC (NextStar) and Sony TVs and Formovie projectors, the service is on only in Save Your Box mode, and *Original* takes it out of the enabled accessibility
  services. The system waits for the service on every key, so it lives alone in the `:keys` process: what it reads is
  in `remote-keys.txt` (`Prefs.keyApp`), never in SharedPreferences, and `onKeyEvent` does no heavy work.
  Vestel (F&U and other store brands; MediaTek, Android TV 14 tested) maps its Netflix button to `KEYCODE_STEM_1`, a
  global key (`dumpsys window policy` lists them) that the key filter sees first; its YouTube, Prime Video and Google
  Play buttons stay with the system. The stock lists needed no Vestel entries.
  KTC (NextStar; MediaTek, Google TV 14 tested) maps its Tabii button to `KEYCODE_BUTTON_8`, also a global key, which
  KTC's own hot-key receiver would open Tabii with; its Netflix, YouTube and Prime Video buttons stay with the system.
  Sony (BRAVIA VH22, Android TV 12 tested) sends `KEYCODE_BUTTON_9` for its BRAVIA CORE, Disney+, Prime Video,
  YouTube and Crunchyroll buttons, `KEYCODE_BUTTON_4` for Netflix and `KEYCODE_BUTTON_8` for the Google Play button of
  older remotes (BRAVIA 4K UR3, Android 10 tested), global keys for `com.sony.dtv.sonyvendorservice`;
  only the scan code tells them apart, so `Prefs` keys them as `SONY_KEY` + scan code. "Sony default" (empty) leaves
  the key to the TV. Sony ships its own Apple TV build, `com.apple.atve.sony.appletv`.
  Xiaomi projectors made by Formovie (Fengmi; L1, MediaTek, Google TV 14 tested) send the same `KEYCODE_STEM_1` for
  Netflix (`RemoteKeys.STEM_NETFLIX`); their YouTube button goes to MediaTek's `GlobalKeyReceiver` and stays with the system.
- After any change, `./build.sh` must end with `Built SaveYourBox.apk`.
## Retro games (optional)

RetroArch's Play Store page stays blank on an uncertified box, so: the official `RetroArch_ra32.apk` (32-bit ARM
boxes; `aarch64` for 64-bit) from buildbot.libretro.com/stable (check it's signed `O=Libretro`); a big APK, so
`adb push` it and `pm install` on the box. Cores come from the URL RetroArch itself uses
(`core_updater_buildbot_cores_url` in its retroarch.cfg: .../nightly/android/latest/armeabi-v7a/, `<core>_libretro_android.so.zip`);
on a rooted box they go straight into `/data/user/0/com.retroarch.ra32/cores/`, owned by the app's uid (the RK322x box
has SELinux disabled; elsewhere use its Online Updater). Light cores for these chips: fceumm, snes9x2010,
genesis_plus_gx, gambatte, mgba, pcsx_rearmed. `rgui_browser_directory` (RetroArch closed: it rewrites the file on
exit) points at /storage/emulated/0/ROMs. Games: homebrew or their own cartridges only.

## Checking a box for BADBOX

Off-brand boxes ("MXQ Pro 5G", T95, X88…) are named in the BADBOX reports (FBI PSA I-060525-PSA, June 2025; Google's
suit, July 2025). Read-only checks, then tell them plainly what you found and what it can't prove (malware in the
firmware can hide; if in doubt, the box goes):

- Packages and files: `pm list packages` for `com.hs.app`; root `find` (push a script: its `su` mangles quotes) for
  `libanl.so`, `p.jar`, `q.jar` outside the system's own folders.
- Servers: what `/proc/net/tcp` shows connected (map uids with `dumpsys package`), and whether anything resolves
  `catmore88.com` or `ipmoyu.com`.
- The FBI's warning signs: not Play Protect certified (the Play Store logs "GMS device compliance query failed"),
  unofficial app stores (FlyMarket, "GetApps"), "free content" apps, a request to turn off Play Protect
  (`settings get global package_verifier_enable`).
- Pre-installed updaters with install rights (Adups `com.adups.fota`, Ragentek): in `HomeMode.DEBLOAT`.
- Then: the router's guest network, apps only from their makers, and the network ADB off when it isn't needed.

## Always

- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and Original can't bring them
  back (`wifiOwners` in `HomeMode`).
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
