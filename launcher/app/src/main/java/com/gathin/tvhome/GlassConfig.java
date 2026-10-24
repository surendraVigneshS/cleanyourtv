package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The glass Home's own settings (design/SPEC.md section 9): the defaults in res/values/config.xml, overlaid by
 * the same theme config.json Config reads, so a theme push changes them without a rebuild. The keys Home shares
 * with the rest of the launcher (greeting_name, app_labels, live_tv_package, launch_actions) stay in Config.
 *
 * Map-like keys use the launcher's "key=value" list form, as app_labels does:
 * {@code "app_accents": ["com.netflix.ninja=#c4162a"]}, {@code "input_labels": ["HDMI 2=Jio set-top box"]}.
 */
final class GlassConfig {
    /** The Zee TV shortcut tile in home_apps: switches to zee_input instead of opening an app. */
    static final String ZEE = "@zee";
    /** The All apps tile in home_apps: opens the App library. */
    static final String ALL_APPS = "@all_apps";
    static final int DEFAULT_ACCENT = 0xFF5B6A80;

    /** The Apps row, in order: package names and the two special tiles above. */
    final List<String> homeApps;
    /** Accent per home_apps entry; the second, darker gradient stop is derived from it. */
    final Map<String, Integer> accents;
    /** The short line under a tile's name in the hero ("Subscriptions"), per home_apps entry. */
    final Map<String, String> subs;
    /** Names for the input ports ("HDMI 2" -> "Jio set-top box"); win over the name set in TV settings. */
    final Map<String, String> inputLabels;
    final String zeeName;
    /** The port the Zee TV tile switches to, as named on its tile: "HDMI 1" to "HDMI 3", "AV". */
    final String zeeInput;
    /** Channel number for the set-top box. Kept for the record: CEC keys need a privileged permission (NOTES.md). */
    final String zeeChannel;
    /** What the toast calls the channel: "Switching to HDMI 2 · Zee Tamil". */
    final String zeeLabel;
    /** The wallpaper, a file in the theme's wallpapers/ folder; the bundled one when it is missing. */
    final File wallpaper;

    private GlassConfig(List<String> homeApps, Map<String, Integer> accents, Map<String, String> subs,
                        Map<String, String> inputLabels, String zeeName, String zeeInput, String zeeChannel,
                        String zeeLabel, File wallpaper) {
        this.homeApps = Collections.unmodifiableList(homeApps);
        this.accents = Collections.unmodifiableMap(accents);
        this.subs = Collections.unmodifiableMap(subs);
        this.inputLabels = Collections.unmodifiableMap(inputLabels);
        this.zeeName = zeeName;
        this.zeeInput = zeeInput;
        this.zeeChannel = zeeChannel;
        this.zeeLabel = zeeLabel;
        this.wallpaper = wallpaper;
    }

    static GlassConfig load(Context ctx, Config base) {
        Resources res = ctx.getResources();
        List<String> homeApps = list(res, R.array.home_apps);
        List<String> accents = list(res, R.array.app_accents);
        List<String> subs = list(res, R.array.app_subs);
        List<String> inputLabels = list(res, R.array.input_labels);
        String zeeName = res.getString(R.string.zee_name).trim();
        String zeeInput = res.getString(R.string.zee_input).trim();
        String zeeChannel = res.getString(R.string.zee_channel).trim();
        String zeeLabel = res.getString(R.string.zee_label).trim();
        String wallpaperName = res.getString(R.string.glass_wallpaper).trim();
        JSONObject json = base.overlay;
        if (json != null) {
            homeApps = Config.stringList(json, "home_apps", homeApps);
            // Per-key overrides: a theme names only the accents it changes.
            accents = merged(accents, Config.stringList(json, "app_accents", Collections.emptyList()));
            subs = merged(subs, Config.stringList(json, "app_subs", Collections.emptyList()));
            inputLabels = merged(inputLabels, Config.stringList(json, "input_labels", Collections.emptyList()));
            zeeName = Config.string(json, "zee_name", zeeName);
            zeeInput = Config.string(json, "zee_input", zeeInput);
            zeeChannel = Config.string(json, "zee_channel", zeeChannel);
            zeeLabel = Config.string(json, "zee_label", zeeLabel);
            wallpaperName = Config.string(json, "glass_wallpaper", wallpaperName);
        }
        // Only a plain file name inside wallpapers/: the theme folder is all ThemeProvider may write.
        File dir = Config.dir(ctx);
        File wallpaper = null;
        if (dir != null && ThemeFiles.collectionImage(wallpaperName)) {
            File f = new File(new File(dir, ThemeFiles.WALLPAPERS), wallpaperName);
            if (f.isFile() && f.length() > 0) wallpaper = f;
        }
        return new GlassConfig(homeApps, colors(Config.packageMap(accents)), Config.packageMap(subs),
                Config.packageMap(inputLabels), zeeName, zeeInput.isEmpty() ? "HDMI 2" : zeeInput, zeeChannel,
                zeeLabel, wallpaper);
    }

    int accent(String key) {
        Integer c = accents.get(key);
        return c != null ? c : DEFAULT_ACCENT;
    }

    boolean hasAccent(String key) {
        return accents.containsKey(key);
    }

    private static List<String> list(Resources res, int id) {
        return new ArrayList<>(Arrays.asList(res.getStringArray(id)));
    }

    /** base, then extra: packageMap keeps the later entry for a key, so the theme's wins. */
    private static List<String> merged(List<String> base, List<String> extra) {
        List<String> out = new ArrayList<>(base);
        out.addAll(extra);
        return out;
    }

    private static Map<String, Integer> colors(Map<String, String> raw) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            try {
                out.put(e.getKey(), Color.parseColor(e.getValue()) | 0xFF000000);
            } catch (IllegalArgumentException ignored) {
                // A bad colour leaves that tile on its default.
            }
        }
        return out;
    }
}
