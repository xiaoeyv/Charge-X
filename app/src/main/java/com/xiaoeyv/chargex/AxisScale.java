package com.xiaoeyv.chargex;

/**
 * 纵轴刻度算法：把"数据真实范围"变成"人能读的整数格"。
 *
 * 如果直接用 min/max 当刻度，会得到 4.352 / 4.216 / 4.081 这种读数，
 * 一屏里没人愿意做这种心算。这里把步长吸附到 1 / 2 / 2.5 / 5 / 10 × 10ⁿ，
 * 刻度就变成 3.8 / 4.0 / 4.2 / 4.4 这种一眼能读的值。
 *
 * 纯函数，无 android 依赖，可直接单测。
 */
public final class AxisScale {

    private static final float[] NICE = {1f, 2f, 2.5f, 5f, 10f};
    private static final int MAX_TICKS = 6;

    private AxisScale() {
    }

    /**
     * @param dataMin 数据最小值
     * @param dataMax 数据最大值
     * @param minSpan 最小可视跨度（数据太平坦时用它撑开，避免把噪声放大成剧烈波动）
     * @param ticks   期望的格子数（实际可能多一格，取决于吸附结果）
     * @return {下限, 上限, 步长}
     */
    public static float[] compute(float dataMin, float dataMax, float minSpan, int ticks) {
        float min = dataMin;
        float max = dataMax;
        if (max < min) {
            float t = min;
            min = max;
            max = t;
        }
        if (Float.isNaN(min) || Float.isNaN(max) || Float.isInfinite(min) || Float.isInfinite(max)) {
            min = 0f;
            max = Math.max(minSpan, 1f);
        }

        float span = max - min;
        if (span < minSpan) {
            float mid = (min + max) / 2f;
            min = mid - minSpan / 2f;
            max = mid + minSpan / 2f;
            span = max - min;
        }

        // 上下留白，曲线不会贴边
        float pad = span * 0.10f;
        min -= pad;
        max += pad;

        float step = niceCeil((max - min) / Math.max(1, ticks));
        float low = (float) (Math.floor(min / step) * step);
        float high = (float) (Math.ceil(max / step) * step);
        if (high - low < step) high = low + step;

        // 吸附后可能格数过多（例如步长 0.5 而跨度 4），逐步放大步长
        int guard = 0;
        while (Math.round((high - low) / step) > MAX_TICKS && guard++ < 8) {
            step = niceCeil(step * 1.5f);
            low = (float) (Math.floor(min / step) * step);
            high = (float) (Math.ceil(max / step) * step);
        }

        return new float[]{round(low), round(high), round(step)};
    }

    /** 向上取整到 1/2/2.5/5/10 × 10ⁿ */
    static float niceCeil(float v) {
        if (v <= 0f || Float.isNaN(v)) return 1f;
        float exp = (float) Math.floor(Math.log10(v));
        float base = (float) Math.pow(10, exp);
        float f = v / base;
        for (float n : NICE) {
            if (f <= n + 1e-4f) return n * base;
        }
        return 10f * base;
    }

    /** 刻度文字用几位小数：由步长决定，避免 "4.0" 和 "4.00" 混排 */
    public static int decimalsFor(float step) {
        if (step <= 0f) return 0;
        if (step >= 10f) return 0;
        int digits = 0;
        float s = step;
        while (s < 1f - 1e-4f && digits < 4) {
            s *= 10f;
            digits++;
        }
        // s 落在 [1,10)：整数则当前位数够用，带小数（2.5）再多一位
        if (Math.abs(s - Math.rint(s)) < 1e-3f) return digits;
        return Math.min(4, digits + 1);
    }

    /** 去掉 float 累积误差（3.8000002 → 3.8） */
    private static float round(float v) {
        return Math.round(v * 1e6f) / 1e6f;
    }
}
