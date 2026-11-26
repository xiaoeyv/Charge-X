package com.xiaoeyv.chargex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PowerMathTest {

    private static final double EPS = 1e-6;

    @Test
    public void wattIsVoltTimesAmp() {
        // 4.2V × 2A = 8.4W
        assertEquals(8.4, PowerMath.watt(4200, 2_000_000), EPS);
        // 4.35V × 0.45A ≈ 1.9575W
        assertEquals(1.9575, PowerMath.watt(4350, 450_000), EPS);
        // 放电 4.0V × -0.5A = -2W
        assertEquals(-2.0, PowerMath.watt(4000, -500_000), EPS);
    }

    @Test
    public void unitConversion() {
        assertEquals(4.2, PowerMath.mvToVolt(4200), EPS);
        assertEquals(1.5, PowerMath.uaToAmp(1_500_000), EPS);
        assertEquals(35.0, PowerMath.tenthsToCelsius(350), EPS);
    }

    @Test
    public void sysfsVoltageUnitHeuristics() {
        assertEquals(4200, PowerMath.sysfsVoltageToMv(4_200_000L)); // µV
        assertEquals(4200, PowerMath.sysfsVoltageToMv(4200L));      // mV
        assertEquals(-1, PowerMath.sysfsVoltageToMv(0));
        assertEquals(-1, PowerMath.sysfsVoltageToMv(999L));
        assertEquals(-1, PowerMath.sysfsVoltageToMv(-5L));
    }

    @Test
    public void sysfsTempUnitHeuristics() {
        assertEquals(350, PowerMath.sysfsTempToTenths(350));      // 0.1°C
        assertEquals(350, PowerMath.sysfsTempToTenths(35_000L));  // m°C
        assertEquals(410, PowerMath.sysfsTempToTenths(410));      // 41.0°C
        assertEquals(Long.MIN_VALUE, PowerMath.sysfsTempToTenths(0));
        assertEquals(Long.MIN_VALUE, PowerMath.sysfsTempToTenths(-10));
    }

    @Test
    public void currentScaleCalibration() {
        // sysfs 用 mA 写入，官方值 µA → 差 1000 倍
        assertEquals(1000, PowerMath.detectCurrentScale(-1_500_000, -450));
        // 两边都是 µA
        assertEquals(1, PowerMath.detectCurrentScale(-1_500_000, -1_480_000));
        // 官方无数据时不下结论
        assertEquals(0, PowerMath.detectCurrentScale(Integer.MIN_VALUE, -450));
        assertEquals(0, PowerMath.detectCurrentScale(0, -450));
        assertEquals(0, PowerMath.detectCurrentScale(-1_500_000, 0));
    }

    @Test
    public void currentSignNormalization() {
        // 约定：充电为正
        assertEquals(1_800_000, PowerMath.normalizeCurrentUa(-1_800_000, PowerMath.STATUS_CHARGING, false));
        assertEquals(1_800_000, PowerMath.normalizeCurrentUa(1_800_000, PowerMath.STATUS_CHARGING, false));
        // 放电为负
        assertEquals(-500_000, PowerMath.normalizeCurrentUa(500_000, PowerMath.STATUS_DISCHARGING, false));
        assertEquals(-500_000, PowerMath.normalizeCurrentUa(-500_000, PowerMath.STATUS_DISCHARGING, false));
        // 手动反号（仅影响状态未知的读数；已知充放电状态一律按约定归正）
        assertEquals(123, PowerMath.normalizeCurrentUa(-123, PowerMath.STATUS_UNKNOWN, true));
        // 状态未知时保持原样
        assertEquals(-123, PowerMath.normalizeCurrentUa(-123, PowerMath.STATUS_UNKNOWN, false));
    }

    @Test
    public void sysfsStringParsing() {
        assertEquals(4_200_000L, SysfsSource.parseLeadingLong("4200000\n"));
        assertEquals(-450L, SysfsSource.parseLeadingLong("-450 mA"));
        assertEquals(350L, SysfsSource.parseLeadingLong(" 350 "));
        assertEquals(Long.MIN_VALUE, SysfsSource.parseLeadingLong("Charging"));
        assertEquals(Long.MIN_VALUE, SysfsSource.parseLeadingLong(""));
    }

    @Test
    public void ringKeepsOrderAndOverflows() {
        SampleRing ring = new SampleRing();
        for (int i = 0; i < 10; i++) {
            ring.add(1000L + i, 4.0 + i * 0.01, 1.0 + i, 30.0 + i, i % 2 == 0, 50 + i);
        }
        assertEquals(10, ring.size());

        long[] ts = new long[8];
        float[] v = new float[8];
        float[] a = new float[8];
        float[] t = new float[8];
        byte[] ch = new byte[8];
        int n = ring.copyRecent(ts, v, a, t, ch);
        assertEquals(8, n);
        // 最近 8 条，时间正序：i = 2..9
        assertEquals(1002L, ts[0]);
        assertEquals(1009L, ts[7]);
        assertEquals(4.02, v[0], 1e-4);
        assertEquals(10.0, a[7], 1e-4);

        ring.clear();
        assertEquals(0, ring.size());
        assertTrue(ring.isEmpty());
    }

    @Test
    public void ringWrapsAroundAtCapacity() {
        SampleRing ring = new SampleRing();
        int total = SampleRing.CAPACITY + 5;
        for (int i = 0; i < total; i++) {
            ring.add(i, 4.0, 1.0, 30.0, true, 60);
        }
        assertEquals(SampleRing.CAPACITY, ring.size());

        long[] ts = new long[SampleRing.CAPACITY];
        float[] v = new float[SampleRing.CAPACITY];
        float[] a = new float[SampleRing.CAPACITY];
        float[] t = new float[SampleRing.CAPACITY];
        byte[] ch = new byte[SampleRing.CAPACITY];
        int n = ring.copyRecent(ts, v, a, t, ch);
        assertEquals(SampleRing.CAPACITY, n);
        assertEquals(5L, ts[0]);                       // 最旧的被覆盖
        assertEquals(total - 1, ts[SampleRing.CAPACITY - 1]);
        assertEquals(5L, ring.firstTs());
        assertEquals(total - 1, ring.lastTs());
    }

    @Test
    public void chargeNodeUnitIsSniffed() {
        // 内核多数给 µAh
        assertEquals(4400, PowerMath.chargeToMah(4_400_000L));
        assertEquals(3800, PowerMath.chargeToMah(3_800_000L));
        // 少数机型直接给 mAh
        assertEquals(4400, PowerMath.chargeToMah(4400L));
        // 无效值
        assertEquals(-1, PowerMath.chargeToMah(-1L));
        assertEquals(-1, PowerMath.chargeToMah(0L));
        assertEquals(-1, PowerMath.chargeToMah(50L));
    }
}
