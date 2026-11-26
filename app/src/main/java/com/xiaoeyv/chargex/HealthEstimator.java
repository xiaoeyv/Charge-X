package com.xiaoeyv.chargex;

/**
 * 电池健康度评估（纯函数，可单测）。
 *
 * 诚实地说：Android 没有一条公开 API 能告诉你"健康度 64%"。
 * EXTRA_HEALTH 只是 0/1 级的粗状态（良好/过热/损坏），拿不到容量衰减。
 *
 * 所以按可信度从高到低分四级取值，并把口径写进结果里让界面如实标注：
 *
 *   ① SOH 直读 —— 少数机型在 sysfs 直接暴露 soh / state_of_health，
 *      这就是系统设置页里那个数字的同源数据，读到就直接用，不做任何加工。
 *   ② 系统容量口径 —— sysfs charge_full ÷ charge_full_design。
 *      满充容量确实低于标称时这才是真实衰减；两者相等说明节点报的是标称值，不能用。
 *   ③ 实测容量口径 —— 库仑积分推算。受电量计精度限制，通常比系统值保守。
 *   ④ 兜底评分 —— 100 - 循环次数惩罚 - 状态惩罚 - 高温惩罚，只能定性。
 *
 * 关键原则：宁可显示"待测"，也不给一个看起来精确的错误数字。
 */
public final class HealthEstimator {

    /* 与 android.os.BatteryManager 的 EXTRA_HEALTH 取值保持一致 */
    public static final int HEALTH_UNKNOWN = 1;
    public static final int HEALTH_GOOD = 2;
    public static final int HEALTH_OVERHEAT = 3;
    public static final int HEALTH_DEAD = 4;
    public static final int HEALTH_OVER_VOLTAGE = 5;
    public static final int HEALTH_UNSPECIFIED_FAILURE = 6;
    public static final int HEALTH_COLD = 7;

    /** 合理的实测容量区间，超出说明积分过程被污染了 */
    public static final int MIN_CAPACITY_MAH = 300;
    public static final int MAX_CAPACITY_MAH = 20000;

    /** 手机锂电池在正常使用年限内不会跌破标称的这个比例，低于它说明测量被污染 */
    private static final float MIN_PLAUSIBLE_RATIO = 0.45f;
    /** 高于标称太多说明节点单位或语义对不上，不采信 */
    private static final float MAX_PLAUSIBLE_RATIO = 1.10f;

    /* 数据来源，决定界面上的口径文案 */
    public static final String SRC_SOH = "soh";
    public static final String SRC_FULL = "full";
    public static final String SRC_MEASURED = "measured";
    public static final String SRC_SCORE = "score";

    private HealthEstimator() {
    }

    /** 一次评估的结果，字段全部可直接持久化 */
    public static final class Result {
        /** 0..100；-1 表示数据不足，无法评估 */
        public int score = -1;
        /** 容量保持率 %；-1 表示未知 */
        public int capacityPct = -1;
        /** 实测容量 mAh（库仑积分）；-1 表示未知 */
        public int measuredMah = -1;
        /** 满充容量 mAh（sysfs charge_full）；-1 表示未知 */
        public int fullMah = -1;
        /** 总容量（标称/设计）mAh；-1 表示未知 */
        public int designMah = -1;
        /** sysfs 直读的健康度 %；-1 表示该机型没暴露 */
        public int sohPct = -1;
        /** 循环次数；-1 表示未知 */
        public int cycles = -1;
        /** BatteryManager 健康状态码；-1 表示未知 */
        public int statusCode = -1;
        /** true=分数来自容量保持率（可信）；false=来自循环/温度估算（参考） */
        public boolean capacityBased;
        /** 本次读数是否来自实测库仑积分（而不是节点直读），决定文案口径 */
        public boolean measuredBased;
        /** 取数口径：soh / full / measured / score */
        public String source = SRC_SCORE;

        /** 总容量（出厂标称），-1 未知 */
        public int totalMah() {
            return designMah > 0 ? designMah : -1;
        }

        /** 实际容量：现在这块电池还能装多少 */
        public int actualMah() {
            // SOH 直读时，用标称容量按比例反推出"现在能装多少"，供容量条绘制
            if (SRC_SOH.equals(source) && sohPct > 0 && designMah > 0) {
                return Math.round(designMah * sohPct / 100f);
            }
            return chooseActual(designMah, fullMah, measuredMah);
        }

        /** 兼容旧调用点，等同实际容量 */
        public int displayMah() {
            return actualMah();
        }

        /** 老化损失 mAh；没测出时为 0（界面按"暂未损失"画满） */
        public int lossMah() {
            int t = totalMah();
            int a = actualMah();
            if (t <= 0 || a <= 0) return 0;
            int loss = t - a;
            return loss > 0 ? loss : 0;
        }

        /** 实际容量占总容量的比例 0..1；未测出时返回 1（条画满） */
        public float actualFraction() {
            int t = totalMah();
            int a = actualMah();
            if (t <= 0 || a <= 0) return 1f;
            float f = a / (float) t;
            if (f < 0f) return 0f;
            return f > 1f ? 1f : f;
        }

        public boolean known() {
            return score >= 0;
        }

        /** 是否精确到百分比的容量口径（否则是 0~100 的模糊评分） */
        public boolean percentBased() {
            return capacityBased && (SRC_SOH.equals(source) || SRC_FULL.equals(source)
                    || SRC_MEASURED.equals(source));
        }
    }

    /** 兼容旧签名：不带 SOH 直读值 */
    public static Result evaluate(int designMah, int fullMah, int measuredMah,
                                  int cycles, int statusCode, float maxTempC) {
        return evaluate(designMah, fullMah, measuredMah, cycles, statusCode, maxTempC, -1);
    }

    public static Result evaluate(int designMah, int fullMah, int measuredMah,
                                  int cycles, int statusCode, float maxTempC, int sohPct) {
        Result r = new Result();
        r.designMah = designMah > 0 ? designMah : -1;
        r.fullMah = fullMah > 0 ? fullMah : -1;
        r.measuredMah = measuredMah > 0 ? measuredMah : -1;
        r.cycles = cycles >= 0 ? cycles : -1;
        r.statusCode = statusCode >= 0 ? statusCode : -1;
        r.sohPct = isPlausibleSoh(sohPct) ? sohPct : -1;

        // ① SOH 直读：与系统设置页同源，读到就以它为准，不做任何"修正"
        if (r.sohPct > 0) {
            r.score = r.sohPct;
            r.capacityPct = r.sohPct;
            r.capacityBased = true;
            r.measuredBased = false;
            r.source = SRC_SOH;
            return r;
        }

        // ② / ③ 容量口径
        int actual = chooseActual(designMah, fullMah, measuredMah);
        int cap = capacityPercent(r.totalMah(), actual);
        r.capacityPct = cap;
        if (cap >= 0) {
            r.score = cap;
            r.capacityBased = true;
            r.source = actual == measuredMah && measuredMah > 0 ? SRC_MEASURED : SRC_FULL;
            r.measuredBased = SRC_MEASURED.equals(r.source);
            return r;
        }

        // ④ 拿不到容量：用可观测的劣化指标做保守估算
        int penalty = 0;
        boolean known = false;

        if (r.cycles >= 0) {
            known = true;
            // 行业经验值：约 500 次循环剩余 80%
            penalty += Math.min(30, Math.round(r.cycles * 0.04f));
        }
        if (r.statusCode > 0) {
            known = true;
            penalty += statusPenalty(r.statusCode);
        }
        if (!Float.isNaN(maxTempC) && maxTempC > 40f) {
            known = true;
            penalty += maxTempC > 45f ? 8 : 4;
        }
        if (!known) return r; // 什么都不知道，返回 -1 由界面显示"待测"

        r.source = SRC_SCORE;
        r.score = clamp(100 - penalty, 0, 100);
        return r;
    }

    /**
     * 选定用于显示的"实际容量"。
     *
     * 这里有两个相反的坑，之前就是踩了第一个才出现 41% 这种离谱值：
     *
     *   坑 A：部分机型的 charge_full 直接等于 charge_full_design（报的是标称值，
     *         不是当前满充容量）。此时不能拿它算衰减，得靠实测。
     *         —— 但"改用实测"不等于"无条件相信实测"：实测如果只有标称的 41%，
     *         那是测量被污染，不是电池真的废了。
     *
     *   坑 B：实测值受电量计精度影响，本身就可能偏低十几个百分点。
     *         所以它只在没有更好来源时才用，而且要过合理性检查。
     *
     * @return 实际容量 mAh；没有可信来源时返回 -1（界面显示"待测"而不是假数字）
     */
    public static int chooseActual(int designMah, int fullMah, int measuredMah) {
        boolean hasFull = fullMah > 0;
        boolean measuredOk = isPlausibleMeasured(designMah, measuredMah);

        if (hasFull && designMah > 0) {
            float ratio = fullMah / (float) designMah;
            // 明显低于标称：这就是真实衰减，系统口径，直接用
            if (ratio < 0.97f) return fullMah;
            // 高于标称 2% 以上：新电池标定略高，按"没有衰减"处理
            if (ratio > 1.02f) return designMah;
            // 与标称几乎相等：这个节点报的多半就是标称值，代表不了衰减。
            // 有可信实测就用实测；没有就返回 -1 —— 宁可显示"待测"，
            // 也不给一个 100% 的假健康度，那比数字不准更糟。
            return measuredOk ? measuredMah : -1;
        }
        if (hasFull) return fullMah;
        if (measuredOk) return measuredMah;
        return -1;
    }

    /**
     * 实测容量是否落在物理上说得通的范围内。
     *
     * 手机锂电池两年重度使用大约衰减到 70%~80%，跌破标称 45% 的只有极端情况
     * （泡水、长期高温、鼓包）。低于这条线时更可能是测量过程被打断过，
     * 而不是电池报废 —— 这时宁可不显示，也不要吓用户。
     */
    public static boolean isPlausibleMeasured(int designMah, int measuredMah) {
        if (measuredMah <= 0) return false;
        if (measuredMah < MIN_CAPACITY_MAH) return false;
        if (designMah <= 0) return measuredMah <= MAX_CAPACITY_MAH;
        float ratio = measuredMah / (float) designMah;
        return ratio >= MIN_PLAUSIBLE_RATIO && ratio <= MAX_PLAUSIBLE_RATIO;
    }

    /** SOH 直读值的合理范围：30%~100% 之外基本是节点语义不同（比如给了 mAh） */
    public static boolean isPlausibleSoh(int sohPct) {
        return sohPct >= 30 && sohPct <= 100;
    }

    /** 容量保持率 %；参数不全返回 -1 */
    public static int capacityPercent(int designMah, int actualMah) {
        if (designMah <= 0 || actualMah <= 0) return -1;
        int pct = Math.round(actualMah * 100f / designMah);
        return clamp(pct, 0, 100);
    }

    /**
     * 由"充入电量"与"电量增量"推算电池满容量。
     *
     * @param addedMah   该区间内累计充入的电荷 mAh
     * @param levelDelta 区间内电量百分点的增量
     * @return 推算的满容量 mAh；数据不足或结果离谱时返回 -1
     */
    public static int capacityFromCharge(double addedMah, int levelDelta) {
        if (levelDelta <= 0 || Double.isNaN(addedMah) || addedMah <= 0) return -1;
        if (levelDelta < 8) return -1;              // 电量计 ±1% 的误差会被放大 12 倍以上
        if (addedMah < 30) return -1;               // 太少的电量，一次读数抖动就毁了
        int cap = (int) Math.round(addedMah * 100.0 / levelDelta);
        if (cap < MIN_CAPACITY_MAH || cap > MAX_CAPACITY_MAH) return -1;
        return cap;
    }

    public static String grade(int score) {
        if (score < 0) return "待测";
        if (score >= 90) return "优秀";
        if (score >= 80) return "良好";
        if (score >= 60) return "一般";
        return "偏差";
    }

    public static String statusLabel(int code) {
        switch (code) {
            case HEALTH_GOOD:
                return "状态正常";
            case HEALTH_OVERHEAT:
                return "电池过热";
            case HEALTH_DEAD:
                return "已损坏";
            case HEALTH_OVER_VOLTAGE:
                return "电压异常";
            case HEALTH_UNSPECIFIED_FAILURE:
                return "状态异常";
            case HEALTH_COLD:
                return "温度过低";
            default:
                return "未知";
        }
    }

    private static int statusPenalty(int code) {
        switch (code) {
            case HEALTH_GOOD:
                return 0;
            case HEALTH_COLD:
                return 3;
            case HEALTH_OVER_VOLTAGE:
                return 6;
            case HEALTH_OVERHEAT:
                return 8;
            case HEALTH_UNSPECIFIED_FAILURE:
                return 12;
            case HEALTH_DEAD:
                return 40;
            default:
                return 0;
        }
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
