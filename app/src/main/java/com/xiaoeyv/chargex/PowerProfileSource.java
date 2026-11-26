package com.xiaoeyv.chargex;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 从系统 PowerProfile 里取电池标称容量。
 *
 * 为什么需要它：健康度必须靠"实际容量 ÷ 总容量"才算得出百分比，
 * 而 charge_full_design 在很多机型上被 SELinux 挡住或干脆不存在。
 * PowerProfile 是 SystemUI、设置页统计耗电时用的系统内置配置，
 * 里面有 mBatteryCapacity（mAh），几乎每台机器都有。
 *
 * 它是 @hide 类，只能用反射。取不到不影响其它功能，只是少一档兜底。
 * 注意：这个值是厂商按机型填的"标称容量"，个别机型会偏保守（例如标称 4500
 * 实际 4600），所以它只作为最后的兜底，优先级低于 sysfs 的真实设计容量。
 */
public final class PowerProfileSource {

    /** 标称容量 mAh；取不到返回 -1 */
    public static int batteryCapacityMah(Context ctx) {
        if (ctx == null) return -1;
        Context app = ctx.getApplicationContext();
        // 8.0+ 拆成了 android.os.PowerProfile（旧版是 com.android.internal.os.PowerProfile）
        String[] classes = {
                "android.os.PowerProfile",
                "com.android.internal.os.PowerProfile",
        };
        for (String name : classes) {
            int mah = tryLoad(name, app);
            if (mah > 0) return mah;
        }
        return -1;
    }

    private static int tryLoad(String className, Context ctx) {
        try {
            Class<?> clazz = Class.forName(className);
            Constructor<?> ctor = clazz.getConstructor(Context.class);
            Object profile = ctor.newInstance(ctx);
            Method m = clazz.getMethod("getBatteryCapacity");
            Object v = m.invoke(profile);
            if (!(v instanceof Number)) return -1;
            double mah = ((Number) v).doubleValue();
            // 个别 ROM 这里给的是 Ah（4.4），不是 mAh
            if (mah > 0.5 && mah < 60) mah *= 1000;
            if (mah < 200 || mah > 30_000) return -1; // 明显不是手机电池，别信
            return (int) Math.round(mah);
        } catch (Throwable ignored) {
            // 隐藏 API 在部分 ROM 上不存在或被限制，安静降级
            return -1;
        }
    }

    private PowerProfileSource() {
    }
}
