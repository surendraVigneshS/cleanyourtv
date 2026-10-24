package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * The colour wash top-left (design/SPEC.md section 1): a 1400x760 radial ellipse anchored at (18%, 0), in the
 * focused tile's accent at 60%, tweened over 450 ms. The ellipse is one white alpha mask built once; only the
 * colour changes (a colour filter), and only the ellipse's bounding box is filled, not the whole screen.
 */
final class WashView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float u;
    private int color;
    private ValueAnimator tween;

    WashView(Context ctx, float unit, int initial) {
        super(ctx);
        u = unit;
        color = initial;
        paint.setAlpha((int) (255 * 0.6f));
        paint.setColorFilter(new PorterDuffColorFilter(initial, PorterDuff.Mode.SRC_IN));
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float rx = 1400 * u;
        // Mask stops from the mockup: opaque at the centre, 60% at 35%, gone at 68% of the radius.
        RadialGradient g = new RadialGradient(0, 0, rx, new int[]{0xFFFFFFFF, 0x99FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 0.68f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, 760f / 1400f);
        m.postTranslate(0.18f * w, 0);
        g.setLocalMatrix(m);
        paint.setShader(g);
    }

    void setColor(int target, boolean animate) {
        if (tween != null) tween.cancel();
        if (target == color) return;
        if (!animate) {
            apply(target);
            return;
        }
        tween = ValueAnimator.ofObject(new ArgbEvaluator(), color, target).setDuration(450);
        tween.setInterpolator(new AccelerateDecelerateInterpolator());
        tween.addUpdateListener(a -> apply((int) a.getAnimatedValue()));
        tween.start();
    }

    private void apply(int c) {
        color = c;
        paint.setColorFilter(new PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = 0.18f * getWidth(), rx = 1400 * u * 0.68f, ry = 760 * u * 0.68f;
        canvas.drawRect(Math.max(0, cx - rx), 0, Math.min(getWidth(), cx + rx), Math.min(getHeight(), ry), paint);
    }
}
