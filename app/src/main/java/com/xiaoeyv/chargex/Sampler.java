package com.xiaoeyv.chargex;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.Map;

/**
 * 双通道采样聚合：sysfs 优先（秒级实时），官方 API 兜底/校准。
 */
public final class Sampler {

    private final Context app;
    private final BatterySource bat;
    private final SysfsSource sysfs;
    private final Reading reading = new Reading();

    /** sysfs 电流单位倍率：1=µA，1000=mA（由官方值自动校准） */
    private int currentScale = 1;
    private int tick;

    /** 电池健康用的静态信息，只在构造时读一次（这些节点不会每秒变化） */
    private int designMah = -1;
    private int fullMah = -1;
    private int cycleCount = -1;

    public Sampler(Context ctx) {
        this.app = ctx.getApplicationContext();
        this.bat = new BatterySource(app);
        this.sysfs = SysfsSource.detect();
        readDeviceHealthInfo();
    }

    /** 一次性把静态信息全部探测出来，结果同时写进 SampleStore.DEVICE 供界面展示 */
    private void readDeviceHealthInfo() {
        DeviceInfo d = probeDevice(app, bat);
        designMah = d.designMah;
        fullMah = d.fullMah;
        cycleCount = d.cycles;
        SampleStore.DEVICE = d;
    }

    /**
     * 探测设备静态信息。四条通道依次兜底：
     * sysfs 单节点 → sysfs uevent → BatteryManager EXTRA → PowerProfile 标称容量。
     *
     * 纯读取，不注册任何接收器，界面侧也能直接调用。
     */
    public static DeviceInfo probeDevice(Context ctx, BatterySource bat) {
        DeviceInfo d = new DeviceInfo();
        SysfsSource sysfs = SysfsSource.detect();

        if (sysfs != null) {
            d.batteryPath = sysfs.path();
            Map<String, String> ue = sysfs.readUevent();

            d.name = SysfsSource.ueText(ue, "NAME");
            d.statusText = SysfsSource.ueText(ue, "STATUS");
            d.healthText = SysfsSource.ueText(ue, "HEALTH");

            d.technology = sysfs.readText(SysfsSource.NODE_TECHNOLOGY);
            if (d.technology == null) d.technology = SysfsSource.ueText(ue, "TECHNOLOGY");

            int design = PowerMath.chargeToMah(sysfs.readLong(SysfsSource.NODE_CHARGE_FULL_DESIGN));
            if (design > 0) {
                d.designSource = "sysfs";
            } else {
                design = PowerMath.chargeToMah(SysfsSource.ueLong(ue, "CHARGE_FULL_DESIGN"));
                if (design > 0) d.designSource = "uevent";
            }
            d.designMah = design;

            int full = PowerMath.chargeToMah(sysfs.readLong(SysfsSource.NODE_CHARGE_FULL));
            if (full <= 0) full = PowerMath.chargeToMah(SysfsSource.ueLong(ue, "CHARGE_FULL"));
            d.fullMah = full;

            long c = sysfs.readLong(SysfsSource.NODE_CYCLE_COUNT);
            if (c <= 0) c = SysfsSource.ueLong(ue, "CYCLE_COUNT");
            if (c > 0 && c < 100_000) d.cycles = (int) c;
        }

        if (bat != null) {
            if (d.technology == null) d.technology = bat.technology();
            if (d.cycles < 0) d.cycles = bat.cycleCount();
            d.maxChargingUa = bat.maxChargingUa();
            d.maxChargingUv = bat.maxChargingUv();
        }

        // 设计容量最后一档：系统 PowerProfile 里的标称值
        if (d.designMah <= 0 && ctx != null) {
            int pp = PowerProfileSource.batteryCapacityMah(ctx);
            if (pp > 0) {
                d.designMah = pp;
                d.designSource = "power_profile";
            }
        }

        d.chargerPath = SysfsSource.chargerDir();
        d.chargerMaxMv = SysfsSource.chargerMaxVoltageMv();
        d.chargerMaxMa = SysfsSource.chargerMaxCurrentMa();
        // 最后探一次：有没有机型直接给了健康度百分比
        d.sohPct = SysfsSource.probeSohPct();
        return d;
    }

    /**
     * 服务未启动时（用户关掉了记录），界面也能拿到容量、循环次数与设备信息，
     * 否则健康卡会一直停在"待测"。
     */
    public static void feedDeviceInfo(Context ctx, HealthTracker health) {
        if (ctx == null) return;
        try {
            BatterySource bat = new BatterySource(ctx.getApplicationContext());
            DeviceInfo d = probeDevice(ctx, bat);
            SampleStore.DEVICE = d;
            if (health != null) {
                health.setDeviceInfo(d.designMah, d.fullMah, d.cycles, bat.health(), d.sohPct);
            }
        } catch (Exception ignored) {
        }
    }

    public int designMah() {
        return designMah;
    }

    public int fullMah() {
        return fullMah;
    }

    public int cycleCount() {
        return cycleCount;
    }

    public void onBatteryIntent(Intent i) {
        bat.set(i);
    }

    public BatterySource battery() {
        return bat;
    }

    public boolean sysfsAvailable() {
        return sysfs != null;
    }

    public String sysfsPath() {
        return sysfs == null ? "-" : sysfs.path();
    }

    /** 采样一帧。返回的对象每秒复用，调用方需立刻取值 */
    public Reading sample() {
        long now = System.currentTimeMillis();
        // 粘性广播由系统广播驱动；这里兜底定期刷新，防止个别机型不回调
        if ((tick++ % 5) == 0) bat.refresh(app);

        int status = bat.status();
        long mv = bat.voltageMv();
        long tenths = bat.tempTenths();
        int curUaInt = bat.currentNowUa();
        long curUa = PowerMath.isInvalid(curUaInt) ? Long.MIN_VALUE : curUaInt;
        boolean usedSysfs = false;

        if (sysfs != null) {
            long v = PowerMath.sysfsVoltageToMv(sysfs.readLong(SysfsSource.NODE_VOLTAGE));
            if (v > 0) {
                mv = v;
                usedSysfs = true;
            }
            long t = PowerMath.sysfsTempToTenths(sysfs.readLong(SysfsSource.NODE_TEMP));
            if (t != Long.MIN_VALUE) {
                tenths = t;
                usedSysfs = true;
            }
            long c = sysfs.readLong(SysfsSource.NODE_CURRENT);
            if (c != Long.MIN_VALUE) {
                if (c != 0) {
                    int s = PowerMath.detectCurrentScale(curUaInt, c);
                    if (s != 0) currentScale = s; // 0=官方值不可用，沿用上次判断
                }
                curUa = c * currentScale;
                usedSysfs = true;
            }
        }

        // 合理性校验：手机任何时刻功率都不会超过 300W，超了说明 sysfs 单位是 mA
        if (curUa != Long.MIN_VALUE && mv > 0 && currentScale == 1) {
            double w = Math.abs(PowerMath.watt(mv, curUa));
            if (w > 300) {
                currentScale = 1000;
                curUa = Math.round(curUa / 1000.0);
            }
        }

        long normUa = curUa == Long.MIN_VALUE
                ? Long.MIN_VALUE
                : PowerMath.normalizeCurrentUa(curUa, status, false);

        reading.timeMs = now;
        reading.volt = mv > 0 ? PowerMath.mvToVolt(mv) : Double.NaN;
        reading.amp = normUa == Long.MIN_VALUE ? Double.NaN : PowerMath.uaToAmp(normUa);
        reading.tempC = tenths == Long.MIN_VALUE ? Double.NaN : PowerMath.tenthsToCelsius(tenths);
        reading.status = status;
        reading.plugged = bat.plugged();
        reading.health = bat.health();
        reading.level = bat.level();
        reading.sysfs = usedSysfs;
        reading.rawCurUa = curUa;
        reading.rawVoltMv = mv;
        reading.rawTempTenths = tenths;

        return reading;
    }

    /** 设备启动至今的单调时钟，仅用于调试日志对齐，不参与业务 */
    public static long uptimeMs() {
        return SystemClock.elapsedRealtime();
    }
}
