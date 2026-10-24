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
