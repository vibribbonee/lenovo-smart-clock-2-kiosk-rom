package org.kioskclock.nav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Vector "mic off" glyph in the shape of the Material icon: mic capsule, holder arc, stem and a
 * diagonal slash. Drawn on an optional filled circle. Crisp at any size, unlike the emoji.
 */
final class MicOffIcon extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean slash;
    private final int sizePx;

    MicOffIcon(Context c, int sizePx, int background, int glyph, boolean slash) {
        super(c);
        this.sizePx = sizePx;
        this.slash = slash;
        bg.setColor(background);
        fill.setColor(glyph);
        stroke.setColor(glyph);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onMeasure(int w, int h) { setMeasuredDimension(sizePx, sizePx); }

    @Override
    protected void onDraw(Canvas c) {
        float s = sizePx;
        if (bg.getAlpha() > 0) c.drawCircle(s / 2, s / 2, s / 2, bg);
        float u = s / 24f;                       // Material 24-unit grid
        stroke.setStrokeWidth(1.9f * u);
        // capsule (mic head)
        c.drawRoundRect(new RectF(9.2f * u, 4.5f * u, 14.8f * u, 13.2f * u), 2.8f * u, 2.8f * u, fill);
        // holder arc
        c.drawArc(new RectF(6.6f * u, 6.8f * u, 17.4f * u, 16.6f * u), 20, 140, false, stroke);
        // stem + base
        c.drawLine(12 * u, 16.6f * u, 12 * u, 19.2f * u, stroke);
        if (slash) {
            Paint gap = new Paint(stroke);
            gap.setColor(bg.getAlpha() > 0 ? bg.getColor() : 0xFF000000);
            gap.setStrokeWidth(3.6f * u);
            c.drawLine(5.2f * u, 5.2f * u, 18.8f * u, 18.8f * u, gap);   // cut-out behind the slash
            c.drawLine(5.2f * u, 5.2f * u, 18.8f * u, 18.8f * u, stroke);
        }
    }
}
