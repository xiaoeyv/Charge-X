package com.xiaoeyv.chargex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AxisScaleTest {

    @Test
    public void scaleSnapsToReadableSteps() {
        float[] s = AxisScale.compute(3.92f, 4.38f, 0.05f, 3);
        float low = s[0], high = s[1], step = s[2];
        assertEquals(0.2f, step, 1e-4f);
        // 刻度必须落在步长的整数倍上，否则会出现 4.13 这种读数
        assertEquals(0f, Math.abs(low / step - Math.round(low / step)), 1e-4f);
        assertEquals(0f, Math.abs(high / step - Math.round(high / step)), 1e-4f);
        assertTrue(low <= 3.92f);
        assertTrue(high >= 4.38f);
    }

    @Test
    public void flatDataUsesMinSpanInsteadOfNoise() {
        // 电压只抖了 2mV，不能把它放大成满屏波动
        float[] s = AxisScale.compute(4.001f, 4.003f, 0.05f, 3);
        assertTrue(s[1] - s[0] >= 0.05f);
    }

    @Test
    public void handlesSingleValue() {
        float[] s = AxisScale.compute(3.3f, 3.3f, 0.5f, 3);
        assertTrue(s[1] > s[0]);
        assertTrue(s[0] <= 3.3f && s[1] >= 3.3f);
    }

    @Test
    public void swappedBoundsAreOk() {
        float[] a = AxisScale.compute(4.4f, 3.9f, 0.05f, 3);
        float[] b = AxisScale.compute(3.9f, 4.4f, 0.05f, 3);
        assertEquals(b[0], a[0], 1e-6f);
        assertEquals(b[1], a[1], 1e-6f);
    }

    @Test
    public void negativeRangeKeepsZeroCrossing() {
        // 放电功率为负：刻度必须同时覆盖 0，才能画出零基线
        float[] s = AxisScale.compute(-8.6f, 1.2f, 0.5f, 3);
        assertTrue(s[0] <= -8.6f);
        assertTrue(s[1] >= 1.2f);
        assertTrue(s[0] < 0f && s[1] > 0f);
    }

    @Test
    public void niceCeilCoversBaseSteps() {
        assertEquals(1f, AxisScale.niceCeil(0.9f), 1e-5f);
        assertEquals(2f, AxisScale.niceCeil(1.7f), 1e-5f);
        assertEquals(2.5f, AxisScale.niceCeil(2.2f), 1e-5f);
        assertEquals(5f, AxisScale.niceCeil(4.1f), 1e-5f);
        assertEquals(10f, AxisScale.niceCeil(9.5f), 1e-5f);
        assertEquals(0.2f, AxisScale.niceCeil(0.15f), 1e-5f);
    }

    @Test
    public void decimalsFollowStep() {
        assertEquals(0, AxisScale.decimalsFor(5f));
        assertEquals(1, AxisScale.decimalsFor(0.5f));
        assertEquals(1, AxisScale.decimalsFor(0.2f));
        assertEquals(2, AxisScale.decimalsFor(0.05f));
        assertEquals(3, AxisScale.decimalsFor(0.005f));
    }

    @Test
    public void tickCountStaysReadable() {
        float[] s = AxisScale.compute(0f, 100f, 1f, 3);
        int ticks = Math.round((s[1] - s[0]) / s[2]);
        assertTrue("刻度过多会挤成一团: " + ticks, ticks <= 6);
    }
}
