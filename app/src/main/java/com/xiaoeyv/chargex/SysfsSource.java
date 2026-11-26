package com.xiaoeyv.chargex;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 直读 /sys/class/power_supply —— 唯一能做到秒级刷新的手段（官方 API 走 HAL 缓存，10~60s 才变一次）。
 * 非公开接口，部分机型被 SELinux 拦截，读不到就返回无效值由上层降级。
 *
 * 除了逐节点读取，还解析 uevent 全文：那是内核把整块电池信息的键值对一次性铺开的地方，
 * 机型差异比单个节点小得多，是拿"电池类型 / 循环次数 / 设计容量"最稳的一路。
 */
public final class SysfsSource {

    public static final String NODE_CURRENT = "current_now";
    public static final String NODE_CURRENT_AVG = "current_avg";
    public static final String NODE_VOLTAGE = "voltage_now";
    public static final String NODE_TEMP = "temp";
    public static final String NODE_CAPACITY = "capacity";
    public static final String NODE_CHARGE_COUNTER = "charge_counter";
    /** 满充容量（µAh），电池健康度的主要依据 */
    public static final String NODE_CHARGE_FULL = "charge_full";
    /** 出厂设计容量（µAh） */
    public static final String NODE_CHARGE_FULL_DESIGN = "charge_full_design";
    /** 循环次数 */
    public static final String NODE_CYCLE_COUNT = "cycle_count";
    public static final String NODE_STATUS = "status";
    public static final String NODE_HEALTH = "health";
    public static final String NODE_TECHNOLOGY = "technology";

    /**
     * 部分厂商机型直接暴露健康度百分比，读到它比任何估算都准 ——
     * 这正是系统设置页"电池健康度"同源的数据，所以必须优先尝试。
     * 只在 30~100 之间采信，避免把温度之类的小数值误当 SOH。
     */
    private static final String[] SOH_NODES = {
            "soh", "state_of_health", "battery_soh", "batt_soh",
            "health_capacity", "charge_health", "battery_health",
    };

    /** uevent 里的键名前缀 */
    private static final String UE = "POWER_SUPPLY_";

    private static final String[] CANDIDATES = {
            "/sys/class/power_supply/battery",
            "/sys/class/power_supply/bms",
            "/sys/class/power_supply/Battery",
            "/sys/class/power_supply/BAT0",
            "/sys/class/power_supply/battery/",
    };

    /** 充电器侧目录，能读到"供电方"而不是"电池"的信息 */
    private static final String[] CHARGER_CANDIDATES = {
            "/sys/class/power_supply/usb",
            "/sys/class/power_supply/ac",
            "/sys/class/power_supply/AC",
            "/sys/class/power_supply/wireless",
            "/sys/class/power_supply/dc",
    };

    private final String dir;
    private final Map<String, File> files = new HashMap<>(8);

    private SysfsSource(String dir) {
        this.dir = dir;
    }

    /** 探测可用目录，无可用节点返回 null */
    public static SysfsSource detect() {
        for (String d : CANDIDATES) {
            if (isReadable(new File(d, NODE_CURRENT))
                    || isReadable(new File(d, NODE_VOLTAGE))) {
                return new SysfsSource(d);
            }
        }
        return null;
    }

    private static boolean isReadable(File f) {
        return f.exists() && f.canRead();
    }

    public String path() {
        return dir;
    }

    /* ---------------- 逐节点读取 ---------------- */

    /** 读整数节点，失败或不可用返回 Long.MIN_VALUE */
    public long readLong(String name) {
        File f = file(name);
        if (!isReadable(f)) return Long.MIN_VALUE;
        // sysfs 每次读取都必须重新 open，句柄复用读不到新值
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[96];
            int len = in.read(buf);
            if (len <= 0) return Long.MIN_VALUE;
            return parseLeadingLong(new String(buf, 0, len, StandardCharsets.US_ASCII).trim());
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /** 读文本节点（status / technology / health 等），失败返回 null */
    public String readText(String name) {
        File f = file(name);
        if (!isReadable(f)) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[128];
            int len = in.read(buf);
            if (len <= 0) return null;
            String s = new String(buf, 0, len, StandardCharsets.US_ASCII).trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private File file(String name) {
        File f = files.get(name);
        if (f == null) {
            f = new File(dir, name);
            files.put(name, f);
        }
        return f;
    }

    /* ---------------- uevent 整块解析 ---------------- */

    /**
     * 解析 uevent（形如 "POWER_SUPPLY_STATUS=Charging"），key 统一去掉前缀。
     * 读不到返回空 Map，调用方按"没有该字段"处理即可。
     */
    public Map<String, String> readUevent() {
        Map<String, String> out = new HashMap<>(24);
        File f = new File(dir, "uevent");
        if (!isReadable(f)) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[2048];
            int len = in.read(buf);
            if (len <= 0) return out;
            String[] lines = new String(buf, 0, len, StandardCharsets.US_ASCII).split("\n");
            for (String line : lines) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                if (key.startsWith(UE)) key = key.substring(UE.length());
                out.put(key, line.substring(eq + 1).trim());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 从 uevent Map 里取整数，没有或非法返回 Long.MIN_VALUE */
    public static long ueLong(Map<String, String> ue, String key) {
        if (ue == null) return Long.MIN_VALUE;
        return parseLeadingLong(ue.get(key));
    }

    /** 从 uevent Map 里取文本，没有返回 null */
    public static String ueText(Map<String, String> ue, String key) {
        if (ue == null) return null;
        String v = ue.get(key);
        return v == null || v.isEmpty() ? null : v;
    }

    /* ---------------- 健康度直读 ---------------- */

    /**
     * 探测 sysfs 里是否有直接给出健康度的节点。
     *
     * 绝大多数机型没有这个节点（Android 就没定义标准的 SOH 接口），
     * 但有的话它就是权威值 —— 系统设置页显示的那个数字往往就是从这里来的，
     * 所以宁可多探几遍，也别自己算一个对不上系统的结果。
     *
     * @return 健康度百分比 30..100；没有返回 -1
     */
    public static int probeSohPct() {
        for (String d : CANDIDATES) {
            for (String n : SOH_NODES) {
                long v = readStatic(d, n);
                if (v >= 30 && v <= 100) return (int) v;
            }
            // uevent 里同名字段（不同内核把 SOH 塞在不同位置）
            Map<String, String> ue = readUeventOf(d);
            for (String n : new String[]{"SOH", "STATE_OF_HEALTH", "HEALTH", "CAPACITY_HEALTH"}) {
                long v = ueLong(ue, n);
                if (v >= 30 && v <= 100) return (int) v;
            }
        }
        return -1;
    }

    private static Map<String, String> readUeventOf(String dir) {
        Map<String, String> out = new HashMap<>(8);
        File f = new File(dir, "uevent");
        if (!isReadable(f)) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[2048];
            int len = in.read(buf);
            if (len <= 0) return out;
            for (String line : new String(buf, 0, len, StandardCharsets.US_ASCII).split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                if (key.startsWith(UE)) key = key.substring(UE.length());
                out.put(key, line.substring(eq + 1).trim());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /* ---------------- 充电器侧 ---------------- */

    /** 找到一个可读的充电器目录（usb/ac/wireless），没有返回 null */
    public static String chargerDir() {
        for (String d : CHARGER_CANDIDATES) {
            if (isReadable(new File(d, "online"))) return d;
        }
        return null;
    }

    /** 充电器声明的最大电压 mV；读不到 -1 */
    public static long chargerMaxVoltageMv() {
        String d = chargerDir();
        if (d == null) return -1;
        long uv = readStatic(d, "voltage_max");
        if (uv <= 0) return -1;
        return uv > 100_000L ? Math.round(uv / 1000.0) : uv;
    }

    /** 充电器声明的最大电流 mA；读不到 -1 */
    public static long chargerMaxCurrentMa() {
        String d = chargerDir();
        if (d == null) return -1;
        long ua = readStatic(d, "current_max");
        if (ua <= 0) ua = readStatic(d, "input_current_limit");
        if (ua <= 0) return -1;
        return ua > 20_000L ? Math.round(ua / 1000.0) : ua;
    }

    private static long readStatic(String dir, String name) {
        File f = new File(dir, name);
        if (!isReadable(f)) return -1;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64];
            int len = in.read(buf);
            if (len <= 0) return -1;
            return parseLeadingLong(new String(buf, 0, len, StandardCharsets.US_ASCII).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /* ---------------- 字符串解析 ---------------- */

    /** 兼容 "4200000"、"-450"、"4200000\n"、"450 mA"、" 350 " 等写法 */
    static long parseLeadingLong(String s) {
        if (s == null) return Long.MIN_VALUE;
        int n = s.length();
        int i = 0;
        while (i < n && isSpace(s.charAt(i))) i++;
        int valueStart = i;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        int startDigits = i;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == startDigits) return Long.MIN_VALUE;
        try {
            return Long.parseLong(s.substring(valueStart, i));
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
