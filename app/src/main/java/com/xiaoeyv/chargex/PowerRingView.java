package com.xiaoeyv.chargex;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 电量环：轨道 + 进度弧 + 中心百分比。
 * 零依赖、无对象分配（RectF/Paint 全复用），进度变化直接重绘。
 */
public class PowerRingView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private float progress = -1f; // 0..1，<0 表示无数据
    private String label = "--";
    private int accentColor = 0xFF0E9F6E;

    public PowerRingView(Context context) {
        super(context);
        init();
    }

    public PowerRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PowerRingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(9f));
        trackPaint.setColor(0xFFE9EDF2);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(dp(9f));
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(accentColor);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFF121820);
        textPaint.setFakeBoldText(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** @param value 0..1，负值表示无数据 */
    public void setProgress(float value) {
        float clamped = value < 0 ? -1f : Math.max(0f, Math.min(1f, value));
        if (Math.abs(clamped - progress) < 0.0005f) return;
        progress = clamped;
        label = progress < 0 ? "--" : Math.round(progress * 100) + "%";
        setContentDescription(progress < 0 ? "电量未知" : "电量 " + Math.round(progress * 100) + "%");
        invalidate();
    }

    /** 环形颜色跟随充电状态语义 */
    public void setAccentColor(int color) {
        if (accentColor == color) return;
        accentColor = color;
        progressPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float stroke = dp(9f);
        float radius = Math.min(cx, cy) - stroke / 2f - dp(1f);
        if (radius <= 0) return;

        c.drawCircle(cx, cy, radius, trackPaint);

        if (progress > 0) {
            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
            c.drawArc(arcRect, -90f, 360f * progress, false, progressPaint);
        }

        textPaint.setTextSize(radius * 0.52f);
        float baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f;
        c.drawText(label, cx, baseline, textPaint);
    }
}
