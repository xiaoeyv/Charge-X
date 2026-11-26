package com.xiaoeyv.chargex;

/**
 * 定长环形缓冲：只用基本类型数组存采样，零对象开销。
 * 14400 点 ≈ 4 小时（1s 一采），常驻内存约 314KB；满了覆盖最旧记录。
 */
public final class SampleRing {

    public static final int CAPACITY = 14400;

    private final long[] ts = new long[CAPACITY];
    private final float[] volt = new float[CAPACITY];
    private final float[] amp = new float[CAPACITY];
    private final float[] temp = new float[CAPACITY];
    private final byte[] charging = new byte[CAPACITY];
    /** 电量百分比 0..100，-1 表示该帧无电量数据（用于充满时间外推） */
    private final byte[] level = new byte[CAPACITY];

    private int size;
    private int head; // 下一个写入位置

    public synchronized void add(long timeMs, double v, double a, double t, boolean isCharging, int levelPercent) {
        ts[head] = timeMs;
        volt[head] = (float) v;
        amp[head] = (float) a;
        temp[head] = Double.isNaN(t) ? Float.NaN : (float) t;
        charging[head] = (byte) (isCharging ? 1 : 0);
        level[head] = (byte) (levelPercent < 0 || levelPercent > 100 ? -1 : levelPercent);
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) size++;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized void clear() {
        size = 0;
        head = 0;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized long firstTs() {
        if (size == 0) return 0L;
        return ts[(head - size + CAPACITY) % CAPACITY];
    }

    public synchronized long lastTs() {
        if (size == 0) return 0L;
        return ts[(head - 1 + CAPACITY) % CAPACITY];
    }

    public synchronized int latestLevel() {
        if (size == 0) return -1;
        return level[(head - 1 + CAPACITY) % CAPACITY];
    }

    /**
     * 取不早于 cutoffMs 的最早一个有效电量采样，返回 {电量, 时间戳}；无则 null。
     * 供预计充满时间做线性外推。
     */
    public synchronized long[] oldestLevelSince(long cutoffMs) {
        if (size == 0) return null;
        int start = (head - size + CAPACITY) % CAPACITY;
        for (int i = 0; i < size; i++) {
            int idx = (start + i) % CAPACITY;
            if (ts[idx] >= cutoffMs && level[idx] >= 0) {
                return new long[]{level[idx], ts[idx]};
            }
        }
        return null;
    }

    /**
     * 把最近的 n 个点按 时间正序 拷进调用方复用的数组，返回实际条数。
     * 图表每帧调用，避免任何内存分配。
     */
    public synchronized int copyRecent(long[] outTs, float[] outV, float[] outA, float[] outT, byte[] outCharging) {
        int n = Math.min(size, outTs.length);
        int start = (head - n + CAPACITY) % CAPACITY;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % CAPACITY;
            outTs[i] = ts[idx];
            outV[i] = volt[idx];
            outA[i] = amp[idx];
            outT[i] = temp[idx];
            outCharging[i] = charging[idx];
        }
        return n;
    }

    /** 导出用：返回按时间正序的副本 */
    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot(size);
        int start = (head - size + CAPACITY) % CAPACITY;
        for (int i = 0; i < size; i++) {
            int idx = (start + i) % CAPACITY;
            s.ts[i] = ts[idx];
            s.volt[i] = volt[idx];
            s.amp[i] = amp[idx];
            s.temp[i] = temp[idx];
            s.charging[i] = charging[idx];
        }
        return s;
    }

    public static final class Snapshot {
        public final int size;
        public final long[] ts;
        public final float[] volt;
        public final float[] amp;
        public final float[] temp;
        public final byte[] charging;

        Snapshot(int n) {
            this.size = n;
            this.ts = new long[n];
            this.volt = new float[n];
            this.amp = new float[n];
            this.temp = new float[n];
            this.charging = new byte[n];
        }
    }
}
