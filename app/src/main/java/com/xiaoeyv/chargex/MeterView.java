package com.xiaoeyv.chargex;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 一条圆角横条，两种语义复用：
 *
 *   ① 容量条 —— 整条代表"出厂总容量"，左侧实色段是"现在还能用"，右侧浅色段是"老化损失"。
 *      没测出数值时 fraction = 1，条是满的、没有损失段，视觉上等价于"暂时没有衰减"。
 *   ② 进度条 —— 测量进度，充够约 10% 就满。
 *
 * 之所以自绘而不用 ProgressBar：framework 的 ProgressBar 要配 drawable，
 * 想画出"分段 + 损失色"得叠两层，反而更重；这里 3 个 drawRect 就够了。
 */
public class MeterView extends View {

    public static final int MODE_CAPACITY = 0;
    public static final int MODE_PROGRESS = 1;

    private final Paint trackPaint = new Paint();
    private final Paint primaryPaint = new Paint();
    private final Paint lossPaint = new Paint();
    private final Path clipPath = new Path();

    private int mode = MODE_PROGRESS;
    /** 主段占比 0..1 */
    private float fraction = 0f;
    /** 是否显示"损失"段（容量模式且总容量已知） */
    private boolean showLoss;

    public MeterView(Context context) {
        super(context);
        init();
    }

    public MeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MeterView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setColor(0xFFEEF1F5);
        trackPaint.setStyle(Paint.Style.FILL);

        primaryPaint.setColor(0xFF0E9F6E);
        primaryPaint.setStyle(Paint.Style.FILL);

        lossPaint.setColor(0xFFF6D2D2);
        lossPaint.setStyle(Paint.Style.FILL);
    }

    public void setMode(int m) {
        if (mode == m) return;
        mode = m;
        invalidate();
    }

    /**
     * @param fraction  主段占比 0..1
     * @param accent    主段颜色
     * @param lossColor 损失段颜色（仅容量模式用；传 0 不画）
     */
    public void setValues(float fraction, int accent, int lossColor) {
        float f = fraction;
        if (f < 0f) f = 0f;
        if (f > 1f) f = 1f;
        boolean nextShowLoss = mode == MODE_CAPACITY && lossColor != 0;
        // 每 500ms 刷一次界面，值没变就别重绘
        if (Math.abs(f - this.fraction) < 0.0005f
                && accent == primaryPaint.getColor()
                && nextShowLoss == showLoss
                && (!nextShowLoss || lossColor == lossPaint.getColor())) {
            return;
        }
        this.fraction = f;
        this.showLoss = nextShowLoss;
        primaryPaint.setColor(accent);
        if (nextShowLoss) lossPaint.setColor(lossColor);
        invalidate();
    }

    public float fraction() {
        return fraction;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        float r = h / 2f;
        clipPath.reset();
        clipPath.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW);

        c.save();
        c.clipPath(clipPath);
        c.drawRect(0f, 0f, w, h, trackPaint);
        if (showLoss && fraction < 0.999f) {
            c.drawRect(w * fraction, 0f, w, h, lossPaint);
        }
        if (fraction > 0f) {
            c.drawRect(0f, 0f, w * fraction, h, primaryPaint);
        }
        c.restore();
    }
}
