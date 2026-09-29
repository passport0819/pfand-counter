package app.pfandcounter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A plain column chart drawn on the canvas, no library: one column per label, its value written
 * above it, the label below. Used for the amount counted per month.
 */
class StatsChart extends View {
    private final String[] labels;
    private final long[] values;
    private final String[] shown;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    StatsChart(Context ctx, String[] labels, long[] values, String[] shown) {
        super(ctx);
        this.labels = labels;
        this.values = values;
        this.shown = shown;
        density = ctx.getResources().getDisplayMetrics().density;
        bar.setColor(ctx.getColor(R.color.accent));
        track.setColor(ctx.getColor(R.color.surface_high));
        text.setColor(ctx.getColor(R.color.text_dim));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * ctx.getResources().getDisplayMetrics().scaledDensity);
        StringBuilder said = new StringBuilder();
        for (int i = 0; i < labels.length; i++) said.append(labels[i]).append(": ").append(shown[i]).append(". ");
        setContentDescription(said.toString());
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(150 * density + 2 * text.getTextSize()));
    }

    @Override protected void onDraw(Canvas c) {
        int n = labels.length;
        if (n == 0) return;
        long max = 1;
        for (long v : values) max = Math.max(max, v);
        float line = text.getTextSize() * 1.3f;
        float top = line, bottom = getHeight() - line;
        float slot = getWidth() / (float) n;
        float width = Math.min(slot * 0.6f, 36 * density);
        float radius = 4 * density;
        for (int i = 0; i < n; i++) {
            // Left to right in both reading directions: time runs from old to new.
            float mid = slot * i + slot / 2;
            RectF full = new RectF(mid - width / 2, top + line, mid + width / 2, bottom);
            c.drawRoundRect(full, radius, radius, track);
            float h = (full.height()) * values[i] / max;
            if (values[i] > 0) {
                c.drawRoundRect(new RectF(full.left, bottom - Math.max(h, 2 * radius), full.right, bottom),
                        radius, radius, bar);
            }
            c.drawText(shown[i], mid, bottom - h - line * 0.3f, text);
            c.drawText(labels[i], mid, getHeight() - text.descent(), text);
        }
    }
}
