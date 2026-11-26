package com.xiaoeyv.chargex;

import java.util.Locale;

/**
 * 全部为纯函数：单位换算、功率计算、符号归一、单位校准。
 * 刻意不引用 android.* 常量，保证可在 JVM 上直接单测。
 */
public final class PowerMath {

    private PowerMath() {
    }

    /* 与 android.os.BatteryManager 的 EXTRA_STATUS 取值保持一致 */
    public static final int STATUS_UNKNOWN = 1;
    public static final int STATUS_CHARGING = 2;
    public static final int STATUS_DISCHARGING = 3;
    public static final int STATUS_NOT_CHARGING = 4;
    public static final int STATUS_FULL = 5;

    /** 官方 API 用它表示"该机型不支持此属性" */
    public static boolean isInvalid(int raw) {
        return raw == Integer.MIN_VALUE;
    }

    public static boolean isCharging(int status) {
        return status == STATUS_CHARGING || status == STATUS_FULL;
    }

    /**
     * sysfs voltage_now -> mV。
     * 绝大多数内核是 µV（4200000），少数是 mV（4200）。
     */
    public static long sysfsVoltageToMv(long raw) {
        if (raw <= 0) return -1L;
        if (raw > 100_000L) return Math.round(raw / 1000.0);        // µV
        if (raw >= 2_000L && raw <= 6_000L) return raw;             // 已是 mV
        return -1L;
    }

    /**
     * sysfs temp -> 0.1°C（与 EXTRA_TEMPERATURE 单位对齐）。
     * 常见 350 表示 35.0°C；部分内核用 m°C，35000。
     */
    public static long sysfsTempToTenths(long raw) {
        if (raw <= 0) return Long.MIN_VALUE;
        if (raw > 1_500L) return Math.round(raw / 100.0);           // m°C
        return raw;                                                 // 已是 0.1°C
    }

    /**
     * 用官方 CURRENT_NOW(µA) 校准 sysfs 电流单位。
     * @return 1 = sysfs 是 µA；1000 = sysfs 是 mA；0 = 无法判断（保持上一次倍率）
     */
    public static int detectCurrentScale(int officialUa, long sysfsRaw) {
        if (isInvalid(officialUa) || officialUa == 0 || sysfsRaw == 0) return 0;
        double ratio = Math.abs((double) officialUa / (double) sysfsRaw);
        if (ratio > 200 && ratio < 5000) return 1000;
        if (ratio > 0.2 && ratio < 5) return 1;
        return 0;
    }

    /**
     * 符号归一：统一约定 —— 充电为正、放电为负。
     * 不同机型 current_now 符号相反，统一后曲线才不会正负乱跳。
     */
    public static long normalizeCurrentUa(long rawUa, int status, boolean invert) {
        long v = invert ? -rawUa : rawUa;
        if (status == STATUS_CHARGING || status == STATUS_FULL) {
            return Math.abs(v);
        }
        if (status == STATUS_DISCHARGING) {
            return -Math.abs(v);
        }
        return v;
    }

    /** P(W) = V(v) × I(A) = mv × µA / 1e9 */
    public static double watt(long mv, long ua) {
        return mv * (double) ua / 1e9;
    }

    public static double mvToVolt(long mv) {
        return mv / 1000.0;
    }

    public static double uaToAmp(long ua) {
        return ua / 1_000_000.0;
    }

    public static double tenthsToCelsius(long tenths) {
        return tenths / 10.0;
    }

    /**
     * sysfs 容量 -> mAh。
     * 内核约定是 µAh（4400000），但个别机型直接给 mAh（4400）。
     * 分界取 20000：低于它的不可能是 µAh 的电池容量，也不可能是 mAh 的笔记本电池。
     * @return mAh；无效返回 -1
     */
    public static int chargeToMah(long raw) {
        if (raw <= 0) return -1;
        long mah = raw > 20_000L ? Math.round(raw / 1000.0) : raw;
        if (mah < 100 || mah > 100_000) return -1;
        return (int) mah;
    }

    public static String fmt(double v, int digits) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return String.format(Locale.US, "%." + digits + "f", v);
    }
}
