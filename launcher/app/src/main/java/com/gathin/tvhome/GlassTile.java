package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * One glass Home tile, drawn in a single onDraw: baked shadow, glass fill, brand tint, top-left highlight, edge,
 * focus ring and the content (design/SPEC.md sections 2 and 5). No child views and no clip paths, so a focus
 * change costs one small redraw per frame for 180 ms; the 1.12x zoom itself is a RenderNode property.
 * Everything outside the tile (shadow, ring) draws past its bounds: the row's parent does not clip children.
 */
final class GlassTile extends View {
    static final int APP = 0, INPUT = 1, TOOL = 2;
    static final float FOCUS_SCALE = 1.12f;
    private static final int FG = 0xFFF4F6FB, MUTED = 0xFFB5BDCC, OK = 0xFF55D29A;

    final int kind;
    /** home_apps entry, port name or tool name. */
    final String key;
    String name;
    /** Input tiles: the label line under the name. */
    String sub;
    boolean live;
    private Bitmap icon;
    /** Line icon: a tool's, or an app tile's monogram stand-in (Zee TV, All apps) drawn on the white chip. */
    private Drawable glyph;
    private int c1, c2;

    private final float u;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint subText = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rect = new RectF();
    private final Rect pad = new Rect();
    private final Drawable shadow, shadowFocus;
    private LinearGradient tint;
    private RadialGradient sheen;
    /** 0 at rest, 1 focused: drives fill, edge, ring, tint and shadow together. */
    private float f;
    private ValueAnimator focusAnim;
    private boolean focusedState;
    private boolean lifted;

    GlassTile(Context ctx, int kind, String key, float unit, Typeface display, Typeface body) {
        super(ctx);
        this.kind = kind;
        this.key = key;
        this.u = unit;
        shadow = ctx.getDrawable(R.drawable.tile_shadow);
        shadowFocus = ctx.getDrawable(R.drawable.tile_shadow_focus);
        text.setTypeface(display);
        text.setColor(FG);
        text.setTextSize((kind == APP ? 40 : 34) * u);
        text.setLetterSpacing(-0.01f);
        // Soft text shadow (SPEC: app name), cached with the glyphs by the renderer.
        text.setShadowLayer(6 * u, 0, 2 * u, 0x59000000);
        subText.setTypeface(body);
        subText.setColor(MUTED);
        subText.setTextSize(22 * u);
        setOutlineProvider(null);   // translationZ only reorders drawing; no framework shadow on top of ours
        setWillNotDraw(false);
        setFocusable(false);
    }

    void setAccent(int accent) {
        c1 = accent;
        c2 = Color.rgb((int) (Color.red(accent) * 0.42f), (int) (Color.green(accent) * 0.42f),
                (int) (Color.blue(accent) * 0.42f));
        tint = null;
        invalidate();
    }

    int accent() {
        return c1;
    }

    void setIcon(Bitmap b) {
        icon = b;
        invalidate();
    }

    void setGlyph(Drawable d) {
        glyph = d == null ? null : d.mutate();
        invalidate();
    }

    void setLive(boolean on) {
        if (live == on) return;
        live = on;
        invalidate();
    }

    /** Zoom with a slight overshoot and fade the glass up (SPEC section 6: 1.12x, ~1.3 overshoot, 180 ms). */
    void setFocusedState(boolean focused, boolean animate) {
        if (focusedState == focused) return;
        focusedState = focused;
        float scale = focused ? FOCUS_SCALE : 1f;
        setTranslationZ(focused ? 1f : 0f);
        animate().cancel();
        if (focusAnim != null) focusAnim.cancel();
        if (!animate) {
            setScaleX(scale);
            setScaleY(scale);
            f = focused ? 1f : 0f;
            invalidate();
            return;
        }
        animate().scaleX(scale).scaleY(scale).setDuration(180)
                .setInterpolator(focused ? new OvershootInterpolator(1.3f) : new DecelerateInterpolator()).start();
        focusAnim = ValueAnimator.ofFloat(f, focused ? 1f : 0f).setDuration(180);
        focusAnim.addUpdateListener(a -> {
            f = (float) a.getAnimatedValue();
            invalidate();
        });
        focusAnim.start();
    }

    /** Move mode: the carried tile sits a little higher and larger than a focused one. */
    void setLifted(boolean on) {
        lifted = on;
        float scale = on ? 1.18f : focusedState ? FOCUS_SCALE : 1f;
        animate().scaleX(scale).scaleY(scale).translationY(on ? -8 * u : 0f).setDuration(160).start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        tint = null;
        sheen = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float r = 22 * u;
        // Shadow: two baked nine-patches crossfaded, outset by their own padding, dropped 12 / 28 px.
        drawShadow(canvas, shadow, (int) (255 * (1 - f)), 12 * u, w, h);
        drawShadow(canvas, shadowFocus, (int) (255 * f), 28 * u, w, h);

        rect.set(0, 0, w, h);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        // Glass: white 8% -> 16%.
        paint.setColor(Color.argb((int) (255 * lerp(0.08f, 0.16f, f)), 255, 255, 255));
        canvas.drawRoundRect(rect, r, r, paint);
        if (kind == APP) {
            // Brand tint under the glass: 145deg c1 -> c2 at 55% -> 80%.
            if (tint == null) {
                double a = Math.toRadians(145 - 90);
                float dx = (float) Math.cos(a) * w / 2f, dy = (float) Math.sin(a) * h / 2f;
                tint = new LinearGradient(w / 2f - dx, h / 2f - dy, w / 2f + dx, h / 2f + dy, c1, c2, Shader.TileMode.CLAMP);
            }
            paint.setShader(tint);
            paint.setAlpha((int) (255 * lerp(0.55f, 0.80f, f)));
            canvas.drawRoundRect(rect, r, r, paint);
            // Highlight: a 260x140 ellipse at (20%, 0), white 22% fading out by 65%.
            if (sheen == null) {
                sheen = new RadialGradient(0, 0, 260 * u, new int[]{0x38FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.65f}, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setScale(1f, 140f / 260f);
                m.postTranslate(0.2f * w, 0);
                sheen.setLocalMatrix(m);
            }
            paint.setShader(sheen);
            paint.setAlpha(255);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setShader(null);
        }
        // Inner top highlight (inset 0 1px 0): white 22% -> 40%.
        paint.setColor(Color.argb((int) (255 * lerp(0.22f, 0.40f, f)), 255, 255, 255));
        paint.setStrokeWidth(Math.max(1f, u));
        canvas.drawLine(r, Math.max(1f, u) * 1.5f, w - r, Math.max(1f, u) * 1.5f, paint);
        // Edge: 1 px white 18% -> 45%.
        float edge = Math.max(1f, u);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(edge);
        paint.setColor(Color.argb((int) (255 * lerp(0.18f, 0.45f, f)), 255, 255, 255));
        rect.set(edge / 2, edge / 2, w - edge / 2, h - edge / 2);
        canvas.drawRoundRect(rect, r, r, paint);
        // Focus ring: 4 px white just outside the edge.
        if (f > 0f) {
            float ring = 4 * u;
            paint.setStrokeWidth(ring);
            paint.setColor(Color.argb((int) (255 * f), 255, 255, 255));
            rect.set(-ring / 2, -ring / 2, w + ring / 2, h + ring / 2);
            canvas.drawRoundRect(rect, r + ring / 2, r + ring / 2, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        if (kind == APP) drawApp(canvas, w, h);
        else if (kind == INPUT) drawInput(canvas, w, h);
        else drawTool(canvas, h);
    }

    private void drawApp(Canvas canvas, int w, int h) {
        float x = 20 * u, y = 18 * u, s = 56 * u, cr = 14 * u;
        rect.set(x, y, x + s, y + s);
        if (icon != null) {
            // The icon fills the slot; a white chip under it shows through transparent icons.
            paint.setColor(0xEBFFFFFF);
            canvas.drawRoundRect(rect, cr, cr, paint);
            canvas.drawBitmap(icon, null, rect, paint);
        } else if (glyph != null) {
            paint.setColor(0xEBFFFFFF);
            canvas.drawRoundRect(rect, cr, cr, paint);
            int g = (int) (34 * u);
            int gx = (int) (x + (s - g) / 2), gy = (int) (y + (s - g) / 2);
            glyph.setTint(c2);
            glyph.setBounds(gx, gy, gx + g, gy + g);
            glyph.draw(canvas);
        }
        canvas.drawText(ellipsize(name, text, w - 48 * u), 24 * u, h - 22 * u - text.descent(), text);
    }

    private void drawInput(Canvas canvas, int w, int h) {
        float d = 14 * u, cx = w - 18 * u - d / 2, cy = 18 * u + d / 2;
        if (live) {
            paint.setColor(0x4055D29A);   // ring 6 px at 25%
            canvas.drawCircle(cx, cy, d / 2 + 6 * u, paint);
            paint.setColor(OK);
        } else {
            paint.setColor(0x4DFFFFFF);
        }
        canvas.drawCircle(cx, cy, d / 2, paint);
        float subBase = h - 22 * u - subText.descent();
        boolean hasSub = sub != null && !sub.isEmpty();
        float nameBase = hasSub ? subBase - subText.getTextSize() - 6 * u : subBase;
        canvas.drawText(ellipsize(name, text, w - 48 * u), 24 * u, nameBase, text);
        if (hasSub) canvas.drawText(ellipsize(sub, subText, w - 48 * u), 24 * u, subBase, subText);
    }

    private void drawTool(Canvas canvas, int h) {
        if (glyph != null) {
            int s = (int) (40 * u), x = (int) (24 * u), y = (int) (20 * u);
            glyph.setTint(FG);
            glyph.setBounds(x, y, x + s, y + s);
            glyph.draw(canvas);
        }
        canvas.drawText(ellipsize(name, text, getWidth() - 48 * u), 24 * u, h - 22 * u - text.descent(), text);
    }

    private void drawShadow(Canvas canvas, Drawable d, int alpha, float dy, int w, int h) {
        if (d == null || alpha <= 0) return;
        d.getPadding(pad);
        d.setBounds(-pad.left, (int) dy - pad.top, w + pad.right, h + (int) dy + pad.bottom);
        d.setAlpha(alpha);
        d.draw(canvas);
    }

    private static String ellipsize(String s, Paint p, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        int n = p.breakText(s, true, max - p.measureText("…"), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    boolean isLifted() {
        return lifted;
    }
}
