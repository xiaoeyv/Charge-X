package com.xiaoeyv.chargex;

/**
 * 预计充满时间：取一段时间窗内的电量增长率做线性外推。
 * 纯函数，便于单测；数据不足或速率不增时返回 -1（界面隐藏该行，而不是显示错误值）。
 */
public final class EtaEstimator {

    /** 最短采样窗口，太短的窗口里 1% 的抖动会算出荒谬结果 */
    public static final long MIN_WINDOW_MS = 60_000L;
    /** 超过 6 小时不予显示（多半是涓流充电或数据异常） */
    public static final long MAX_ETA_SECONDS = 6 * 3600L;

    private EtaEstimator() {
    }

    /**
     * @return 预计充满秒数；0 表示即将充满；-1 表示无法估算
     */
    public static long etaSeconds(int startLevel, long startTs, int endLevel, long endTs) {
        if (startLevel < 0 || startLevel > 100 || endLevel < 0 || endLevel > 100) return -1;
        long dt = endTs - startTs;
        if (dt < MIN_WINDOW_MS) return -1;
        if (endLevel >= 100) return 0;
        int gained = endLevel - startLevel;
        if (gained <= 0) return -1; // 没在涨就别猜了
        double percentPerMs = gained / (double) dt;
        double remainMs = (100.0 - endLevel) / percentPerMs;
        long sec = Math.round(remainMs / 1000.0);
        if (sec < 0 || sec > MAX_ETA_SECONDS) return -1;
        return sec;
    }

    /** 转人话："即将充满" / "25 分钟" / "1 小时 10 分钟"；无法估算返回 null */
    public static String humanize(long seconds) {
        if (seconds < 0) return null;
        if (seconds < 60) return "即将充满";
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) return minutes + " 分钟";
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分钟";
    }
}
