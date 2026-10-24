# Mi TV Home: design and behaviour spec

Target: Xiaomi MiTV-AXSO0 (Mi TV 4C Pro / 4A Pro), Android TV 9, 1 GB RAM, 1920x1080 at 1x.
Base: fork of github.com/Gathin23/cleanyourtv (launcher/ Java app). Keep its plumbing, replace its home layout and skin.
Reference mockup: design/mitv-home.html, tab "Glass (ours)". The "Clean Your TV" tab is the repo's stock layout and is NOT the target.

## 1. Canvas
- Safe area: 96 px left/right, 54 px top/bottom (5%). Nothing interactive outside it.
- Background: one static wallpaper, pre-blurred (no runtime blur on this SoC). Theme file supplies it; default is a soft 4-blob colour field (blue, magenta, teal, violet) on #0a1326. A vertical scrim rgba(7,10,18) 15% -> 55% top to bottom sits over it.
- Colour wash: a radial gradient anchored top-left (1400x760 ellipse at 18%, 0%), colour = focused app's accent, opacity 0.6, animated with a 450 ms colour tween. Inputs row uses #1f6f8f, TV row uses #3b4a66.

## 2. Tokens
- fg #f4f6fb, muted #b5bdcc, bg #070a12, ok #55d29a
- glass: white 8% fill, 1 px edge white 18%, inner top highlight white 22%, shadow 0 12 30 black 28%
- glass focused: white 16% fill, edge white 45%, 4 px white ring, shadow 0 28 56 black 55%
- radius: tiles 22 px, pills 999
- Fonts: Outfit (display: names, clock, hero), Manrope (body/labels). Both bundled as TTF in res/font.
- Type scale (px): 22 label-small, 24 row label, 28 body, 32 greeting, 36 clock, 40 app name, 64 hero title

## 3. Top bar (height 64)
- Left pill: green status dot, "Hi Sura" (Outfit 32/600), divider, time-of-day tag (22, muted):
  <5h "Still up?", <12 "Good morning", <17 "Good afternoon", <21 "Good evening", else "Movie night".
  Name comes from config (`greeting_name`), not hard-coded.
- Right pill: Wi-Fi glyph, short date ("Sat 24 Oct"), 24h clock (Outfit 36/600, tabular). Clock ticks once a minute, only while Home is visible.
- Pills are not focusable.

## 4. Hero band (below top bar, min height 170)
- Eyebrow = row label (24, uppercase, 0.12em). Title = focused item name (64/700). Subtitle (28, muted):
  app: "<sub> · Press OK to open"; shortcut app: "<sub> · Press OK to switch to HDMI 2";
  input live: "<label> · Press OK to switch input"; input dead: "Nothing connected"; tool: "Opens the system <name> screen".

## 5. Rows (gap 34, label above each, horizontal tracks)
Row 1 "Apps", tiles 300x170, gap 28. Order is fixed by config, not alphabetical:
  1 YouTube, 2 Netflix, 3 Prime Video, 4 Hotstar, 5 Zee TV (shortcut), 6 Live TV, 7 All apps.
  Tile = glass panel + brand tint (app accent gradient at 55% opacity under the glass, 80% when focused) + a radial highlight top-left.
  App name bottom-left (Outfit 40/700, soft shadow). Icon slot top-left 56x56 radius 14, filled with PackageManager.getApplicationIcon(), decoded once and LruCached.
  Accent per app: YouTube #e0262c, Netflix #c4162a, Prime #1188c7, Hotstar #1e4fd1, Zee TV #7a2cc9, Live TV #2aa876, All apps #5b6a80. Fallback for unknown apps: dominant colour of the icon via Palette, else #5b6a80.
Row 2 "Inputs", tiles 236x132: HDMI 1, HDMI 2, HDMI 3, AV. Label = name the user set in TV settings if any. Status dot top-right: green glowing when the port reports a signal, white 30% otherwise. Source of truth: TvInputManager input states.
Row 3 "TV", tiles 236x132 with a 40 px line icon top-left: Settings, Network, Picture, Sound, Sleep. Each opens the matching system settings intent.

## 6. Focus and navigation
- D-pad only. Focused tile scales 1.12 with a spring (overshoot ~1.3 easing, 180 ms). Row track slides so the focused tile, at 1.12x, stays fully inside the safe area with 20 px margin.
- Each row remembers its own column (focus memory). Up/Down moves between rows; Left/Right within a row; no wrap.
- Back/Home returns to Apps row, column 0. Focus is never trapped. Mouse/hover is irrelevant.
- Toast (glass pill, bottom centre, 28/600, 1.4 s): "Opening <app>", "Switching to HDMI 2 · Zee Tamil", "Home".

## 7. Zee TV shortcut
- Switch the TV to HDMI 2 (Jio set-top box) through the TV input framework.
- Then, if the HDMI input reports a CEC logical device, send CEC user-control key presses for the configured channel number (`zee_channel`, digits then OK) via HdmiControlManager. If no CEC device or the call fails, do nothing further; switching input is the guaranteed part.
- Whether the Jio box honours CEC is unknown; this must be tested on day one and logged.

## 8. Performance budget (hard requirements)
- Launcher PSS at idle under 60 MB; no work on the main thread at bind time; icons decoded on a background thread at first launch and cached.
- No runtime blur, no video wallpaper, no network calls on Home.
- Clock and stats timers paused when Home is not visible.
- APK under 2 MB.

## 9. Config (no rebuild to change)
JSON in the theme folder: greeting_name, app order (package list), per-app accent overrides, input labels, zee_input (default HDMI 2), zee_channel, wallpaper filename.
