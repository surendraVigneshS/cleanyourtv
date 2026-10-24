package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * One horizontal row of GlassTiles (design/SPEC.md sections 5 and 6). It remembers its own focused column, and
 * slides (scrollX, 280 ms) only as far as needed to keep the focused tile, at 1.12x with its 4 px ring, fully
 * inside the row with a 20 px margin. The row spans the safe area and clips sideways only: the zoom, ring and
 * shadow spill into the gaps above and below, which is what lets three rows fit inside the safe area.
 */
final class TileRow extends ViewGroup {
    final int tileW, tileH;
    private final int gap;
    /** Left inset of the first tile: room for its zoom, ring and margin when it has focus. */
    final int inset;
    private final int margin;
    private final float overhang;
    int col;
    private ValueAnimator slide;

    TileRow(Context ctx, int tileW, int tileH, int gap, float unit) {
        super(ctx);
        this.tileW = tileW;
        this.tileH = tileH;
        this.gap = gap;
        this.margin = Math.round(20 * unit);
        this.overhang = tileW * (GlassTile.FOCUS_SCALE - 1f) / 2f + 4 * unit;
        this.inset = Math.round(overhang) + margin;
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(true);
    }

    /** The widest first-tile inset among rows, so every row (and its label) can line up on it. */
    static int insetFor(int tileW, float unit) {
        return Math.round(tileW * (GlassTile.FOCUS_SCALE - 1f) / 2f + 4 * unit) + Math.round(20 * unit);
    }

    private int start = -1;

    /** Lines every row's first tile up on the same x. */
    void setStart(int x) {
        start = x;
        requestLayout();
    }

    private int startX() {
        return start >= 0 ? start : inset;
    }

    int count() {
        return getChildCount();
    }

    GlassTile tile(int i) {
        return i >= 0 && i < getChildCount() ? (GlassTile) getChildAt(i) : null;
    }

    GlassTile focusedTile() {
        return tile(col);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int exactW = MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY);
        int exactH = MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(exactW, exactH);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), tileH);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x = startX();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.layout(x, 0, x + tileW, tileH);
            x += tileW + gap;
        }
        // A rebuilt row keeps its slide valid for the column it remembers.
        scrollTo(target(col), 0);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        int sx = getScrollX();
        canvas.clipRect(sx, -getHeight() * 2, sx + getWidth(), getHeight() * 3);
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    /** The scrollX that shows column c whole, moving as little as possible from where the row is now. */
    private int target(int c) {
        if (getChildCount() == 0 || getWidth() == 0) return 0;
        int left = startX() + c * (tileW + gap);
        float lo = left - overhang - margin, hi = left + tileW + overhang + margin;
        int s = getScrollX();
        if (hi - s > getWidth()) s = (int) Math.ceil(hi - getWidth());
        if (lo - s < 0) s = (int) Math.floor(lo);
        int max = Math.max(0, startX() + getChildCount() * (tileW + gap) - gap + inset - getWidth());
        return Math.max(0, Math.min(s, max));
    }

    void slideTo(int c, boolean animate) {
        int to = target(c);
        if (slide != null) slide.cancel();
        if (to == getScrollX()) return;
        if (!animate) {
            scrollTo(to, 0);
            return;
        }
        slide = ValueAnimator.ofInt(getScrollX(), to).setDuration(280);
        slide.setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1f));
        slide.addUpdateListener(a -> scrollTo((int) a.getAnimatedValue(), 0));
        slide.start();
    }
}
