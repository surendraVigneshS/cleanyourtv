package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.palette.graphics.Palette;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The glass Home (design/SPEC.md): greeting and clock pills, a hero band, and three rows - Apps, Inputs, TV -
 * over a pre-blurred wallpaper. It keeps the reference launcher's plumbing: Config and the theme overlay, the
 * leanback launch intents and launch_actions, InputSwitch for HDMI/AV, the App library, LibraryApps and the
 * saved tile Order. Everything that asks PackageManager or TvInputManager runs on one background thread; the main
 * thread only builds views and draws.
 *
 * Sizes are the spec's 1920x1080 design px times {@link #u}, so the layout is the same on a 720p or 1080p panel.
 */
public class GlassHome extends Activity {
    private static final String TAG = "TvHome.Glass";
    private static final int FG = 0xFFF4F6FB, MUTED = 0xFFB5BDCC, BG = 0xFF070A12, OK_GREEN = 0xFF55D29A;
    private static final int INPUTS_WASH = 0xFF1F6F8F, TOOLS_WASH = 0xFF3B4A66;
    private static final int APPS = 0, INPUTS = 1, TOOLS = 2;
    private static final String[] PORTS = {"HDMI 1", "HDMI 2", "HDMI 3", "AV"};
    private static final String[] TOOL_NAMES = {"Settings", "Network", "Picture", "Sound", "Sleep"};
    private static final int[] TOOL_ICONS = {R.drawable.glass_ic_settings, R.drawable.glass_ic_network,
            R.drawable.glass_ic_picture, R.drawable.glass_ic_sound, R.drawable.glass_ic_sleep};
    private static final long MINUTE_MS = 60_000L;
    private static final long LONG_PRESS_MS = 600L;

    /** Icons, rendered once at the tile's slot size and kept for the process: a few KB each. */
    private static final LruCache<String, Bitmap> ICONS = new LruCache<String, Bitmap>(1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    /** Palette accents for apps the config gives no colour, keyed like ICONS. */
    private static final Map<String, Integer> PALETTE = Collections.synchronizedMap(new HashMap<>());
    /** The one background thread: PackageManager, TvInputManager, decoding. Never the main thread. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glass-home");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final Handler main = new Handler(Looper.getMainLooper());
    private float u;
    private Config cfg;
    private GlassConfig gc;
    private Typeface display600, display700, body500, body600, body700;

    private FrameLayout root;
    private WashView wash;
    private TextView hi, tod, date, clock, eyebrow, title, desc, toast;
    private final TileRow[] rows = new TileRow[3];
    private final String[] rowLabels = {"Apps", "Inputs", "TV"};
    private int row = APPS;

    /** Apps row data, by tile key. */
    private final Map<String, App> apps = new HashMap<>();
    private Map<String, InputSwitch.Port> ports = Collections.emptyMap();
    private final Map<String, Integer> inputStates = new HashMap<>();
    private boolean started, resumed, stale;
    private int okDown = -1;
    private boolean longFired;
    private boolean moving;
    private PopupWindow menu;
    private AppLibraryDialog library;
    private Bitmap wallpaper;

    /** One Apps row entry: an app, or the Zee TV / All apps tile. */
    private static final class App {
        String key, pkg, name, sub;
        Intent launch;
        int accent;
        boolean configuredAccent;
        boolean fromLibrary;
    }

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            main.postDelayed(this, MINUTE_MS - now % MINUTE_MS + 50);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            if (started) loadApps();
            else stale = true;
        }
    };

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            inputStates.put(inputId, state);
            updateInputTiles();
        }

        @Override
        public void onInputAdded(String inputId) {
            loadPorts();
        }

        @Override
        public void onInputRemoved(String inputId) {
            loadPorts();
        }
    };

    // ---- lifecycle --------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        u = Math.min(Math.max(w, h) / 1920f, Math.min(w, h) / 1080f);
        cfg = Config.load(this);
        gc = GlassConfig.load(this, cfg);
        display600 = font(R.font.outfit_semibold, Typeface.DEFAULT_BOLD);
        display700 = font(R.font.outfit_bold, Typeface.DEFAULT_BOLD);
        body500 = font(R.font.manrope_medium, Typeface.SANS_SERIF);
        body600 = font(R.font.manrope_semibold, Typeface.DEFAULT_BOLD);
        body700 = font(R.font.manrope_bold, Typeface.DEFAULT_BOLD);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setClipChildren(false);
        // The root is opaque: the window's own background under it would be a second full-screen fill per frame.
        getWindow().setBackgroundDrawable(null);
        wash = new WashView(this, u, gc.accent(firstKey()));
        root.addView(wash, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildSafeArea(), safeParams());
        toast = buildToast();
        root.addView(toast, toastParams());
        setContentView(root);

        buildInputsRow();
        buildToolsRow();
        loadWallpaper();
        loadApps();
        loadPorts();

        IntentFilter pkgs = new IntentFilter();
        pkgs.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgs.addDataScheme("package");
        registerReceiver(packageReceiver, pkgs);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        updateClock();
        main.removeCallbacks(minuteTick);
        main.postDelayed(minuteTick, MINUTE_MS - System.currentTimeMillis() % MINUTE_MS + 50);
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, time);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, main);
        // States may have moved while another app was in front, and no callback reached us then.
        refreshInputStates();
        if (stale) {
            stale = false;
            loadApps();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (moving) dropMoving();
        if (menu != null) menu.dismiss();
        super.onPause();
    }

    @Override
    protected void onStop() {
        started = false;
        // Clock and input listeners run only while Home can be seen (SPEC section 8).
        main.removeCallbacks(minuteTick);
        try {
            unregisterReceiver(timeReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (library != null) library.popup.dismiss();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(packageReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // The Home key, whether pressed on Home or in an app: back to the Apps row, first tile.
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) goHome(resumed);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back returns focus to the start instead.
        if (moving) dropMoving();
        else goHome(true);
    }

    // ---- layout -----------------------------------------------------------------------------------------------

    private int px(float design) {
        return Math.round(design * u);
    }

    private Typeface font(int res, Typeface fallback) {
        try {
            Typeface t = getResources().getFont(res);
            return t != null ? t : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private FrameLayout.LayoutParams safeParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.setMargins(px(96), px(54), px(96), px(54));
        return lp;
    }

    private TextView text(Typeface face, float size, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private GradientDrawable pill() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x14FFFFFF);
        d.setStroke(Math.max(1, px(1)), 0x2EFFFFFF);
        d.setCornerRadius(px(999));
        return d;
    }

    private View buildSafeArea() {
        LinearLayout safe = new LinearLayout(this);
        safe.setOrientation(LinearLayout.VERTICAL);
        safe.setClipChildren(false);
        safe.setClipToPadding(false);

        // Top bar: greeting pill left, status pill right. Not focusable.
        FrameLayout top = new FrameLayout(this);
        top.setClipChildren(false);
        LinearLayout greet = new LinearLayout(this);
        greet.setOrientation(LinearLayout.HORIZONTAL);
        greet.setGravity(Gravity.CENTER_VERTICAL);
        greet.setPadding(px(28), 0, px(28), 0);
        greet.setBackground(pill());
        View dot = new View(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.OVAL);
        glow.setColor(0x4D55D29A);
        GradientDrawable core = new GradientDrawable();
        core.setShape(GradientDrawable.OVAL);
        core.setColor(OK_GREEN);
        LayerDrawable dotBg = new LayerDrawable(new Drawable[]{glow, core});
        dotBg.setLayerInset(1, px(5), px(5), px(5), px(5));
        dot.setBackground(dotBg);
        greet.addView(dot, new LinearLayout.LayoutParams(px(24), px(24)));
        hi = text(display600, 32, FG);
        hi.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams hiLp = new LinearLayout.LayoutParams(-2, -2);
        hiLp.setMarginStart(px(16) - px(5));
        greet.addView(hi, hiLp);
        View divider = new View(this);
        divider.setBackgroundColor(0x2EFFFFFF);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(Math.max(1, px(1)), px(30));
        dLp.setMarginStart(px(16));
        greet.addView(divider, dLp);
        tod = text(body600, 22, MUTED);
        LinearLayout.LayoutParams todLp = new LinearLayout.LayoutParams(-2, -2);
        todLp.setMarginStart(px(16));
        greet.addView(tod, todLp);
        top.addView(greet, new FrameLayout.LayoutParams(-2, px(64), Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(px(28), 0, px(28), 0);
        status.setBackground(pill());
        ImageView wifi = new ImageView(this);
        wifi.setImageResource(R.drawable.glass_ic_wifi);
        wifi.setColorFilter(MUTED);
        status.addView(wifi, new LinearLayout.LayoutParams(px(32), px(32)));
        date = text(body600, 28, MUTED);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(-2, -2);
        dateLp.setMarginStart(px(22));
        status.addView(date, dateLp);
        clock = text(display600, 36, FG);
        clock.setLetterSpacing(-0.01f);
        clock.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(-2, -2);
        clockLp.setMarginStart(px(22));
        status.addView(clock, clockLp);
        top.addView(status, new FrameLayout.LayoutParams(-2, px(64), Gravity.END | Gravity.CENTER_VERTICAL));
        safe.addView(top, new LinearLayout.LayoutParams(-1, px(64)));

        // Hero: row name, focused item, a line about what OK does. Bottom-aligned in at least 170 px.
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.BOTTOM);
        hero.setMinimumHeight(px(170));
        eyebrow = text(body700, 24, MUTED);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setAllCaps(true);
        hero.addView(eyebrow);
        title = text(display700, 64, FG);
        title.setLetterSpacing(-0.02f);
        title.setShadowLayer(px(12), 0, px(4), 0x73000000);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-2, -2);
        tLp.topMargin = px(8);
        hero.addView(title, tLp);
        desc = text(body500, 28, MUTED);
        LinearLayout.LayoutParams dLp2 = new LinearLayout.LayoutParams(-2, -2);
        dLp2.topMargin = px(8);
        hero.addView(desc, dLp2);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.topMargin = px(34);
        safe.addView(hero, heroLp);

        // Rows: label above each, tracks with focus memory. Every row's first tile lines up on the same x.
        int start = TileRow.insetFor(px(300), u);   // the widest tile needs the most room
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        int[][] sizes = {{300, 170}, {236, 132}, {236, 132}};
        for (int i = 0; i < 3; i++) {
            TextView label = text(body700, 24, MUTED);
            label.setLetterSpacing(0.14f);
            label.setAllCaps(true);
            label.setText(rowLabels[i]);
            label.setPadding(start, 0, 0, 0);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) lLp.topMargin = px(34);
            list.addView(label, lLp);
            rows[i] = new TileRow(this, px(sizes[i][0]), px(sizes[i][1]), px(28), u);
            rows[i].setStart(start);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.topMargin = px(14);
            list.addView(rows[i], rLp);
        }
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
        listLp.topMargin = px(36);
        safe.addView(list, listLp);
        return safe;
    }

    private TextView buildToast() {
        TextView t = text(body600, 28, FG);
        t.setBackground(pill());
        t.setPadding(px(32), px(18), px(32), px(18));
        t.setAlpha(0f);
        return t;
    }

    private FrameLayout.LayoutParams toastParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = px(48);
        return lp;
    }

    private final Runnable hideToast = () -> toast.animate().alpha(0f).translationY(px(20)).setDuration(250).start();

    /** Glass pill, bottom centre, 1.4 s (SPEC section 6). */
    private void toast(String message) {
        main.removeCallbacks(hideToast);
        toast.setText(message);
        toast.animate().cancel();
        if (toast.getAlpha() == 0f) toast.setTranslationY(px(20));
        toast.animate().alpha(1f).translationY(0f).setDuration(250).start();
        main.postDelayed(hideToast, 1400);
    }

    // ---- clock and greeting -------------------------------------------------------------------------------------

    private void updateClock() {
        Date now = new Date();
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        String name = cfg.greetingName.isEmpty() ? "" : " " + cfg.greetingName;
        hi.setText("Hi" + name);
        tod.setText(hour < 5 ? "Still up?" : hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon"
                : hour < 21 ? "Good evening" : "Movie night");
        date.setText(new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now));
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
    }

    // ---- wallpaper ----------------------------------------------------------------------------------------------

    /** Decoded off the main thread at screen size, with the scrim painted into it once (one layer, not two). */
    private void loadWallpaper() {
        final File file = gc.wallpaper;
        final int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        WORKER.execute(() -> {
            Bitmap b = null;
            try {
                b = decode(file, w, h);
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "wallpaper: " + e);
            }
            if (b == null) {
                try {
                    b = decode(null, w, h);
                } catch (Exception | OutOfMemoryError e) {
                    Log.w(TAG, "bundled wallpaper: " + e);
                }
            }
            if (b == null) return;
            Canvas c = new Canvas(b);
            Paint scrim = new Paint();
            scrim.setShader(new LinearGradient(0, 0, 0, b.getHeight(), 0x26070A12, 0x8C070A12, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, b.getWidth(), b.getHeight(), scrim);
            final Bitmap ready = b;
            main.post(() -> {
                if (isDestroyed()) return;
                wallpaper = ready;
                root.setBackground(new BitmapDrawable(getResources(), ready));
            });
        });
    }

    private Bitmap decode(File file, int w, int h) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(file)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;   // 565 bands on soft gradients
        o.inMutable = true;
        // Scale while decoding to cover the screen: a 1080p image on a 720p panel costs 3.7 MB, not 8.3.
        float scale = Math.max(w / (float) bounds.outWidth, h / (float) bounds.outHeight);
        if (scale < 1f) {
            o.inScaled = true;
            o.inDensity = 10000;
            o.inTargetDensity = Math.max(1, Math.round(10000 * scale));
        }
        try (InputStream in = open(file)) {
            return BitmapFactory.decodeStream(in, null, o);
        }
    }

    private InputStream open(File file) throws Exception {
        return file != null ? new FileInputStream(file) : getResources().openRawResource(R.raw.glass_wallpaper);
    }

    // ---- Apps row -----------------------------------------------------------------------------------------------

    private String firstKey() {
        return gc.homeApps.isEmpty() ? GlassConfig.ALL_APPS : gc.homeApps.get(0);
    }

    /** Which apps exist and how to open them, off the main thread; the row is rebuilt on the main thread after. */
    private void loadApps() {
        final Context app = getApplicationContext();
        final int slot = px(56);
        WORKER.execute(() -> {
            PackageManager pm = app.getPackageManager();
            Map<String, ResolveInfo> leanback = new HashMap<>();
            for (ResolveInfo ri : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                if (ri.activityInfo != null && !leanback.containsKey(ri.activityInfo.packageName)) {
                    leanback.put(ri.activityInfo.packageName, ri);
                }
            }
            List<String> keys = new ArrayList<>(gc.homeApps);
            // Apps the owner added from the App library join the row before All apps.
            Set<String> added = LibraryApps.load(app);
            Set<String> fromLibrary = new HashSet<>();
            for (String pkg : added) {
                if (keys.contains(pkg)) continue;
                int at = keys.indexOf(GlassConfig.ALL_APPS);
                keys.add(at < 0 ? keys.size() : at, pkg);
                fromLibrary.add(pkg);
            }
            List<App> list = new ArrayList<>();
            for (String key : keys) {
                App a = new App();
                a.key = key;
                a.sub = gc.subs.get(key);
                a.fromLibrary = fromLibrary.contains(key);
                if (GlassConfig.ZEE.equals(key)) {
                    a.name = gc.zeeName;
                } else if (GlassConfig.ALL_APPS.equals(key)) {
                    if (!cfg.appLibrary) continue;
                    a.name = getString(R.string.glass_all_apps);
                } else {
                    a.pkg = key;
                    ResolveInfo ri = leanback.get(key);
                    a.launch = launchIntent(pm, key, ri);
                    if (a.launch == null) continue;   // not installed, or disabled
                    String label = cfg.appLabels.get(key);
                    if (label == null) label = ri != null ? String.valueOf(ri.loadLabel(pm)) : appLabel(pm, key);
                    a.name = Cards.cleanLabel(label, key);
                    iconFor(pm, key, slot);
                }
                a.configuredAccent = gc.hasAccent(key);
                Integer sampled = PALETTE.get(key);
                a.accent = a.configuredAccent || sampled == null ? gc.accent(key) : sampled;
                list.add(a);
            }
            applySavedOrder(list);
            main.post(() -> {
                if (!isDestroyed()) bindApps(list);
            });
        });
    }

    /** The leanback launch, as the stock launcher and AppAdapter send it, or launch_actions' screen; else LAUNCHER. */
    private Intent launchIntent(PackageManager pm, String pkg, ResolveInfo leanback) {
        Intent i = null;
        if (leanback != null) {
            i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(pkg, leanback.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        } else {
            Intent plain = pm.getLaunchIntentForPackage(pkg);
            if (plain != null) i = plain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        String action = cfg.launchActions.get(pkg);
        if (i != null && action != null) {
            Intent byAction = new Intent(action).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pm.resolveActivity(byAction, 0) != null) i = byAction;
        }
        return i;
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** getApplicationIcon at the slot's size with its 14 px corners, decoded once per process (background thread). */
    private void iconFor(PackageManager pm, String pkg, int slot) {
        if (ICONS.get(pkg) != null) return;
        try {
            Drawable d = pm.getApplicationIcon(pkg);
            Bitmap raw = Bitmap.createBitmap(slot, slot, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(raw);
            d.setBounds(0, 0, slot, slot);
            d.draw(c);
            if (!gc.hasAccent(pkg) && !PALETTE.containsKey(pkg)) {
                Palette p = Palette.from(raw).generate();
                PALETTE.put(pkg, p.getDominantColor(GlassConfig.DEFAULT_ACCENT) | 0xFF000000);
            }
            Bitmap round = Bitmap.createBitmap(slot, slot, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            float r = 14 * u;
            new Canvas(round).drawRoundRect(new RectF(0, 0, slot, slot), r, r, p);
            raw.recycle();
            ICONS.put(pkg, round);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "icon " + pkg + ": " + e);
        }
    }

    private void applySavedOrder(List<App> list) {
        Order saved = Order.load(Order.file(this));
        if (saved == null || !orderKey().equals(saved.dock)) return;
        final Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < saved.keys.size(); i++) rank.put(saved.keys.get(i), i);
        // Stable: entries the saved order doesn't know (a new library app) keep their place relative to each other.
        List<App> ranked = new ArrayList<>(), rest = new ArrayList<>();
        for (App a : list) (rank.containsKey(a.key) ? ranked : rest).add(a);
        Collections.sort(ranked, (a, b) -> rank.get(a.key) - rank.get(b.key));
        list.clear();
        list.addAll(ranked);
        list.addAll(rest);
    }

    /** The saved order applies only while home_apps is what it was saved under (as Order does for dock_apps). */
    private String orderKey() {
        return "glass:" + Order.dockKey(gc.homeApps);
    }

    private void bindApps(List<App> list) {
        TileRow r = rows[APPS];
        String focusedKey = r.focusedTile() != null ? r.focusedTile().key : null;
        r.removeAllViews();
        apps.clear();
        for (App a : list) {
            apps.put(a.key, a);
            GlassTile t = new GlassTile(this, GlassTile.APP, a.key, u, display700, body600);
            t.name = a.name;
            t.setAccent(a.accent);
            if (a.pkg != null) {
                Bitmap icon = ICONS.get(a.pkg);
                if (icon != null) t.setIcon(icon);
            } else {
                t.setGlyph(getDrawable(GlassConfig.ZEE.equals(a.key) ? R.drawable.glass_ic_input : R.drawable.glass_ic_apps));
            }
            r.addView(t);
        }
        int col = 0;
        for (int i = 0; focusedKey != null && i < r.count(); i++) if (focusedKey.equals(r.tile(i).key)) col = i;
        r.col = Math.min(col, Math.max(0, r.count() - 1));
        refocus(false);
    }

    // ---- Inputs row ---------------------------------------------------------------------------------------------

    private void buildInputsRow() {
        for (String name : PORTS) {
            GlassTile t = new GlassTile(this, GlassTile.INPUT, name, u, display700, body600);
            t.name = name;
            t.sub = gc.inputLabels.get(name);
            rows[INPUTS].addView(t);
        }
    }

    private void loadPorts() {
        final Context app = getApplicationContext();
        WORKER.execute(() -> {
            Map<String, InputSwitch.Port> found = InputSwitch.ports(app);
            Map<String, Integer> states = readStates(app, found);
            main.post(() -> {
                ports = found;
                inputStates.clear();
                inputStates.putAll(states);
                updateInputTiles();
            });
        });
    }

    private void refreshInputStates() {
        final Context app = getApplicationContext();
        final Map<String, InputSwitch.Port> current = ports;
        if (current.isEmpty()) return;
        WORKER.execute(() -> {
            Map<String, Integer> states = readStates(app, current);
            main.post(() -> {
                inputStates.putAll(states);
                updateInputTiles();
            });
        });
    }

    private static Map<String, Integer> readStates(Context ctx, Map<String, InputSwitch.Port> ports) {
        Map<String, Integer> out = new HashMap<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (InputSwitch.Port p : ports.values()) {
            for (String id : p.ids) {
                try {
                    out.put(id, tim.getInputState(id));
                } catch (RuntimeException ignored) {
                }
            }
        }
        return out;
    }

    private boolean portLive(InputSwitch.Port p) {
        if (p == null) return false;
        for (String id : p.ids) {
            Integer s = inputStates.get(id);
            if (s != null && s == TvInputManager.INPUT_STATE_CONNECTED) return true;
        }
        return false;
    }

    private void updateInputTiles() {
        // Some TVs (Sony) report every HDMI port connected whatever is plugged in: then no port is shown live.
        int hdmi = 0, hdmiLive = 0;
        for (Map.Entry<String, InputSwitch.Port> e : ports.entrySet()) {
            if (!e.getKey().startsWith("HDMI")) continue;
            hdmi++;
            if (portLive(e.getValue())) hdmiLive++;
        }
        boolean bogus = hdmi > 1 && hdmiLive == hdmi;
        TileRow r = rows[INPUTS];
        for (int i = 0; i < r.count(); i++) {
            GlassTile t = r.tile(i);
            InputSwitch.Port p = ports.get(t.key);
            boolean live = !bogus && portLive(p);
            String label = inputLabel(t.key);
            t.sub = label != null ? label : live ? "Connected" : "No signal";
            t.setLive(live);
            t.invalidate();
        }
        if (row == INPUTS) updateHero();
    }

    /** config input_labels first, then the name given in Settings > Inputs. */
    private String inputLabel(String port) {
        String l = gc.inputLabels.get(port);
        if (l != null && !l.isEmpty()) return l;
        InputSwitch.Port p = ports.get(port);
        return p != null ? p.customLabel : null;
    }

    // ---- TV row -------------------------------------------------------------------------------------------------

    private void buildToolsRow() {
        for (int i = 0; i < TOOL_NAMES.length; i++) {
            GlassTile t = new GlassTile(this, GlassTile.TOOL, TOOL_NAMES[i], u, display700, body600);
            t.name = TOOL_NAMES[i];
            t.setGlyph(getDrawable(TOOL_ICONS[i]));
            rows[TOOLS].addView(t);
        }
    }

    /**
     * The first target in tool_intents that opens one screen: a component that exists, or an action with exactly
     * one handler (two or more would put a chooser on screen). Otherwise the general Settings screen.
     */
    private Intent toolIntent(String tool) {
        PackageManager pm = getPackageManager();
        String targets = gc.toolIntents.get(tool);
        if (targets != null) {
            for (String target : targets.split("\\|")) {
                target = target.trim();
                if (target.isEmpty()) continue;
                ComponentName c = target.contains("/") ? ComponentName.unflattenFromString(target) : null;
                if (c != null) {
                    Intent i = new Intent().setComponent(c);
                    try {
                        pm.getActivityInfo(c, 0);
                        return i;
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                    continue;
                }
                Intent i = new Intent(target);
                if (pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).size() == 1) return i;
            }
        }
        return SystemSettings.intent(this, Settings.ACTION_SETTINGS);
    }

    // ---- focus ----------------------------------------------------------------------------------------------------

    private void refocus(boolean animate) {
        for (int i = 0; i < 3; i++) {
            TileRow r = rows[i];
            for (int c = 0; c < r.count(); c++) r.tile(c).setFocusedState(i == row && c == r.col, animate);
            r.slideTo(r.col, animate);
        }
        updateHero();
        GlassTile t = rows[row].focusedTile();
        wash.setColor(row == APPS ? (t != null ? t.accent() : GlassConfig.DEFAULT_ACCENT)
                : row == INPUTS ? INPUTS_WASH : TOOLS_WASH, animate);
    }

    private void move(int dRow, int dCol) {
        if (dRow != 0) {
            int next = row + dRow;
            // No wrap; and skip a row that has nothing in it (an Apps row with every app missing).
            while (next >= 0 && next < 3 && rows[next].count() == 0) next += dRow;
            if (next < 0 || next >= 3 || next == row) return;
            rows[row].focusedTile().setFocusedState(false, true);
            row = next;
        } else {
            TileRow r = rows[row];
            int c = r.col + dCol;
            if (c < 0 || c >= r.count()) return;
            if (moving) {
                carry(c);
                return;
            }
            r.focusedTile().setFocusedState(false, true);
            r.col = c;
        }
        TileRow r = rows[row];
        if (r.focusedTile() == null) return;
        r.focusedTile().setFocusedState(true, true);
        r.slideTo(r.col, true);
        updateHero();
        GlassTile t = r.focusedTile();
        wash.setColor(row == APPS ? t.accent() : row == INPUTS ? INPUTS_WASH : TOOLS_WASH, true);
    }

    private void goHome(boolean showToast) {
        if (moving) dropMoving();
        if (menu != null) menu.dismiss();
        if (rows[APPS].count() > 0) {
            row = APPS;
            rows[APPS].col = 0;
            refocus(resumed);
        }
        if (showToast) toast(getString(R.string.glass_toast_home));
    }

    private void updateHero() {
        GlassTile t = rows[row].focusedTile();
        eyebrow.setText(rowLabels[row]);
        if (t == null) {
            title.setText("");
            desc.setText("");
            return;
        }
        title.setText(t.name);
        if (row == APPS) {
            App a = apps.get(t.key);
            String sub = a != null && a.sub != null ? a.sub : "";
            String prefix = sub.isEmpty() ? "" : sub + " · ";
            desc.setText(GlassConfig.ZEE.equals(t.key)
                    ? prefix + getString(R.string.glass_press_switch, gc.zeeInput)
                    : prefix + getString(R.string.glass_press_open));
        } else if (row == INPUTS) {
            String label = inputLabel(t.key);
            desc.setText(t.live ? (label != null ? label : t.name) + " · " + getString(R.string.glass_press_input)
                    : getString(R.string.glass_nothing_connected));
        } else {
            desc.setText(getString(R.string.glass_opens_system, t.name.toLowerCase(Locale.getDefault())));
        }
    }

    // ---- keys -----------------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) move(0, -1);
                    else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) move(0, 1);
                    else if (!moving) move(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1, 0);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = code;
                        longFired = false;
                    } else if (!longFired && okDown == code
                            && (e.isLongPress() || e.getEventTime() - e.getDownTime() >= LONG_PRESS_MS)) {
                        longFired = true;
                        if (!moving) showTileMenu();
                    }
                } else if (okDown == code) {
                    okDown = -1;
                    if (!longFired) {
                        if (moving) dropMoving();
                        else activate();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (!down && !moving) showTileMenu();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void activate() {
        GlassTile t = rows[row].focusedTile();
        if (t == null) return;
        if (row == APPS) {
            App a = apps.get(t.key);
            if (a == null) return;
            if (GlassConfig.ZEE.equals(a.key)) {
                switchToPort(gc.zeeInput, getString(R.string.glass_toast_zee, gc.zeeInput, gc.zeeLabel));
            } else if (GlassConfig.ALL_APPS.equals(a.key)) {
                openLibrary();
            } else {
                toast(getString(R.string.glass_toast_opening, a.name));
                try {
                    startActivity(a.launch);
                } catch (ActivityNotFoundException | SecurityException ex) {
                    toast(getString(R.string.cant_open, a.name));
                }
            }
        } else if (row == INPUTS) {
            switchToPort(t.key, getString(R.string.glass_toast_switching, t.key));
        } else {
            try {
                startActivity(toolIntent(t.key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException ex) {
                toast(getString(R.string.not_available));
            }
        }
    }

    /**
     * Switches to a port through the TV input framework. The Zee TV tile stops here: CEC keys for the channel
     * would need android.permission.HDMI_CEC (signature|privileged), and the set-top box answered no CEC poll
     * when tested (NOTES.md), so zee_channel is not sent.
     */
    private void switchToPort(String port, String message) {
        InputSwitch.Port p = ports.get(port);
        if (p == null) {
            toast(getString(R.string.not_available));
            return;
        }
        toast(message);
        InputSwitch.open(this, p.openId, cfg.liveTvPackage, cfg.inputAction, cfg.inputUriScheme);
    }

    private void openLibrary() {
        if (library != null && library.popup.isShowing()) return;
        library = new AppLibraryDialog(this, cfg.animations, cfg.reducedMotion, cfg.appLibrarySelection,
                apps::containsKey, this::loadApps, () -> library = null);
        library.show();
    }

    // ---- tile menu and Move ---------------------------------------------------------------------------------------

    /** Hold OK (or Menu) on an app tile: Move, Remove from Home for apps added from the library, App info. */
    private void showTileMenu() {
        if (row != APPS) return;
        GlassTile t = rows[APPS].focusedTile();
        App a = t == null ? null : apps.get(t.key);
        if (a == null) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout list = panel.findViewById(R.id.rows);
        PopupWindow popup = new PopupWindow(panel, (int) (220 * getResources().getDisplayMetrics().density),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> {
            if (menu == popup) menu = null;
        });
        menu = popup;
        if (rows[APPS].count() > 1) addMenuRow(list, popup, R.string.tile_move, this::startMoving);
        if (a.fromLibrary && a.pkg != null) addMenuRow(list, popup, R.string.library_remove_shortcut, () -> {
            if (LibraryApps.save(this, a.pkg, false)) loadApps();
            else toast(getString(R.string.library_save_failed));
        });
        Intent info = a.pkg == null ? null : SystemSettings.appInfo(this, a.pkg);
        if (info != null) addMenuRow(list, popup, R.string.library_app_info, () -> {
            try {
                startActivity(info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException ex) {
                toast(getString(R.string.not_available));
            }
        });
        if (list.getChildCount() == 0) {
            menu = null;
            return;
        }
        popup.showAsDropDown(t, 0, px(16));
        list.getChildAt(0).requestFocus();
    }

    private void addMenuRow(LinearLayout list, PopupWindow popup, int title, Runnable action) {
        View r = getLayoutInflater().inflate(R.layout.item_input, list, false);
        r.findViewById(R.id.icon).setVisibility(View.GONE);
        r.findViewById(R.id.dot).setVisibility(View.GONE);
        r.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) r.findViewById(R.id.title)).setText(title);
        // The OK release that opened the menu must not also pick its first row.
        final boolean[] pressed = {false};
        r.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                if (ev.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            if (pressed[0]) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        list.addView(r);
    }

    private void startMoving() {
        GlassTile t = rows[APPS].focusedTile();
        if (t == null) return;
        moving = true;
        t.setLifted(true);
        toast(getString(R.string.glass_move_hint));
    }

    /** Move mode, Left/Right: the carried tile swaps places with its neighbour. */
    private void carry(int to) {
        TileRow r = rows[APPS];
        GlassTile t = r.focusedTile();
        r.removeViewAt(r.col);
        r.addView(t, to);
        r.col = to;
        r.slideTo(to, true);
    }

    private void dropMoving() {
        moving = false;
        TileRow r = rows[APPS];
        GlassTile t = r.focusedTile();
        if (t != null) t.setLifted(false);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < r.count(); i++) keys.add(r.tile(i).key);
        if (!Order.save(Order.file(this), new Order(orderKey(), keys))) toast(getString(R.string.library_save_failed));
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // In the background on a 1 GB TV: give the icons back; they are rebuilt in a moment on return.
        if (level >= TRIM_MEMORY_MODERATE && !started) ICONS.evictAll();
    }
}
