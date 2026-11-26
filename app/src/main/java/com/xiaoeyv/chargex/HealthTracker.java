package com.xiaoeyv.chargex;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 电池健康的运行时 + 持久化层。
 *
 * 只有一个模式：自动。用户不用按任何按钮 —— 充电时后台就在累计电荷，
 * 充够约 10% 就产出一次容量估计，多次估计取均值收敛。
 * 这么做是因为"手动开始测量"对普通人来说是个无法理解的开关：
 * 不知道什么时候该点、点了要不要守着、忘了点会不会没数据。
 *
 * 结果、未完成的测量现场全部写进 SharedPreferences，
 * 重启 App、重启手机都不丢。
 *
 * 单实例：通过 {@link #get(Context)} 获取，服务与界面共用同一份内存状态。
 */
public final class HealthTracker {

    private static final String PREFS = "health";

    private static final String K_SCHEMA = "schema";
    /**
     * 测量口径版本。每次改算法就 +1。
     *
     * v2：窗口从 10% 提到 20%、锚点限制在 25%~90%、离群值剔除、按真实间隔积分。
     * v1 存下来的实测容量普遍偏低 30% 以上（电量计跳变 + 简单平均），
     * 留着只会让用户继续看到错的健康度，所以升级时直接清掉重测。
     */
    private static final int SCHEMA = 2;

    private static final String K_SCORE = "score";
    private static final String K_CAP_PCT = "cap_pct";
    private static final String K_MEASURED_MAH = "measured_mah";
    private static final String K_FULL_MAH = "full_mah";
    private static final String K_DESIGN_MAH = "design_mah";
    private static final String K_CYCLES = "cycles";
    private static final String K_STATUS = "status_code";
    private static final String K_CAP_BASED = "cap_based";
    private static final String K_MEASURED_BASED = "measured_based";
    private static final String K_UPDATED_TS = "updated_ts";
    private static final String K_PEAK_TEMP = "peak_temp";
    // 未完成测量段的现场
    private static final String K_SESS_ANCHOR = "sess_anchor";
    private static final String K_SESS_ADDED = "sess_added";
    private static final String K_SESS_LAST_TS = "sess_last_ts";
    private static final String K_SESS_COUNT = "sess_count";
    private static final String K_SESS_SUM = "sess_sum";
    private static final String K_SESS_LAST_MAH = "sess_last_mah";

    private static final long SAVE_PROGRESS_INTERVAL_MS = 20_000L;

    private final SharedPreferences prefs;
    private final HealthSession session = new HealthSession();

    private long updatedTs;
    private float peakTemp = Float.NaN;

    /** sysfs / PowerProfile 提供的设备信息 */
    private int designMah = -1;
    private int fullMah = -1;
    private int cycles = -1;
    private int statusCode = -1;
    private int sohPct = -1;

    private HealthEstimator.Result result = new HealthEstimator.Result();
    private long lastProgressSave;

    private HealthTracker(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    /** 单实例入口：服务与界面各自取都能拿到同一份 */
    public static synchronized HealthTracker get(Context ctx) {
        HealthTracker t = SampleStore.HEALTH;
        if (t == null) {
            t = new HealthTracker(ctx.getApplicationContext());
            SampleStore.HEALTH = t;
        }
        return t;
    }

    /* ---------------- 持久化 ---------------- */

    private void load() {
        // 口径升级：旧版本的实测容量不可信，清掉重测（标称容量、循环次数这些不丢）
        if (prefs.getInt(K_SCHEMA, 1) < SCHEMA) {
            prefs.edit()
                    .remove(K_SCORE).remove(K_CAP_PCT).remove(K_MEASURED_MAH)
                    .remove(K_CAP_BASED).remove(K_MEASURED_BASED)
                    .remove(K_SESS_ANCHOR).remove(K_SESS_ADDED).remove(K_SESS_LAST_TS)
                    .remove(K_SESS_COUNT).remove(K_SESS_SUM).remove(K_SESS_LAST_MAH)
                    .putInt(K_SCHEMA, SCHEMA)
                    .apply();
        }

        updatedTs = prefs.getLong(K_UPDATED_TS, 0L);
        peakTemp = prefs.getFloat(K_PEAK_TEMP, Float.NaN);

        HealthEstimator.Result r = new HealthEstimator.Result();
        r.score = prefs.getInt(K_SCORE, -1);
        r.capacityPct = prefs.getInt(K_CAP_PCT, -1);
        r.measuredMah = prefs.getInt(K_MEASURED_MAH, -1);
        r.fullMah = prefs.getInt(K_FULL_MAH, -1);
        r.designMah = prefs.getInt(K_DESIGN_MAH, -1);
        r.cycles = prefs.getInt(K_CYCLES, -1);
        r.statusCode = prefs.getInt(K_STATUS, -1);
        r.capacityBased = prefs.getBoolean(K_CAP_BASED, false);
        r.measuredBased = prefs.getBoolean(K_MEASURED_BASED, false);
        result = r;

        session.restore(prefs.getInt(K_SESS_ANCHOR, -1), prefs.getFloat(K_SESS_ADDED, 0f),
                prefs.getLong(K_SESS_LAST_TS, 0L), prefs.getInt(K_SESS_COUNT, 0),
                prefs.getFloat(K_SESS_SUM, 0f), prefs.getInt(K_SESS_LAST_MAH, -1));
    }

    private void persist() {
        prefs.edit()
                .putInt(K_SCHEMA, SCHEMA)
                .putInt(K_SCORE, result.score)
                .putInt(K_CAP_PCT, result.capacityPct)
                .putInt(K_MEASURED_MAH, result.measuredMah)
                .putInt(K_FULL_MAH, result.fullMah)
                .putInt(K_DESIGN_MAH, result.designMah)
                .putInt(K_CYCLES, result.cycles)
                .putInt(K_STATUS, result.statusCode)
                .putBoolean(K_CAP_BASED, result.capacityBased)
                .putBoolean(K_MEASURED_BASED, result.measuredBased)
                .putLong(K_UPDATED_TS, updatedTs)
                .putFloat(K_PEAK_TEMP, peakTemp)
                .apply();
        saveSession();
    }

    private void saveSession() {
        prefs.edit()
                .putInt(K_SESS_ANCHOR, session.anchorLevel())
                .putFloat(K_SESS_ADDED, (float) session.addedMah())
                .putLong(K_SESS_LAST_TS, session.lastTs())
                .putInt(K_SESS_COUNT, session.estimateCount())
                .putFloat(K_SESS_SUM, (float) session.estimateSum())
                .putInt(K_SESS_LAST_MAH, session.estimateCount() == 0 ? -1 : session.averageMah())
                .apply();
    }

    /** 测量现场每 20 秒落一次盘，避免每秒写盘 */
    private void saveProgressIfDue(long now) {
        if (now - lastProgressSave < SAVE_PROGRESS_INTERVAL_MS) return;
        lastProgressSave = now;
        saveSession();
    }

    /* ---------------- 采样输入 ---------------- */

    /** sysfs / 官方 API / PowerProfile 提供的静态设备信息，拿到就立刻重算一次 */
    public void setDeviceInfo(int designMah, int fullMah, int cycles, int statusCode) {
        setDeviceInfo(designMah, fullMah, cycles, statusCode, -1);
    }

    public void setDeviceInfo(int designMah, int fullMah, int cycles, int statusCode, int sohPct) {
        boolean changed = this.designMah != designMah || this.fullMah != fullMah
                || this.cycles != cycles || this.sohPct != sohPct;
        this.designMah = designMah;
        this.fullMah = fullMah;
        this.cycles = cycles;
        this.sohPct = sohPct;
        if (statusCode >= 0 && statusCode != this.statusCode) {
            this.statusCode = statusCode;
            changed = true;
        }
        if (changed) {
            boolean hadCapacity = result.capacityBased;
            recompute(System.currentTimeMillis(), hadCapacity);
        }
    }

    public void onSample(long ts, double ampA, int level, boolean charging,
                         int healthStatusCode, double tempC) {
        if (healthStatusCode > 0 && healthStatusCode != statusCode) {
            statusCode = healthStatusCode;
            recompute(ts, false);
        }
        // 只有"充电时的高温"才计入劣化（打游戏发热不该算电池账）
        if (charging && !Double.isNaN(tempC)) {
            if (Float.isNaN(peakTemp) || tempC > peakTemp) {
                peakTemp = (float) tempC;
                if (peakTemp > 40f) recompute(ts, false);
            }
        }

        int est = session.onSample(ts, ampA, level, charging);
        if (est > 0) {
            int avg = session.averageMah();
            result.measuredMah = avg > 0 ? avg : est;
            recompute(ts, false);
            updatedTs = ts;
            persist();
            return;
        }
        saveProgressIfDue(ts);
    }

    private void recompute(long ts, boolean wasCapacityBased) {
        HealthEstimator.Result r = HealthEstimator.evaluate(
                designMah, fullMah, effectiveMeasured(), cycles, statusCode, peakTemp, sohPct);
        result = r;
        if (r.capacityBased && !wasCapacityBased) {
            updatedTs = ts; // 首次拿到容量口径的结果，记一次"测量时间"
        }
        persist();
    }

    /** 实测值可能来自历史持久化，重算时不能丢 */
    private int effectiveMeasured() {
        if (result.measuredMah > 0) return result.measuredMah;
        return session.averageMah();
    }

    /* ---------------- 对外读取 ---------------- */

    public HealthEstimator.Result result() {
        return result;
    }

    public long updatedTs() {
        return updatedTs;
    }

    public int designMah() {
        return designMah;
    }

    public int fullMah() {
        return fullMah;
    }

    public float peakTemp() {
        return peakTemp;
    }

    /* ---- 实时进度（界面每 500ms 读一次） ---- */

    /** 当前锚点之后电量涨了几个百分点 */
    public int progressLevelDelta(int currentLevel) {
        return session.progressLevelDelta(currentLevel);
    }

    public int progressMah() {
        return session.progressMah();
    }

    /** 一次测量需要充入的百分点，界面用来算进度 */
    public int progressTargetPct() {
        return HealthSession.MIN_LEVEL_DELTA;
    }

    /**
     * 测量进度 0..1。
     *
     * 出结果需要同时满足"电量涨够"和"电荷充够"，所以取两者的较小值 ——
     * 用最大值会让用户看着条走满了却迟迟没结果，体感更差。
     */
    /** 一次测量需要充入的电荷量 mAh，界面用来算进度 */
    public int progressTargetMah() {
        int c = designMah > 0 ? designMah : session.averageMah();
        if (c <= 0) return HealthSession.MIN_ADDED_MAH;
        return Math.max(HealthSession.MIN_ADDED_MAH,
                Math.round(c * HealthSession.MIN_LEVEL_DELTA / 100f));
    }

    /**
     * 测量进度 0..1。
     *
     * 出结果需要同时满足"电量涨够"和"电荷充够"，所以取两者的较小值 ——
     * 用最大值会让用户看着条走满了却迟迟没结果，体感更差。
     */
    public float progressFraction(int currentLevel) {
        float lv = session.progressLevelDelta(currentLevel)
                / (float) HealthSession.MIN_LEVEL_DELTA;
        float mh = session.progressMah() / (float) progressTargetMah();
        float f = Math.min(lv, mh);
        if (f < 0f) return 0f;
        return f > 1f ? 1f : f;
    }

    /** sysfs 直读的健康度，-1 表示该机型没暴露 */
    public int sohPct() {
        return sohPct;
    }
}
