package com.xiaoeyv.chargex;

/**
 * 一次探测到的设备静态信息。
 *
 * 这里收拢所有"不是每秒都在变"的字段：电池类型、循环次数、设计容量、
 * 充电器上限、内核节点路径……它们来自 sysfs 节点、sysfs uevent、
 * BatteryManager 的 EXTRA、PowerProfile 四条通道，界面一次性展示。
 *
 * 动态量（电压/电流/温度/电量/供电方式）不在这里，它们随帧变化。
 */
public final class DeviceInfo {

    /** 电池节点目录，如 /sys/class/power_supply/battery；不可用时 null */
    public String batteryPath;
    /** 充电器节点目录，如 /sys/class/power_supply/usb；不可用时 null */
    public String chargerPath;

    /** 内核 uevent 的 NAME 字段，多数是 "battery" */
    public String name;
    /** 电池类型，如 "Li-ion"；来自 sysfs technology 或 EXTRA_TECHNOLOGY */
    public String technology;
    /** 内核原始状态文本，如 "Charging" / "Discharging" / "Full" */
    public String statusText;
    /** 内核原始健康文本，如 "Good" / "Overheat" */
    public String healthText;

    /** 出厂设计（标称）容量 mAh；-1 未知 */
    public int designMah = -1;
    /** 当前满充容量 mAh；-1 未知 */
    public int fullMah = -1;
    /** 设计容量是从哪来的：sysfs / uevent / power_profile / none */
    public String designSource = "none";
    /** 循环次数；-1 未知 */
    public int cycles = -1;

    /**
     * sysfs 直读的健康度百分比；-1 表示该机型没暴露这个节点。
     * 有值时它就是权威口径 —— 系统设置页显示的电池健康度通常同源于此。
     */
    public int sohPct = -1;

    /** 健康度来源，界面用来标注口径：sysfs_soh / sysfs_full / measured / score */
    public String healthSource = "score";

    /** 充电器声明的最大电压 mV；-1 未知 */
    public long chargerMaxMv = -1;
    /** 充电器声明的最大电流 mA；-1 未知 */
    public long chargerMaxMa = -1;

    /** BatteryManager 声明最大充电电流 µA（Integer.MIN_VALUE = 不支持） */
    public int maxChargingUa = Integer.MIN_VALUE;
    /** BatteryManager 声明最大充电电压 mV（Integer.MIN_VALUE = 不支持） */
    public int maxChargingUv = Integer.MIN_VALUE;

    /** technology 的展示文案，缺失时按"未提供" */
    public String technologyLabel() {
        if (technology == null || technology.isEmpty()) return "未提供";
        String t = technology.trim();
        if ("Li-ion".equalsIgnoreCase(t)) return "锂离子（Li-ion）";
        if ("Li-poly".equalsIgnoreCase(t) || "Li-polymer".equalsIgnoreCase(t)) return "锂聚合物";
        if ("LiFePO4".equalsIgnoreCase(t)) return "磷酸铁锂";
        if ("NiMH".equalsIgnoreCase(t)) return "镍氢";
        if ("NiCd".equalsIgnoreCase(t)) return "镍镉";
        return t;
    }

    /** 内核状态文本翻译；未知返回 null */
    public static String statusLabel(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(java.util.Locale.US);
        if (s.startsWith("charg")) return "正在充电";
        if (s.startsWith("discharg")) return "正在放电";
        if (s.equals("full")) return "已充满";
        if (s.equals("not charging")) return "未充电";
        return raw;
    }

    /** 内核健康文本翻译；未知返回 null */
    public static String healthLabel(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(java.util.Locale.US);
        if (s.equals("good")) return "状态正常";
        if (s.equals("overheat")) return "电池过热";
        if (s.equals("dead")) return "已损坏";
        if (s.equals("over voltage")) return "电压异常";
        if (s.equals("cold")) return "温度过低";
        if (s.equals("unspecified failure")) return "状态异常";
        return raw;
    }
}
