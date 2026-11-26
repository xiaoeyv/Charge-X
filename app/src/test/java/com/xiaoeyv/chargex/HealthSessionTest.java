package com.xiaoeyv.chargex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HealthSessionTest {

    private static final long T0 = 1_700_000_000_000L;

    /**
     * 2A 恒流、每分钟涨 1%：1% 对应 2A×60s = 33.33mAh，满容量约 3333mAh。
     * 从 40% 充到 65%，跨 25 个百分点，只产出一次估计（够 20% 就重置锚点）。
     */
    @Test
    public void measuresCapacityFromContinuousCharge() {
        HealthSession s = new HealthSession();
        int last = -1;
        for (int i = 0; i <= 1500; i++) {
            int level = 40 + i / 60;
            int est = s.onSample(T0 + i * 1000L, 2.0, level, true);
            if (est > 0) last = est;
        }
        assertTrue("应算出约 3333mAh，实际 " + last, last > 3200 && last < 3450);
        assertEquals(1, s.estimateCount());
    }

    @Test
    public void doesNotDoubleCountSameSegment() {
        HealthSession s = new HealthSession();
        int calls = 0;
        for (int i = 0; i <= 1680; i++) {
            int level = 40 + i / 60;   // 40% → 68%
            if (s.onSample(T0 + i * 1000L, 2.0, level, true) > 0) calls++;
        }
        // 40→60 产出一次；60→68 不够 20 个点，不会再算
        assertEquals(1, calls);
        assertTrue(s.averageMah() > 3200 && s.averageMah() < 3450);
    }

    @Test
    public void restartAfterLongGap() {
        HealthSession s = new HealthSession();
        s.onSample(T0, 2.0, 50, true);
        for (int i = 1; i <= 100; i++) {
            s.onSample(T0 + i * 1000L, 2.0, 51, true);
        }
        // 断档 5 分钟后继续：累计必须清零重来，否则会把断档期间的电算成 0
        int est = s.onSample(T0 + 100_000L + 300_000L, 2.0, 51, true);
        assertEquals(-1, est);
        assertEquals(0, s.progressMah());
    }

    @Test
    public void dischargeResetsAnchor() {
        HealthSession s = new HealthSession();
        for (int i = 0; i <= 300; i++) {
            s.onSample(T0 + i * 1000L, 2.0, 60, true);
        }
        assertTrue(s.addedMah() > 0);
        // 拔线放电：电量掉了，之前的累计不再可信
        s.onSample(T0 + 301_000L, -1.5, 58, false);
        assertEquals(-1, s.onSample(T0 + 302_000L, 2.0, 58, true));
        assertEquals(0, s.progressMah());
    }

    @Test
    public void shortCurrentDipKeepsSession() {
        HealthSession s = new HealthSession();
        for (int i = 0; i <= 60; i++) {
            s.onSample(T0 + i * 1000L, 2.0, 50, true);
        }
        double before = s.addedMah();
        // 5 秒钟电流掉到 0（充电器协商中），不应该作废
        for (int i = 61; i <= 66; i++) {
            assertEquals(-1, s.onSample(T0 + i * 1000L, 0.0, 50, true));
        }
        s.onSample(T0 + 67_000L, 2.0, 50, true);
        assertTrue(s.addedMah() > before);
    }

    @Test
    public void levelDropRestartsAccumulation() {
        HealthSession s = new HealthSession();
        for (int i = 0; i <= 120; i++) {
            s.onSample(T0 + i * 1000L, 2.0, 70, true);
        }
        assertTrue(s.addedMah() > 0);
        s.onSample(T0 + 121_000L, 2.0, 69, true); // 电量计回跳 1%
        assertEquals(0, s.progressMah());
    }

    /**
     * 电量计校准跳变：系统偶尔把 50 直接报成 55（重新校准），
     * 这 5 个百分点不是充出来的，必须整段作废，否则容量直接被低估。
     */
    @Test
    public void levelJumpDiscardsAccumulation() {
        HealthSession s = new HealthSession();
        for (int i = 0; i <= 300; i++) {
            s.onSample(T0 + i * 1000L, 2.0, 50, true);
        }
        assertTrue(s.addedMah() > 0);
        s.onSample(T0 + 301_000L, 2.0, 55, true);
        assertEquals(0, s.progressMah());
        assertEquals(55, s.anchorLevel());
    }

    /** 低电量段 SOC 受电压回弹影响会虚高，不能拿来当锚点 */
    @Test
    public void anchorSkippedOutsideTrustedRange() {
        HealthSession s = new HealthSession();
        for (int i = 0; i <= 600; i++) {
            s.onSample(T0 + i * 1000L, 2.0, 10, true);
        }
        assertEquals(-1, s.anchorLevel());
        assertEquals(0, s.progressMah());
    }

    /**
     * 一次离谱的估计不能污染整体结论 —— 这正是之前从 64% 掉到 41% 的机制：
     * 电量计跳变算出一个 1/3 的值，简单平均直接把它固化进去了。
     */
    @Test
    public void outlierEstimateIsTrimmed() {
        HealthSession s = new HealthSession();
        long ts = T0;
        for (int c = 0; c < 5; c++) {
            for (int i = 0; i <= 1200; i++) {
                s.onSample(ts + i * 1000L, 2.0, 25 + i / 60, true);
            }
            ts += 1_200_000L;
            s.onSample(ts, -1.0, 25, false);   // 模拟放回 25%
            ts += 1000L;
        }
        assertEquals(5, s.estimateCount());
        int before = s.averageMah();
        assertTrue("均值应约 3333，实际 " + before, before > 3200 && before < 3450);

        // 第 6 次：电流只有 0.2A 却同样涨了 20%，会算出 333mAh 的离谱值
        for (int i = 0; i <= 1200; i++) {
            s.onSample(ts + i * 1000L, 0.2, 25 + i / 60, true);
        }
        assertEquals("离群值必须被剔除，均值不能动", before, s.averageMah());
        assertEquals(5, s.estimateCount());
    }

    @Test
    public void ignoresInvalidLevelAndNanCurrent() {
        HealthSession s = new HealthSession();
        assertEquals(-1, s.onSample(T0, 2.0, -1, true));
        assertEquals(-1, s.onSample(T0 + 1000L, Double.NaN, 50, true));
        assertEquals(-1, s.onSample(T0 + 2000L, 1.0, 50, false));
    }

    @Test
    public void resetClearsEverything() {
        HealthSession s = new HealthSession();
        for (int i = 0; i <= 120; i++) {
            s.onSample(T0 + i * 1000L, 2.0, 50, true);
        }
        s.reset();
        assertEquals(0, s.progressMah());
        assertEquals(-1, s.anchorLevel());
        assertEquals(-1, s.averageMah());
    }

    @Test
    public void restoreKeepsSessionState() {
        HealthSession s = new HealthSession();
        s.restore(64, 120.0, T0, 0, 0.0, -1);
        assertEquals(64, s.anchorLevel());
        assertEquals(120, s.progressMah());
        assertTrue(s.progressLevelDelta(70) == 6);
    }

    /** 升级前存的是"次数 + 总和"，要能反推成一个可用样本，别让老用户从零开始 */
    @Test
    public void restoreRebuildsEstimateFromLegacyAverage() {
        HealthSession s = new HealthSession();
        s.restore(64, 0.0, T0, 3, 9600.0, -1);
        assertEquals(1, s.estimateCount());
        assertEquals(3200, s.averageMah());
    }
}
