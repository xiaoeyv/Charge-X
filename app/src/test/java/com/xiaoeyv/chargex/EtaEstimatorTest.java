package com.xiaoeyv.chargex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class EtaEstimatorTest {

    @Test
    public void extrapolatesLinearRate() {
        // 5 分钟内从 70% 涨到 75%，速率 1%/分钟，剩 25% → 25 分钟
        long startTs = 1_000_000L;
        long endTs = startTs + 5 * 60_000L;
        long eta = EtaEstimator.etaSeconds(70, startTs, 75, endTs);
        assertEquals(25 * 60, eta);
    }

    @Test
    public void rejectsTooShortWindow() {
        // 只有 30 秒样本，1% 的跳动会算出荒谬结果
        long startTs = 0L;
        assertEquals(-1, EtaEstimator.etaSeconds(70, startTs, 71, startTs + 30_000L));
    }

    @Test
    public void rejectsFlatOrFallingLevel() {
        long startTs = 0L;
        long endTs = 5 * 60_000L;
        assertEquals(-1, EtaEstimator.etaSeconds(80, startTs, 80, endTs));
        assertEquals(-1, EtaEstimator.etaSeconds(80, startTs, 79, endTs));
    }

    @Test
    public void handlesFullAndInvalid() {
        long startTs = 0L;
        long endTs = 5 * 60_000L;
        assertEquals(0, EtaEstimator.etaSeconds(95, startTs, 100, endTs));
        assertEquals(-1, EtaEstimator.etaSeconds(-1, startTs, 80, endTs));
        assertEquals(-1, EtaEstimator.etaSeconds(70, endTs, 80, startTs)); // 时间倒挂
    }

    @Test
    public void rejectsAbsurdlyLongEta() {
        // 1 小时才涨 1% → 推算剩余 20 小时，不予显示
        long startTs = 0L;
        long endTs = 60 * 60_000L;
        assertEquals(-1, EtaEstimator.etaSeconds(79, startTs, 80, endTs));
    }

    @Test
    public void humanizesDuration() {
        assertEquals("即将充满", EtaEstimator.humanize(30));
        assertEquals("25 分钟", EtaEstimator.humanize(25 * 60));
        assertEquals("1 小时", EtaEstimator.humanize(3600));
        assertEquals("1 小时 10 分钟", EtaEstimator.humanize(70 * 60));
        assertNull(EtaEstimator.humanize(-1));
    }

    @Test
    public void ringProvidesLevelWindow() {
        SampleRing ring = new SampleRing();
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 60; i++) {
            ring.add(base + i * 1000L, 4.2, 2.0, 33.0, true, 60 + i / 10);
        }
        assertEquals(65, ring.latestLevel());

        long[] first = ring.oldestLevelSince(base + 40_000L);
        // 第 40 秒那一帧的电量为 64
        assertEquals(64, first[0]);
        assertEquals(base + 40_000L, first[1]);

        assertNull(ring.oldestLevelSince(base + 120_000L));
    }
}
