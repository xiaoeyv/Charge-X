package com.xiaoeyv.chargex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HealthEstimatorTest {

    /* ---- 容量口径 ---- */

    @Test
    public void capacityFromSysfsWins() {
        // 设计 4400mAh，现在满充只能装 3800mAh
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 3800, -1, -1, -1, Float.NaN);
        assertTrue(r.capacityBased);
        assertEquals(86, r.capacityPct);
        assertEquals(86, r.score);
        assertEquals("良好", HealthEstimator.grade(r.score));
    }

    @Test
    public void measuredCapacityUsedWhenSysfsMissing() {
        HealthEstimator.Result r = HealthEstimator.evaluate(5000, -1, 4250, -1, -1, Float.NaN);
        assertTrue(r.capacityBased);
        assertEquals(85, r.capacityPct);
        assertEquals(4250, r.displayMah());
    }

    @Test
    public void sysfsFullBeatsMeasured() {
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 3900, 3000, -1, -1, Float.NaN);
        assertEquals(3900, r.displayMah());
        assertEquals(89, r.capacityPct);
    }

    @Test
    public void overshootIsCappedAt100() {
        // 新电池常见 103%，界面不能显示 103%
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 4532, -1, -1, -1, Float.NaN);
        assertEquals(100, r.capacityPct);
        assertEquals(100, r.score);
        assertEquals("优秀", HealthEstimator.grade(r.score));
    }

    /* ---- 兜底评分 ---- */

    @Test
    public void cycleCountFallback() {
        HealthEstimator.Result r = HealthEstimator.evaluate(-1, -1, -1, 500, HealthEstimator.HEALTH_GOOD, Float.NaN);
        assertFalse(r.capacityBased);
        assertEquals(80, r.score); // 100 - 500*0.04 = 80
        assertEquals("良好", HealthEstimator.grade(r.score));
    }

    @Test
    public void cycleCountPenaltyIsCapped() {
        HealthEstimator.Result r = HealthEstimator.evaluate(-1, -1, -1, 5000, HealthEstimator.HEALTH_GOOD, Float.NaN);
        assertEquals(70, r.score); // 惩罚上限 30
    }

    @Test
    public void heatAndStatusAddPenalty() {
        // 500 循环(-20) + 过热(-8) + 峰值 47°C(-8)
        HealthEstimator.Result r = HealthEstimator.evaluate(-1, -1, -1, 500, HealthEstimator.HEALTH_OVERHEAT, 47f);
        assertEquals(64, r.score);
        assertEquals("一般", HealthEstimator.grade(r.score));
    }

    @Test
    public void unknownDataGivesNoScore() {
        HealthEstimator.Result r = HealthEstimator.evaluate(-1, -1, -1, -1, -1, Float.NaN);
        assertEquals(-1, r.score);
        assertFalse(r.known());
        assertEquals("待测", HealthEstimator.grade(r.score));
    }

    @Test
    public void deadBatteryScoresLow() {
        HealthEstimator.Result r = HealthEstimator.evaluate(-1, -1, -1, -1, HealthEstimator.HEALTH_DEAD, Float.NaN);
        assertEquals(60, r.score);
        assertEquals("一般", HealthEstimator.grade(r.score));
        assertEquals("已损坏", HealthEstimator.statusLabel(HealthEstimator.HEALTH_DEAD));
    }

    /* ---- 充入电量 -> 满容量 ---- */

    @Test
    public void capacityFromChargeBasic() {
        // 从 50% 充到 60% 充进 420mAh，则满容量约 4200mAh
        assertEquals(4200, HealthEstimator.capacityFromCharge(420, 10));
    }

    @Test
    public void capacityRejectsSmallDelta() {
        assertEquals(-1, HealthEstimator.capacityFromCharge(400, 3));
        assertEquals(-1, HealthEstimator.capacityFromCharge(400, 0));
    }

    @Test
    public void capacityRejectsTinyCharge() {
        assertEquals(-1, HealthEstimator.capacityFromCharge(12, 10));
    }

    @Test
    public void capacityRejectsAbsurdResult() {
        // 2% 充进 4000mAh 会推出 200000mAh 的电池，显然积分被污染
        assertEquals(-1, HealthEstimator.capacityFromCharge(4000, 2));
        assertEquals(-1, HealthEstimator.capacityFromCharge(10_000, 5));
    }

    @Test
    public void capacityPercentGuards() {
        assertEquals(-1, HealthEstimator.capacityPercent(0, 4000));
        assertEquals(-1, HealthEstimator.capacityPercent(4400, 0));
        assertEquals(100, HealthEstimator.capacityPercent(4400, 4400));
    }

    /* ---- 总容量 / 实际容量 / 老化损失 ---- */

    @Test
    public void lossAndFractionComeFromTotalMinusActual() {
        // 标称 4400，满充只剩 3740 → 损失 660，占比 85%
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 3740, -1, -1, -1, Float.NaN);
        assertEquals(4400, r.totalMah());
        assertEquals(3740, r.actualMah());
        assertEquals(660, r.lossMah());
        assertEquals(0.85f, r.actualFraction(), 0.0001f);
        assertEquals(85, r.capacityPct);
    }

    @Test
    public void unmeasuredMeansNoLossAndFullBar() {
        // 只有标称容量、还没实测：按"没有损失"处理，条画满
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, -1, -1, -1, -1, Float.NaN);
        assertEquals(-1, r.actualMah());
        assertEquals(0, r.lossMah());
        assertEquals(1f, r.actualFraction(), 0.0001f);
        assertEquals(-1, r.capacityPct);
    }

    @Test
    public void nominalFullNodeFallsBackToMeasured() {
        // 有些机型 charge_full 直接等于 charge_full_design，此时实测值才反映真实衰减
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 4400, 3740, -1, -1, Float.NaN);
        assertEquals(3740, r.actualMah());
        assertEquals(660, r.lossMah());
        assertEquals(85, r.capacityPct);
        assertTrue(r.measuredBased);
    }

    @Test
    public void measuredIgnoredWhenFullNodeIsCredible() {
        // full 明显低于 design，说明它是真实满充容量；实测偏低不应覆盖它
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 3900, 3000, -1, -1, Float.NaN);
        assertEquals(3900, r.actualMah());
        assertEquals(500, r.lossMah());
        assertFalse(r.measuredBased);
    }

    @Test
    public void overshootKeepsZeroLoss() {
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 4532, -1, -1, -1, Float.NaN);
        assertEquals(0, r.lossMah());
        assertEquals(1f, r.actualFraction(), 0.0001f);
    }

    /* ---- SOH 直读：与系统设置页同源，优先级最高 ---- */

    @Test
    public void sohDirectReadWins() {
        // 系统直读 64%，此时实测 1804mAh 这种歪值不该参与计算
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 4400, 1804, -1, -1, Float.NaN, 64);
        assertEquals(64, r.score);
        assertEquals(64, r.capacityPct);
        assertEquals(HealthEstimator.SRC_SOH, r.source);
        assertEquals(2816, r.actualMah());   // 4400 × 64% 反推，供容量条绘制
        assertEquals(1584, r.lossMah());
        assertTrue(r.percentBased());
        assertFalse(r.measuredBased);
    }

    @Test
    public void implausibleSohIsIgnored() {
        // 某些节点给的是 mAh 而不是百分比，4400 不能当 4400%
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 3800, -1, -1, -1, Float.NaN, 4400);
        assertEquals(HealthEstimator.SRC_FULL, r.source);
        assertEquals(86, r.capacityPct);
    }

    /* ---- 实测值不可信时宁可显示"待测" ---- */

    /**
     * 这就是线上出现的 41%：charge_full 报的是标称值，实测又只有 1804mAh。
     * 两者都不足采信时，绝不能拿 1804/4400 当结论 —— 那会让用户以为电池报废了。
     */
    @Test
    public void brokenMeasurementIsNotShown() {
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 4400, 1804, -1, -1, Float.NaN);
        assertEquals(-1, r.capacityPct);
        assertFalse(r.capacityBased);
        assertEquals(-1, r.actualMah());
        assertEquals("待测", HealthEstimator.grade(r.score));
        // 原始值仍保留，界面可以如实展示"偏低已忽略"
        assertEquals(1804, r.measuredMah);
    }

    @Test
    public void nominalFullWithTrustedMeasured() {
        // 标称值冒充满充容量，但实测落在合理区间 —— 用它
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 4400, 3100, -1, -1, Float.NaN);
        assertEquals(3100, r.actualMah());
        assertEquals(70, r.capacityPct);
        assertEquals(HealthEstimator.SRC_MEASURED, r.source);
        assertTrue(r.measuredBased);
    }

    @Test
    public void implausibleMeasurementGuards() {
        assertFalse(HealthEstimator.isPlausibleMeasured(4400, 1804));  // 41%，太低
        assertFalse(HealthEstimator.isPlausibleMeasured(4400, 5000));  // 113%，太高
        assertFalse(HealthEstimator.isPlausibleMeasured(4400, -1));
        assertTrue(HealthEstimator.isPlausibleMeasured(4400, 2816));   // 64%，正常
        assertTrue(HealthEstimator.isPlausibleMeasured(-1, 4000));     // 没有标称值时只查绝对区间
    }

    @Test
    public void slightlyAboveNominalIsFullHealth() {
        // 新电池满充容量略高于标称是正常标定偏差，不应算出 103%
        HealthEstimator.Result r = HealthEstimator.evaluate(4400, 4600, -1, -1, -1, Float.NaN);
        assertEquals(100, r.capacityPct);
        assertEquals(0, r.lossMah());
        assertEquals(4400, r.actualMah());
    }
}
