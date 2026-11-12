package com.aipc.gamepadbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Live drawing of the PS5 controller state the phone is sending: buttons
 * light up, stick dots move, trigger bars fill. Reads the shared mapper
 * at ~30 fps while attached, so it shows input after button mapping.
 *
 * Drawn in a 100 x 62 unit design space, scaled to fit.
 */
public class PadView extends View {

    private static final float W = 100f;
    private static final float H = 62f;

    private final PadMapper mapper;
    private final PadState s = new PadState();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int colIdle;
    private final int colLine;
    private final int colActive;
    private final int colText;
    private final int colTextOn;
    private float k = 1f; // design unit -> px

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            mapper.getState(s);
            invalidate();
            postDelayed(this, 33);
        }
    };

    public PadView(Context ctx, PadMapper mapper) {
        super(ctx);
        this.mapper = mapper;
        colIdle = ctx.getColor(R.color.pad_idle);
        colLine = ctx.getColor(R.color.pad_line);
        colActive = ctx.getColor(R.color.accent);
        colText = ctx.getColor(R.color.text_secondary);
        colTextOn = ctx.getColor(R.color.bg);
        line.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = Math.round(w * H / W);
        int maxH = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(hSpec) != MeasureSpec.UNSPECIFIED && h > maxH) h = maxH;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        k = Math.min(getWidth() / W, getHeight() / H);
        c.save();
        c.translate((getWidth() - W * k) / 2f, (getHeight() - H * k) / 2f);
        line.setStrokeWidth(0.4f * k);
        text.setTextSize(2.6f * k);

        // Triggers (pressure bars) and shoulders
        bar(c, 6, 2, 30, 6.5f, s.l2, "L2");
        bar(c, 70, 2, 94, 6.5f, s.r2, "R2");
        rbtn(c, 6, 8.5f, 30, 13, PadFrame.BTN_L1, "L1");
        rbtn(c, 70, 8.5f, 94, 13, PadFrame.BTN_R1, "R1");

        // Touchpad, Create, Options, PS
        rbtn(c, 37, 9, 63, 25, PadFrame.BTN_TOUCHPAD, "Touchpad");
        cbtn(c, 31, 18, 2.4f, PadFrame.BTN_SHARE, "");
        label(c, 31, 23.5f, "Create");
        cbtn(c, 69, 18, 2.4f, PadFrame.BTN_OPTIONS, "");
        label(c, 69, 23.5f, "Options");
        cbtn(c, 50, 31, 2.8f, PadFrame.BTN_CREATE, "PS");

        // D-pad
        rbtn(c, 15.5f, 21.5f, 20.5f, 26.5f, PadFrame.BTN_UP, "▲");
        rbtn(c, 15.5f, 33.5f, 20.5f, 38.5f, PadFrame.BTN_DOWN, "▼");
        rbtn(c, 9.5f, 27.5f, 14.5f, 32.5f, PadFrame.BTN_LEFT, "◀");
        rbtn(c, 21.5f, 27.5f, 26.5f, 32.5f, PadFrame.BTN_RIGHT, "▶");

        // Face buttons
        cbtn(c, 82, 24, 3.2f, PadFrame.BTN_TRIANGLE, "△");
        cbtn(c, 88, 30, 3.2f, PadFrame.BTN_CIRCLE, "○");
        cbtn(c, 82, 36, 3.2f, PadFrame.BTN_CROSS, "✕");
        cbtn(c, 76, 30, 3.2f, PadFrame.BTN_SQUARE, "□");

        // Sticks (pressing a stick = L3 / R3)
        stick(c, 36, 47, s.lx, s.ly, PadFrame.BTN_L3, "L3");
        stick(c, 64, 47, s.rx, s.ry, PadFrame.BTN_R3, "R3");

        if (s.connected == 0) {
            text.setColor(ctx().getColor(R.color.warning));
            text.setTextSize(3.2f * k);
            c.drawText("No controller connected", 50 * k, 60 * k, text);
        }
        c.restore();
    }

    private Context ctx() {
        return getContext();
    }

    private boolean on(int bit) {
        return (s.buttons & bit) != 0;
    }

    private void rbtn(Canvas c, float l, float t, float rt, float b, int bit, String lbl) {
        r.set(l * k, t * k, rt * k, b * k);
        boolean a = on(bit);
        fill.setColor(a ? colActive : colIdle);
        c.drawRoundRect(r, 1.2f * k, 1.2f * k, fill);
        line.setColor(a ? colActive : colLine);
        c.drawRoundRect(r, 1.2f * k, 1.2f * k, line);
        centerText(c, r.centerX(), r.centerY(), lbl, a);
    }

    private void cbtn(Canvas c, float cx, float cy, float rad, int bit, String lbl) {
        boolean a = on(bit);
        fill.setColor(a ? colActive : colIdle);
        c.drawCircle(cx * k, cy * k, rad * k, fill);
        line.setColor(a ? colActive : colLine);
        c.drawCircle(cx * k, cy * k, rad * k, line);
        if (!lbl.isEmpty()) centerText(c, cx * k, cy * k, lbl, a);
    }

    private void bar(Canvas c, float l, float t, float rt, float b, int value, String lbl) {
        r.set(l * k, t * k, rt * k, b * k);
        fill.setColor(colIdle);
        c.drawRoundRect(r, 1.2f * k, 1.2f * k, fill);
        if (value > 0) {
            RectF f = new RectF(r.left, r.top, r.left + r.width() * value / 255f, r.bottom);
            fill.setColor(colActive);
            c.drawRoundRect(f, 1.2f * k, 1.2f * k, fill);
        }
        line.setColor(value > 0 ? colActive : colLine);
        c.drawRoundRect(r, 1.2f * k, 1.2f * k, line);
        centerText(c, r.centerX(), r.centerY(), lbl + (value > 0 ? "  " + Math.round(value * 100f / 255f) + "%" : ""), false);
    }

    private void stick(Canvas c, float cx, float cy, int x, int y, int bit, String lbl) {
        boolean a = on(bit);
        fill.setColor(colIdle);
        c.drawCircle(cx * k, cy * k, 9 * k, fill);
        line.setColor(a ? colActive : colLine);
        c.drawCircle(cx * k, cy * k, 9 * k, line);
        float dx = (x - 128) / 127f * 6f;
        float dy = (y - 128) / 127f * 6f;
        boolean moved = Math.abs(x - 128) > 4 || Math.abs(y - 128) > 4;
        fill.setColor(a || moved ? colActive : colLine);
        c.drawCircle((cx + dx) * k, (cy + dy) * k, 3.2f * k, fill);
        centerText(c, (cx + dx) * k, (cy + dy) * k, lbl, a || moved);
    }

    private void label(Canvas c, float cx, float cy, String lbl) {
        text.setColor(colText);
        c.drawText(lbl, cx * k, cy * k, text);
    }

    private void centerText(Canvas c, float x, float y, String lbl, boolean active) {
        text.setColor(active ? colTextOn : colText);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(lbl, x, y - (fm.ascent + fm.descent) / 2f, text);
    }
}
