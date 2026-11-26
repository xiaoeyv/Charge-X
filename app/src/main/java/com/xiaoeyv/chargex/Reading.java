package com.xiaoeyv.chargex;

/** 一次采样结果（可变对象，采样循环中复用，避免每秒产生垃圾） */
public final class Reading {

    public long timeMs;
    /** 电压 V */
    public double volt;
    /** 电流 A，约定：充电为正、放电为负 */
    public double amp;
    /** 温度 °C，无效时 NaN */
    public double tempC;

    public int status = PowerMath.STATUS_UNKNOWN;
    public int plugged;
    public int health;
    public int level = -1;

    /** 本帧是否用到了 sysfs 直读 */
    public boolean sysfs;
    /** 原始电流 µA（未做符号归一，便于核对硬件约定） */
    public long rawCurUa = Integer.MIN_VALUE;
    public long rawVoltMv = -1;
    public long rawTempTenths = Long.MIN_VALUE;

    public double powerW() {
        if (Double.isNaN(volt) || Double.isNaN(amp)) return Double.NaN;
        return volt * amp;
    }

    public void copyFrom(Reading o) {
        timeMs = o.timeMs;
        volt = o.volt;
        amp = o.amp;
        tempC = o.tempC;
        status = o.status;
        plugged = o.plugged;
        health = o.health;
        level = o.level;
        sysfs = o.sysfs;
        rawCurUa = o.rawCurUa;
        rawVoltMv = o.rawVoltMv;
        rawTempTenths = o.rawTempTenths;
    }
}
