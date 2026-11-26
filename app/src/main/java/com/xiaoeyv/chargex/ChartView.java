package com.xiaoeyv.chargex;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 单指标面积图：一次只画一条曲线，用该指标的绝对量程
 * （多线各自归一化会让"线在动但看不出量级"，这是原方案的误读根源）。
 *
 * 纵轴刻度走 {@link AxisScale} 的吸附算法，读数永远是 4.0 / 4.2 / 4.4 这种，
 * 不会出现 4.352 这种只有仪器才需要的小数位。
 * 零以下有值时（放电）额外画一条零基线，正负一眼分得开。
 *
 * 交互按股票行情图的做法：手指按下即出现垂直游标，横向滑动逐点跟随，
 * 顶部浮出该点的时刻与数值；松手立刻消失，不留残影。
 * 纵向滑动则判定为"用户想滚页面"，主动让行，不抢 ScrollView 的手势。
 *
 * 零依赖、Path 复用、每帧无对象分配。
 */
public class ChartView extends View {

    public static final int METRIC_POWER = 0;
    public static final int METRIC_VOLT = 1;
    public static final int METRIC_AMP = 2;
    public static final int METRIC_TEMP = 3;

    private static final int MAX_POINTS = 300;
    private static final int MAX_Y_LABELS = 8;

    private final long[] ts = new long[MAX_POINTS];
    private final float[] vBuf = new float[MAX_POINTS];
    private final float[] aBuf = new float[MAX_POINTS];
    private final float[] tBuf = new float[MAX_POINTS];
    private final float[] work = new float[MAX_POINTS];
    private final byte[] chBuf = new byte[MAX_POINTS];

    private int metric = METRIC_POWER;
    private int accent = 0xFF0E9F6E;

    private final Paint gridPaint = new Paint();
    private final Paint zeroPaint = new Paint();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path fillPath = new Path();
    private final Path linePath = new Path();
    private final RectF tipRect = new RectF();

    private final String[] xLabels = {"", "", "", ""};
    private long xStamp;
    private final SimpleDateFormat xFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat cursorFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // 纵轴刻度缓存：只在量程变化时重新生成字符串
    private final String[] yLabels = new String[MAX_Y_LABELS];
    private int yCount;
    private float cachedLow = Float.NaN;
    private float cachedHigh = Float.NaN;
    private float cachedStep = Float.NaN;
    private float gutterPx;

    // 几何缓存：触摸事件要用，避免重复计算
    private int lastN;
    private float plotLeft, plotRight, plotTop, plotBottom;
    private float lowBound, boundSpan;

    // 游标状态
    private int cursor = -1;
    private float downX, downY;
    private int touchSlop;

    public ChartView(Context context) {
        super(context);
        init();
    }

    public ChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void init() {
        gridPaint.setColor(0xFFEDF0F4);
        gridPaint.setStrokeWidth(dp(1f));

        zeroPaint.setColor(0xFFD5DCE5);
        zeroPaint.setStrokeWidth(dp(1f));

        fillPaint.setStyle(Paint.Style.FILL);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        dotPaint.setStyle(Paint.Style.FILL);

        hintPaint.setColor(0xFF8B95A1);
        hintPaint.setTextSize(dp(12f));
        hintPaint.setTextAlign(Paint.Align.CENTER);

        axisPaint.setColor(0xFF8B95A1);
        axisPaint.setTextSize(dp(9f));

        yLabelPaint.setColor(0xFF8B95A1);
        yLabelPaint.setTextSize(dp(9f));
        yLabelPaint.setTextAlign(Paint.Align.RIGHT);

        cursorPaint.setColor(0xFF9AA6B4);
        cursorPaint.setStrokeWidth(dp(1f));
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setPathEffect(new DashPathEffect(new float[]{dp(3f), dp(3f)}, 0f));

        tipBgPaint.setColor(0xF2121820); // 深色浮层，白字对比度足够
        tipBgPaint.setStyle(Paint.Style.FILL);

        tipValuePaint.setColor(0xFFFFFFFF);
        tipValuePaint.setTextSize(dp(13f));
        tipValuePaint.setFakeBoldText(true);

        tipTimePaint.setColor(0x99FFFFFF);
        tipTimePaint.setTextSize(dp(10f));

        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        gutterPx = dp(30f);
        applyAccent();
    }

    private void applyAccent() {
        linePaint.setColor(accent);
        dotPaint.setColor(accent);
        fillPaint.setColor((accent & 0x00FFFFFF) | 0x1F000000); // 约 12% 透明填充
    }

    public void setMetric(int m) {
        if (metric == m) return;
        metric = m;
        switch (m) {
            case METRIC_VOLT:
                accent = 0xFF2F6FED;
                break;
            case METRIC_AMP:
                accent = 0xFF0891B2;
                break;
            case METRIC_TEMP:
                accent = 0xFFD98514;
                break;
            default:
                accent = 0xFF0E9F6E;
                break;
        }
        cachedLow = Float.NaN; // 换了指标，刻度字符串必须重建
        applyAccent();
        invalidate();
    }

    public int getMetric() {
        return metric;
    }

    /** 该指标的显示单位，供标题区展示 */
    public static String unitOf(int metric) {
        switch (metric) {
            case METRIC_VOLT:
                return "V";
            case METRIC_AMP:
                return "A";
            case METRIC_TEMP:
                return "°C";
            default:
                return "W";
        }
    }

    /** 各指标的最小量程，避免把采样噪声放大成剧烈波动 */
    private float minSpan() {
        switch (metric) {
            case METRIC_VOLT:
                return 0.05f;
            case METRIC_AMP:
                return 0.1f;
            case METRIC_TEMP:
                return 0.5f;
            default:
                return 0.5f;
        }
    }

    private int decimals() {
        switch (metric) {
            case METRIC_TEMP:
                return 1;
            default:
                return 2;
        }
    }

    /* ---------------- 触摸 ---------------- */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                moveCursor(e.getX());
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = Math.abs(e.getX() - downX);
                float dy = Math.abs(e.getY() - downY);
                // 明显是纵向滑动 → 用户想滚页面，清掉游标把事件还给 ScrollView
                if (dy > touchSlop && dy > dx) {
                    clearCursor();
                    return false;
                }
                moveCursor(e.getX());
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                clearCursor();
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    private void moveCursor(float x) {
        if (lastN < 2) return;
        float width = plotRight - plotLeft;
        if (width <= 0f) return;
        float ratio = (x - plotLeft) / width;
        int i = Math.round(ratio * (lastN - 1));
        if (i < 0) i = 0;
        if (i > lastN - 1) i = lastN - 1;
        if (i == cursor) return;
        cursor = i;
        invalidate();
    }

    private void clearCursor() {
        if (cursor < 0) return;
        cursor = -1;
        invalidate();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /* ---------------- 绘制 ---------------- */

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float left = gutterPx;
        float right = w - dp(2f);
        float top = dp(6f);
        float bottom = h - dp(16f); // 底部留给时间轴
        float plotW = right - left;
        float plotH = bottom - top;

        plotLeft = left;
        plotRight = right;
        plotTop = top;
        plotBottom = bottom;

        int n = SampleStore.RING.copyRecent(ts, vBuf, aBuf, tBuf, chBuf);
        lastN = n;
        if (n < 2) {
            c.drawText("还没有开始记录", w / 2f, (top + bottom) / 2f, hintPaint);
            return;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float d;
            switch (metric) {
                case METRIC_VOLT:
                    d = vBuf[i];
                    break;
                case METRIC_AMP:
                    d = aBuf[i];
                    break;
                case METRIC_TEMP:
                    d = tBuf[i];
                    break;
                default:
                    d = vBuf[i] * aBuf[i]; // 功率 = U × I，带符号
                    break;
            }
            work[i] = d;
            if (Float.isNaN(d)) continue;
            if (d < min) min = d;
            if (d > max) max = d;
        }
        if (min > max) {
            c.drawText("暂无有效读数", w / 2f, (top + bottom) / 2f, hintPaint);
            return;
        }

        float[] scale = AxisScale.compute(min, max, minSpan(), 3);
        float low = scale[0];
        float high = scale[1];
        float step = scale[2];
        float span = high - low;
        if (span <= 0f) span = 1f;
        lowBound = low;
        boundSpan = span;

        buildYLabels(low, high, step);

        // 网格线 + 纵轴刻度：0 在量程内时用更实的线，正负一眼分得开
        float labelOffset = (yLabelPaint.descent() + yLabelPaint.ascent()) / 2f;
        for (int i = 0; i < yCount; i++) {
            float value = low + step * i;
            if (value > high + step * 0.01f) break;
            float y = bottom - plotH * (value - low) / span;
            boolean isZero = Math.abs(value) < step * 0.001f;
            c.drawLine(left, y, right, y, isZero ? zeroPaint : gridPaint);
            if (yLabels[i] != null) {
                c.drawText(yLabels[i], left - dp(5f), y - labelOffset, yLabelPaint);
            }
        }

        float zeroY = bottom - plotH * (0f - low) / span;
        float baseline = Math.max(top, Math.min(bottom, zeroY));
        boolean hasZero = low < 0f && high > 0f;
        if (!hasZero) baseline = bottom;

        fillPath.reset();
        linePath.reset();
        boolean started = false;
        float firstX = 0f;
        float lastX = 0f;
        float lastY = 0f;
        for (int i = 0; i < n; i++) {
            float d = work[i];
            if (Float.isNaN(d)) {
                started = false;
                continue;
            }
            float x = left + plotW * i / (n - 1);
            float y = bottom - plotH * (d - low) / span;
            if (y < top) y = top;
            if (y > bottom) y = bottom;
            if (!started) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, baseline);
                fillPath.lineTo(x, y);
                firstX = x;
                started = true;
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        if (lastX > firstX) {
            fillPath.lineTo(lastX, baseline);
            fillPath.close();
            c.drawPath(fillPath, fillPaint);
        }
        c.drawPath(linePath, linePaint);
        if (started) c.drawCircle(lastX, lastY, dp(2.6f), dotPaint);

        updateXLabels(n);
        for (int i = 0; i < 4; i++) {
            if (xLabels[i].isEmpty()) continue;
            float x = left + plotW * i / 3f;
            if (i == 0) {
                axisPaint.setTextAlign(Paint.Align.LEFT);
            } else if (i == 3) {
                axisPaint.setTextAlign(Paint.Align.RIGHT);
                x = right;
            } else {
                axisPaint.setTextAlign(Paint.Align.CENTER);
            }
            c.drawText(xLabels[i], x, h - dp(2f), axisPaint);
        }

        drawCursor(c, left, right, top, bottom, plotW, plotH);
    }

    /** 游标：竖虚线 + 高亮点 + 顶部浮层（时刻 / 数值） */
    private void drawCursor(Canvas c, float left, float right, float top, float bottom,
                            float plotW, float plotH) {
        if (cursor < 0 || cursor >= lastN) return;
        float d = work[cursor];
        if (Float.isNaN(d)) return;

        float x = left + plotW * cursor / (lastN - 1f);
        float y = bottom - plotH * (d - lowBound) / boundSpan;
        if (y < top) y = top;
        if (y > bottom) y = bottom;

        c.drawLine(x, top, x, bottom, cursorPaint);

        // 高亮点：白色描边圈 + 实心点，压在曲线之上
        c.drawCircle(x, y, dp(4.4f), tipBgPaint);
        c.drawCircle(x, y, dp(3.0f), dotPaint);

        String value = PowerMath.fmt(d, decimals()) + " " + unitOf(metric);
        String time = cursorFmt.format(new Date(ts[cursor]));

        float padH = dp(8f);
        float padV = dp(6f);
        float lineGap = dp(2f);
        float timeLine = dp(13f);
        float valueLine = tipValuePaint.descent() - tipValuePaint.ascent();
        float valueW = tipValuePaint.measureText(value);
        float timeW = tipTimePaint.measureText(time);
        float boxW = Math.max(valueW, timeW) + padH * 2f;
        float boxH = padV * 2f + timeLine + lineGap + valueLine;

        float boxLeft = x + dp(9f);
        if (boxLeft + boxW > right) boxLeft = x - dp(9f) - boxW; // 靠右时翻到左侧
        if (boxLeft < left) boxLeft = left;
        float boxTop = top + dp(2f);
        if (boxTop + boxH > bottom) boxTop = bottom - boxH;

        tipRect.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH);
        c.drawRoundRect(tipRect, dp(7f), dp(7f), tipBgPaint);

        float textX = boxLeft + padH;
        float timeBase = boxTop + padV - tipTimePaint.ascent();
        c.drawText(time, textX, timeBase, tipTimePaint);
        float valueBase = boxTop + padV + timeLine + lineGap - tipValuePaint.ascent();
        c.drawText(value, textX, valueBase, tipValuePaint);
    }

    /** 生成纵轴刻度文字，并把左侧留白按最长标签宽度定下来 */
    private void buildYLabels(float low, float high, float step) {
        if (low == cachedLow && high == cachedHigh && step == cachedStep && yCount > 0) return;
        cachedLow = low;
        cachedHigh = high;
        cachedStep = step;

        int decimals = AxisScale.decimalsFor(step);
        String fmt = "%." + decimals + "f";
        int count = Math.round((high - low) / step) + 1;
        if (count < 2) count = 2;
        if (count > MAX_Y_LABELS) count = MAX_Y_LABELS;
        yCount = count;

        float maxWidth = 0f;
        for (int i = 0; i < count; i++) {
            float value = low + step * i;
            // 消除 -0.0 这类显示
            if (Math.abs(value) < step * 0.001f) value = 0f;
            yLabels[i] = String.format(Locale.US, fmt, value);
            float tw = yLabelPaint.measureText(yLabels[i]);
            if (tw > maxWidth) maxWidth = tw;
        }
        for (int i = count; i < MAX_Y_LABELS; i++) yLabels[i] = null;

        float g = maxWidth + dp(9f);
        if (g < dp(26f)) g = dp(26f);
        if (g > dp(48f)) g = dp(48f);
        gutterPx = g;
    }

    /** 时间轴标签每 5 秒刷新一次，避免每帧格式化日期 */
    private void updateXLabels(int n) {
        long now = System.currentTimeMillis();
        if (now - xStamp < 5000L && !xLabels[0].isEmpty()) return;
        xStamp = now;
        if (n < 2) return;
        long t0 = ts[0];
        long t3 = ts[n - 1];
        for (int i = 0; i < 4; i++) {
            long t = t0 + (t3 - t0) * i / 3;
            xLabels[i] = i == 3 ? "现在" : xFmt.format(new Date(t));
        }
    }
}
