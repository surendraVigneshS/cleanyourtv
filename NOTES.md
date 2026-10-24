# Mi TV Home: working notes

TV: Xiaomi MiTV-AXSO0 (dangal), Android TV 9 (SDK 28), firmware PI.6036, 1 GB RAM, Amlogic/droidlogic.
Fork of github.com/Gathin23/cleanyourtv. Target design: `design/SPEC.md` and the "Glass (ours)" tab of
`design/mitv-home.html`.

Branches:
- `glass-home`: the launcher restyle (this file lives here).
- `tv-state`: TV backups, the applied list and the command log, checked out as a separate worktree at
  `../mitv-home-tv-state`. It holds getprop and settings dumps (serials, Android ID), so it stays local and is not
  pushed to the public fork.

## Phase 0: how the reference launcher works

Package `com.gathin.tvhome`, plain Java, AndroidX RecyclerView + DynamicAnimation, minSdk 26, no INTERNET.
Almost all of Home is `MainActivity.java` (3.4k lines) and `AppAdapter.java`.

**App discovery.** `MainActivity.reloadTiles()` runs one `queryIntentActivities(ACTION_MAIN +
CATEGORY_LEANBACK_LAUNCHER)`; `AppAdapter.reload()` turns it into tiles, adding LAUNCHER-only apps the owner put in
through the App library (`LibraryApps`), dropping `hidden_apps`, ordering by `dock_apps` then the saved `Order`
(tile Move menu). Banners via `loadBanner`, icons as fallback; a colour is sampled per tile for the glow
(`ambientColorAt`). Launch: `getLeanbackLaunchIntentForPackage`, or `launch_actions` overrides. Package
add/remove broadcasts mark tiles stale (`TileRefresh`) and they rebuild on the next resume.
App library: `AppLibraryDialog` (every LEANBACK + LAUNCHER app, add/remove to Home).
Tile menu: hold OK on a tile (`tile_menu`), "Move" starts reorder mode with the tile picked up.

**Input switching.** `MainActivity.openInput(inputId)`: optional vendor `input_sources`/`input_action` mapping,
else `ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput(inputId)` with type
`vnd.android.cursor.item/channel`, aimed at `live_tv_package` first (avoids a chooser), then any handler, then
`input_action`. The Inputs dropdown (`showInputs`) lists `TvInputManager.getTvInputList()` passthrough inputs
(HDMI first), folds CEC child inputs into their port, reads `getInputState()` (ignored if every port claims
CONNECTED, a Sony quirk), and uses `loadCustomLabel()` for names set in Settings > Inputs.
On this TV: HDMI 1/2/3 = `com.droidlogic.tvinput/.services.Hdmi{1,2,3}InputService/HW{5,6,7}`,
AV = `.../AV1InputService/HW1`, tuner `.../ADTVInputService/HW16`; `com.android.tv` handles the passthrough URI
(verified: HDMI 2 opened with `am start -a VIEW -d content://android.media.tv/passthrough/...HW6`).

**Themes/config.** `Config.load()` reads `res/values/config.xml`, then overlays `config.json` from
`getExternalFilesDir(null)` = `/sdcard/Android/data/com.gathin.tvhome/files/` (copied to internal storage so it
survives unmounted storage). Every key optional, bad types ignored. Wallpaper: `wallpaper.jpg` or a
`wallpapers/` collection (jpg/png/webp). `tools/theme.sh themes/<name>` pushes through `ThemeProvider`
(only the names in `ThemeFiles.path` are writable) and restarts Home.

**Focus and animation.** One shared `GlassView` highlight moves between tiles with two `SpringAnimation`s
(x/y; stiffness 260-520, damping 0.58-1 depending on `focus_overshoot`/`advanced_effects`; `Motion.reduce` for
`reduced_motion`). The focused card scales to `AppAdapter.focusScale()` (1.10, or 1.12 with advanced effects)
with `view.animate()`. `onTileFocused` sizes and places the glass, picks the accent, parks the dock row and scrolls
the grid (`requestChildRectangleOnScreen` override). Focus memory is the static `lastFocused`/`lastFocusedKey`.

**Build.** JDK 17, Android SDK platform 34, build-tools 34.0.0, Gradle 8.9 (wrapper), AGP 8.5.2.
```
cd launcher
./gradlew --no-daemon assembleRelease                # app/build/outputs/apk/release/app-release.apk
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease   # what CI runs
```
Release is R8-minified, shrinkResources, debug-signed. Repo CI gate: APK < 512 KB.

Toolchain changes made on this laptop (no code changes): the Gradle wrapper download timed out under Java
(curl was fine) - the JVM was trying IPv6 first. Fixed by running with
`GRADLE_OPTS=-Djava.net.preferIPv4Stack=true JAVA_OPTS=-Djava.net.preferIPv4Stack=true`. AGP then installed
build-tools 34.0.0 and platform 34 into the existing SDK by itself (licences were already accepted). The
unmodified build succeeded: `app-release.apk` 509,389 B.

## Phase 1: debloat

TV found at 192.168.31.60:5555 (`ro.adb.secure=1`). No `tools/find-tv.sh` exists in the repo and `tv.py` has no
discovery, so it was found with a port-5555 sweep of the /24.

Home on this firmware: `com.google.android.tvlauncher` owns HOME and starts PatchWall
(`com.mitv.tvhome.atv/.app.PatchWallActivity`) on top of itself. With PatchWall disabled, Home is plain Google TV.

Disabled (29, all `pm disable-user --user 0`, list with comments in `tv-state/disable.txt`): Xiaomi/Google
telemetry (4), PatchWall + content/ad rows + Play auto-install configs + Mi TV+ + Sensara (9), TV Manager,
handbook, update service (3), Mi Cast/MiLink, smartshare, Miracast sink (3), SonyLIV x2, Mi Music, Play Music,
YouTube Music, Play Movies, Play Games, Mi Gallery, MX Player, VLC (10). Mi Store is not installed.
`disable.sh` -> `restore.sh` (0 left disabled, Home back to tvlauncher) -> `disable.sh` all exited 0; none of the
script's guards (Home, TV_INPUT_HARDWARE, input/Settings handlers) skipped anything. After a reboot all 29 stayed
disabled (the firmware re-enabled nothing).

Kept on purpose although the example list disables them: Chromecast built-in, Google voice (katniss, pumpkin),
Sun NXT.

Skipped (unsure), left enabled: `com.xiaomi.android.TV.audio` (AudioDebugTool), 
`com.xiaomi.android.tvsetup.partnercustomizer`, `com.google.android.partnersetup`, `com.mitv.videoplayer`
(may play USB video), `com.mitv.dream` (screensaver), `com.mitv.tvlock` (parental lock),
`com.google.android.backdrop` (ambient screensaver), `com.xiaomo.tv.milegal` (legal text, outside the agreed
categories), `com.google.android.leanbacklauncher` (firmware re-enables it at boot anyway).

Keep-list check after reboot: YouTube, Netflix, Prime Video, JioHotstar (`in.startv.hotstar`), ZEE5
(`com.graymatrix.did`), JioCinema/Jio STB (`com.jio.media.stb.ondemand`), Play Store, Live TV (`com.android.tv`),
Sun NXT each started with `am start` and became the resumed activity; TV Settings opened via
`android.settings.SETTINGS`. All 28 keep-list packages (incl. GMS, GSF, WebView, mediashell, Gboard, Bluetooth,
remote service, droidlogic TV input/Live TV, mitv.service, tvlauncher) report enabled.

RAM, from `/proc/meminfo` (the fair comparison; meminfo "Free RAM" after was taken right after launching 10 apps,
which inflates cached PSS):

| | before (PatchWall Home) | after debloat + reboot |
|---|---|---|
| MemAvailable | 159.8 MB | 206.3 MB (+46.5 MB) |
| MemFree | 49.6 MB | 76.3 MB |
| dumpsys meminfo Free RAM | 146.7 MB | 342.3 MB (see note) |
| dumpsys meminfo Used PSS | 543.1 MB | 553.5 MB (see note) |

## CEC on HDMI 2 (Jio set-top box)

- `hdmi_control_enabled` is 0 on this TV (CEC off in settings). All three ports report `cec: true`.
- Test: tuned HDMI 2 through TIF, set `hdmi_control_enabled=1` for the test, waited ~30 s. The TV took logical
  address 0 and broadcast Report Physical Address / Request Active Source; **nothing answered** - no `[R]`
  messages, no device in the CEC list. The Jio box does not speak CEC (or has it off).
- Sending a key: `IHdmiControlService.sendKeyEvent(TV, KEYCODE_INFO)` refused with "Neither user 2000 nor current
  process has android.permission.HDMI_CEC". `HDMI_CEC` is signature|privileged, so a sideloaded launcher can never
  send CEC user-control keys through HdmiControlManager, CEC device or not.
- Setting restored to 0 afterwards.
- **Decision for Phase 2:** the Zee TV tile switches to HDMI 2 and stops there. `zee_channel` stays in the config
  for the record but is not sent.

## Phase 2: glass Home (branch glass-home)

What changed: `GlassHome` is the HOME activity; `MainActivity` (the grid/dock Home) stays in the APK without the
HOME filter. New: `GlassHome`, `GlassTile` (one view per tile, whole tile in one onDraw), `TileRow` (track, focus
memory, slide), `WashView` (accent wash), `GlassConfig` (SPEC section 9 keys), `InputSwitch` (input switching moved
out of MainActivity unchanged, plus port lookup). Kept: Config + theme overlay, leanback launch intents and
`launch_actions`, App library (`AppLibraryDialog`, `LibraryApps`), tile Move menu (`Order`), `SystemSettings`.

- Layout units: the TV panel is 1280x720 at density 213, so the spec's 1920x1080 design px are scaled by
  width/1920 at runtime (one design px = 0.667 device px here).
- Fit: with SPEC's exact sizes the TV row would end ~59 px below the safe area (the mockup overflows the same way).
  Rows clip only sideways, so the 1.12x zoom/ring spills into the 34 px gaps instead of needing padding; the focused
  TV tile now ends at 1013 of the 1026 px safe bottom. Tiles start 42 px into the row so the first tile, zoomed with
  its ring, keeps the 20 px margin; row labels line up with the tiles.
- Glass, the cheap way: wallpaper blurred offline (`tools/glass_assets.py`, 1920x1080 WebP, 16 KB) and decoded once
  at screen size off the main thread with the scrim painted in; tile shadows are baked nine-patches; no RenderScript,
  no runtime blur, no video. Fonts: Outfit and Manrope variable TTFs in res/font, system sans-serif as fallback.
- Icons: `getApplicationIcon()` on the background thread, rendered at slot size with rounded corners, kept in an
  LruCache for the process. Accent: config, else Palette dominant colour, else #5b6a80.
- Inputs row: HDMI 1-3 and AV from `TvInputManager` (CEC child inputs fold into their port); labels from
  `input_labels`, else the name set in Settings > Inputs; the dot follows `TvInputCallback.onInputStateChanged`,
  registered only while Home is visible.
- TV row targets (verified on this firmware): Settings -> `android.settings.SETTINGS` (TvSettings MainSettings);
  Network -> `WIFI_SETTINGS` (NetworkActivity); Sound -> Xiaomi `com.xiaomi.mitv.settings.sound.SoundActivity`
  (`SOUND_SETTINGS` has three handlers here, which would show a chooser); Picture and Sleep -> general Settings.
- Zee TV: switches to HDMI 2 only (see CEC above); toast "Switching to HDMI 2 · Zee Tamil".

Build: `cd launcher && ./gradlew --no-daemon assembleRelease lintRelease` -> lint 0 errors. Release APK 627 KB
(budget 2 MB; above upstream's 512 KB CI gate, mainly Manrope's 165 KB - only matters for an upstream PR).

## Phase 3: verified on the TV (installed, started with am start, not Home yet)

Every tile pressed through the remote's key path (`input keyevent`), result read from `dumpsys activity`:

| Tile | Opens |
|---|---|
| YouTube / Netflix / Prime Video / Hotstar | the app's own main activity |
| Zee TV | Live TV on `Hdmi2InputService/HW6` |
| Live TV | `com.android.tv/.MainActivity` (resumes its last input) |
| All apps | App library popup |
| HDMI 1 / 2 / 3 / AV | Live TV on HW5 / HW6 / HW7 / AV1 HW1 |
| Settings / Network / Sound | MainSettings / NetworkActivity / Xiaomi Sound |
| Picture / Sleep | MainSettings (fallback) |

Navigation: Right to the end slides the Apps track; Down lands on each row's remembered column; Back returns to
Apps, first tile. Hold OK -> Move / App info menu; Move + Right + OK reordered YouTube and Netflix and the order
survived a force-stop (then moved back).

Launcher PSS at idle, `tools/bench.py`'s `pss_of` (dumpsys meminfo TOTAL), 5 samples 30-50 s after a cold start:
**30-32 MB** (31,506 KB: Java 3.0 MB, native 8.5 MB incl. the 3.7 MB wallpaper, code 7.8 MB). Budget 60 MB.
Stock tvlauncher on the same TV: 78.8 MB. The full `bench.py` run (key latency, cold start, return to Home) needs
the launcher to be Home and is run after the handover.

## Handover to the glass Home

- `cmd package set-home-activity` printed Success and changed nothing: tvlauncher's HOME filter has system priority 2,
  any sideloaded launcher gets 0, and Android 9 only consults the preferred Home between equal priorities. So the
  stock launcher cannot stay enabled *and* lose Home. With the owner's agreement it is disabled (not removed) through
  its own list, `tv-state/home-takeover.txt`, applied with `tools/disable.sh` (whose Home guard allowed it because our
  launcher answers HOME), then `set-home-activity com.gathin.tvhome/.GlassHome`.
- Reboot: TV boots into GlassHome (`mFocusedApp` / `mCurrentFocus` = GlassHome), 30 packages disabled.
- `tools/restore.sh tv-state/home-takeover.txt` re-enabled tvlauncher and Home resolved to it again (HOME showed Google
  TV); re-applied afterwards.
- Real remote, pressed by the owner: Home, Input/Source, Netflix/Prime/YouTube buttons, mic and Settings all work.
- Keep list at the end: 27 of 28 enabled; the 28th, `com.google.android.tvlauncher`, is the deliberate handover above.

`tools/bench.py --keys 24 --series 2 --cold 3 --returns 5 --idle 60` with the glass Home as Home:

| | |
|---|---|
| idle PSS | **28 MB** (budget 60 MB; stock tvlauncher 78.8 MB) |
| key response p50 / p95 | 23 / 33 ms |
| focus movement p50 / p95 | 215 / 483 ms (180 ms zoom + 280 ms track slide by design) |
| jank | 3.2 % of 897 frames |
| cold start p50 | 412 ms |
| return to Home p50 | 66 ms |
| launcher idle CPU | 0.0 % |

## Known gaps

- Zee TV cannot tune the channel: no CEC device answers on HDMI 2 and CEC keys need a privileged permission.
  It switches to HDMI 2 only; `zee_channel` is stored but unused.
- Picture and Sleep open the general Settings screen (this firmware has no working picture or sleep-timer
  intent); their hero line still reads "Opens the system ... screen".
- HDMI 2 (Jio box) shows no signal dot while the box reports standby; the dot follows TvInputManager only.
- Strings for the glass Home are English only; other locales fall back to English.
- APK is 627 KB, above upstream's 512 KB CI gate (fine for this fork's 2 MB budget).
- Two ADB keys were authorised during the session (tools/adbkey and the laptop's ~/.android/adbkey): revoke them.

## How to restore everything

From `C:\Users\Surendra\Projects\mitv-home` in Git Bash, with network debugging turned back on
(Settings > Device Preferences > Developer options):
```
export TV_HOST=192.168.31.60
tools/restore.sh ../mitv-home-tv-state/tv-state/home-takeover.txt   # stock launcher back, and Home with it
tools/restore.sh ../mitv-home-tv-state/tv-state/disable.txt         # the 29 debloated packages back
tools/theme.sh --reset                                               # optional: remove the glass theme overlay
```
Single app: `tools/tv.py 'pm enable com.xiaomi.mitv.updateservice'` (e.g. to check for a firmware update).
Remove the launcher entirely, after the first line above: `tools/tv.py 'pm uninstall com.gathin.tvhome'` (it is
our own app, not a system package). The recovery records `disable.sh` wrote are in `~/.cleanyourtv/`.
