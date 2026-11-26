package com.xiaoeyv.chargex;

/** 服务与界面之间的进程内共享状态（零 IPC 开销） */
public final class SampleStore {

    public static final SampleRing RING = new SampleRing();
    /** 最新一帧，volatile 保证可见性 */
    public static volatile Reading LATEST = new Reading();
    public static volatile boolean RUNNING = false;
    public static volatile String SOURCE = "-";
    public static volatile String SOURCE_PATH = "-";

    /** 电池健康跟踪器（单实例，服务与界面共用） */
    public static volatile HealthTracker HEALTH;

    /** 设备静态信息（电池类型、循环次数、设计容量、充电器上限…） */
    public static volatile DeviceInfo DEVICE = new DeviceInfo();

    private SampleStore() {
    }
}
