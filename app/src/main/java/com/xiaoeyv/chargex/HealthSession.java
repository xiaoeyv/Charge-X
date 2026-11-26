package com.xiaoeyv.chargex;

/**
 * 库仑积分式容量测量（纯 JVM，可单测）。
 *
 * 原理：电池里装了多少电荷，可以直接"灌"出来量。
 * 从电量 45% 充到 65%，这一路累计充入多少 mAh，满容量就是 充入量 ÷ 20%。
 *
 * 但这个方法的误差来源比想象的多，每一条都会把结果往"偏小"的方向拉，必须逐一堵住：
 *
 *   1. 窗口太窄 —— 电量计是整数百分比，±1% 的量化误差在 10% 窗口里被放大 10 倍。
 *      所以要求至少 20 个百分点，宁可晚一点出结果，也不要出一个歪值。
 *   2. SOC 两端非线性 —— 低电量段受电压回弹影响会"虚高"，高电量末段是恒压涓流，
 *      两者都会让 ΔSOC 偏离真实电荷增量。所以只在 25%~90% 之间锚定。
 *   3. 电量计校准跳变 —— 系统偶尔会把 60 直接报成 64（重新校准），
 *      这一段并不是真的充了 4%。检测到单帧跳变就作废重来。
 *   4. 采样断档 —— 服务被杀、锁屏省电都会断，断档期间的电荷算 0 会严重低估。
 *      间隔超过 60 秒就重新锚定。
 *   5. 单次离群值污染 —— 一次异常估计（比如充入 50mAh 却涨了 20%）会把均值拖到沟里，
 *      而且之后再也爬不出来。所以保留最近若干次，用截尾均值，把最大最小剔掉。
 */
public final class HealthSession {

    /** 电量至少要涨这么多百分点才算一次估计 */
    public static final int MIN_LEVEL_DELTA = 20;
    /** 至少要充进这么多电荷 */
    public static final int MIN_ADDED_MAH = 40;
    /** 采样间隔超过这个值就认为断档，锚点重来 */
    public static final long MAX_GAP_MS = 60_000L;
    /** 低于这个电流视为没在充 */
    public static final double MIN_AMP = 0.05;

    /** 可信锚点区间：两端 SOC 曲线非线性最严重 */
    public static final int ANCHOR_MIN_LEVEL = 25;
    public static final int ANCHOR_MAX_LEVEL = 90;
    /** 单帧电量跳这么多，说明电量计在校准，这段增量不可信 */
    public static final int JUMP_THRESHOLD = 3;

    /** 保留最近若干次估计，用截尾均值抗离群 */
    private static final int WINDOW = 8;

    private final int[] history = new int[WINDOW];
    private int histCount;
    private int histPos;

    private int anchorLevel = -1;
    private int lastLevel = -1;
    private double addedMah;
    private long lastTs;
    private int lastEstimateMah = -1;
    /** 被离群判据丢掉的次数，用于判断"是误判还是电池真的变了" */
    private int rejected;

    /**
     * 喂一帧采样。
     *
     * @param charging 本帧是否处于充电态
     * @return 本次新产出的容量估计 mAh；没有则 -1
     */
    public int onSample(long ts, double ampA, int level, boolean charging) {
        if (level < 0 || level > 100 || !charging
                || Double.isNaN(ampA) || ampA <= MIN_AMP) {
            // 充电中断：不累计，但保留锚点，允许短暂波动后继续
            lastTs = ts;
            lastLevel = level;
            return -1;
        }

        // 电量计校准跳变：这一帧的电量增量不是充出来的，锚点整段作废
        boolean jumped = lastLevel >= 0 && Math.abs(level - lastLevel) > JUMP_THRESHOLD;
        lastLevel = level;

        if (jumped || lastTs <= 0L || ts < lastTs || ts - lastTs > MAX_GAP_MS) {
            anchorLevel = -1;
            addedMah = 0;
            lastTs = ts;
        }

        // 锚点只建立在可信区间内：低于 25% 会因为电压回弹虚高，高于 90% 是恒压涓流
        if (anchorLevel < 0) {
            lastTs = ts;
            if (level < ANCHOR_MIN_LEVEL || level > ANCHOR_MAX_LEVEL) return -1;
            anchorLevel = level;
            addedMah = 0;
            return -1;
        }

        // 按真实时间间隔积分。断档已在上面拦掉（>60s 重新锚定），所以这里不需要截断上限，
        // 截断会让断档期间充进去的电凭空消失，正是之前低估的来源之一。
        long dt = ts - lastTs;
        // I(A) × t(ms) ÷ 3600 = mAh
        addedMah += ampA * dt / 3600.0;
        lastTs = ts;

        int delta = level - anchorLevel;
        if (delta < 0) {
            // 电量掉了：之前的区间不再可信，重新锚定
            anchorLevel = level;
            addedMah = 0;
            return -1;
        }
        if (delta < MIN_LEVEL_DELTA || addedMah < MIN_ADDED_MAH) return -1;

        int cap = HealthEstimator.capacityFromCharge(addedMah, delta);
        // 无论估计值是否可用，区间都重开，避免同一段电量被反复计入
        anchorLevel = level;
        addedMah = 0;
        if (cap < 0) return -1;

        if (!accept(cap)) return -1;
        lastEstimateMah = cap;
        return cap;
    }

    /**
     * 离群值剔除。
     *
     * 已有 ≥3 次估计时，新值若偏离中位数 30% 以上，先判为污染并丢弃；
     * 但如果连续丢弃 3 次，说明电池状态真的变了（换电池、电量计重置），
     * 此时清空历史接受新值 —— 否则会永远卡在旧值上。
     */
    private boolean accept(int cap) {
        if (histCount >= 3) {
            int med = median();
            if (med > 0 && Math.abs(cap - med) > med * 0.30) {
                if (++rejected < 3) return false;
                clearHistory();
            }
        }
        rejected = 0;
        history[histPos] = cap;
        histPos = (histPos + 1) % WINDOW;
        if (histCount < WINDOW) histCount++;
        return true;
    }

    /**
     * 稳健容量 = 截尾均值。
     *
     * 不用中位数：样本只有 3~5 个时中位数太粗糙（会退化成"取中间那一个"）。
     * 也不用算术平均：一个离群值就足以把结果拖偏。
     * ≥5 个样本时去掉最大最小再平均，两头都堵住。
     */
    public int averageMah() {
        if (histCount == 0) return -1;
        int[] sorted = new int[histCount];
        System.arraycopy(history, 0, sorted, 0, histCount);
        java.util.Arrays.sort(sorted);
        if (histCount < 5) {
            long sum = 0;
            for (int v : sorted) sum += v;
            return (int) Math.round((double) sum / histCount);
        }
        long sum = 0;
        for (int i = 1; i < histCount - 1; i++) sum += sorted[i];
        return (int) Math.round((double) sum / (histCount - 2));
    }

    private int median() {
        if (histCount == 0) return -1;
        int[] sorted = new int[histCount];
        System.arraycopy(history, 0, sorted, 0, histCount);
        java.util.Arrays.sort(sorted);
        return sorted[histCount / 2];
    }

    private void clearHistory() {
        histCount = 0;
        histPos = 0;
        rejected = 0;
        java.util.Arrays.fill(history, 0);
    }

    public int lastEstimateMah() {
        return lastEstimateMah;
    }

    public int estimateCount() {
        return histCount;
    }

    /** 当前锚点之后已充的电量增量（百分点），用于界面进度 */
    public int progressLevelDelta(int currentLevel) {
        if (anchorLevel < 0 || currentLevel < anchorLevel) return 0;
        return currentLevel - anchorLevel;
    }

    public int progressMah() {
        return (int) Math.round(addedMah);
    }

    public void reset() {
        anchorLevel = -1;
        lastLevel = -1;
        addedMah = 0;
        lastTs = 0L;
        clearHistory();
        lastEstimateMah = -1;
    }

    /* ---- 持久化用：跨进程重启恢复未完成的测量段 ---- */

    public int anchorLevel() {
        return anchorLevel;
    }

    public void restore(int anchorLevel, double addedMah, long lastTs,
                        int estimateCount, double estimateSum, int lastEstimateMah) {
        this.anchorLevel = anchorLevel;
        this.addedMah = addedMah;
        this.lastTs = lastTs;
        this.lastEstimateMah = lastEstimateMah;
        // 旧版本存的是"次数 + 总和"，无法还原成样本序列。
        // 用总和反推一个均值填进窗口，保证老用户升级后不至于从头再来。
        clearHistory();
        if (estimateCount > 0 && estimateSum > 0) {
            int avg = (int) Math.round(estimateSum / estimateCount);
            if (avg >= HealthEstimator.MIN_CAPACITY_MAH
                    && avg <= HealthEstimator.MAX_CAPACITY_MAH) {
                history[0] = avg;
                histPos = 1;
                histCount = 1;
            }
        }
    }

    public long lastTs() {
        return lastTs;
    }

    public double addedMah() {
        return addedMah;
    }

    /** 兼容旧接口：现在没有"总和"，返回 均值 × 样本数 */
    public double estimateSum() {
        int avg = averageMah();
        return avg <= 0 ? 0 : (double) avg * histCount;
    }
}
