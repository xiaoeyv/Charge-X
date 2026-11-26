package com.xiaoeyv.chargex;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** CSV 内容生成（纯 Java，便于单测）。IO 部分在 CsvExporter。 */
public final class CsvFormat {

    private CsvFormat() {
    }

    public static String newFileName() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        return "charge_" + f.format(new Date()) + ".csv";
    }

    public static String header() {
        return "时间,时间戳ms,已记录秒,电压V,电流A,温度C,功率W,充电状态\n";
    }

    public static String build(SampleRing.Snapshot s) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder(s.size * 56 + 256);
        sb.append('\uFEFF'); // UTF-8 BOM，Excel 打开中文不乱码
        sb.append(header());
        long base = s.size > 0 ? s.ts[0] : 0L;
        for (int i = 0; i < s.size; i++) {
            sb.append(f.format(new Date(s.ts[i]))).append(',')
                    .append(s.ts[i]).append(',')
                    .append(String.format(Locale.US, "%.1f", (s.ts[i] - base) / 1000.0)).append(',')
                    .append(PowerMath.fmt(s.volt[i], 3)).append(',')
                    .append(PowerMath.fmt(s.amp[i], 3)).append(',')
                    .append(PowerMath.fmt(s.temp[i], 1)).append(',')
                    .append(PowerMath.fmt((double) s.volt[i] * s.amp[i], 2)).append(',')
                    .append(s.charging[i] == 1 ? "charging" : "discharging")
                    .append('\n');
        }
        return sb.toString();
    }
}
