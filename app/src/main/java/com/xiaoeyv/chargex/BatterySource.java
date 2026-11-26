package com.xiaoeyv.chargex;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/** 官方 API 通道：ACTION_BATTERY_CHANGED 粘性广播 + BatteryManager.getIntProperty */
public final class BatterySource {

    private final BatteryManager bm;
    private Intent last;
    private boolean maxChargingProbed;
    private int maxChargingUa = Integer.MIN_VALUE;
    private int maxChargingUv = Integer.MIN_VALUE;

    public BatterySource(Context ctx) {
        this.bm = (BatteryManager) ctx.getApplicationContext().getSystemService(Context.BATTERY_SERVICE);
        refresh(ctx);
    }

    /** 重新取一次粘性广播（不注册接收器，零开销） */
    public void refresh(Context ctx) {
        try {
            Intent i = ctx.getApplicationContext()
                    .registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) last = i;
        } catch (Exception ignored) {
        }
    }

    /** 由 Service 里的接收器实时喂入 */
    public void set(Intent i) {
        if (i != null) last = i;
    }

    public int status() {
        return last == null ? PowerMath.STATUS_UNKNOWN
                : last.getIntExtra(BatteryManager.EXTRA_STATUS, PowerMath.STATUS_UNKNOWN);
    }

    public int plugged() {
        return last == null ? 0 : last.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
    }

    public int health() {
        return last == null ? 1 : last.getIntExtra(BatteryManager.EXTRA_HEALTH, 1);
    }

    public int level() {
        if (last == null) return -1;
        int lv = last.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = last.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (lv < 0 || scale <= 0) return -1;
        return Math.round(lv * 100f / scale);
    }

    /** mV，无效 -1 */
    public long voltageMv() {
        if (last == null) return -1;
        int mv = last.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        return mv > 0 ? mv : -1;
    }

    /** 0.1°C，无效 Long.MIN_VALUE */
    public long tempTenths() {
        if (last == null) return Long.MIN_VALUE;
        int t = last.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return PowerMath.isInvalid(t) ? Long.MIN_VALUE : t;
    }

    /** µA，无效 Integer.MIN_VALUE */
    public int currentNowUa() {
        if (bm == null) return Integer.MIN_VALUE;
        try {
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /** µAh，无效 Integer.MIN_VALUE */
    public int chargeCounterUah() {
        if (bm == null) return Integer.MIN_VALUE;
        try {
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /** 厂商声明的最大充电电流 µA（极少机型有效，key 未公开，故用字面量） */
    public int maxChargingUa() {
        if (!maxChargingProbed) {
            maxChargingProbed = true;
            if (last != null) {
                maxChargingUa = last.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, Integer.MIN_VALUE);
                maxChargingUv = last.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, Integer.MIN_VALUE);
            }
        }
        return maxChargingUa;
    }

    private static final String EXTRA_MAX_CHARGING_CURRENT = "max_charging_current";
    private static final String EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage";
    /** API 34 起才有，常量值未公开，只能用字面量并两种写法都试 */
    private static final String EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT";
    private static final String EXTRA_CYCLE_COUNT_ALT = "cycle_count";

    /** 循环次数；-1 表示该机型未提供 */
    public int cycleCount() {
        if (last == null) return -1;
        int c = last.getIntExtra(EXTRA_CYCLE_COUNT, -1);
        if (c <= 0) c = last.getIntExtra(EXTRA_CYCLE_COUNT_ALT, -1);
        return c > 0 ? c : -1;
    }

    public int maxChargingUv() {
        maxChargingUa();
        return maxChargingUv;
    }

    public String technology() {
        return last == null ? null : last.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
    }
}
