package com.xiaoeyv.chargex;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "app";
    private static final String KEY_RECORDING = "recording";
    private static final int REQ_NOTIF = 101;
    private static final int REQ_EXPORT = 102;

    /* 图形色与文字色分开：浅底上绿色文字必须深一档才达 AA */
    private static final int C_ENERGY = 0xFF0E9F6E;
    private static final int C_ENERGY_TEXT = 0xFF07734F;
    private static final int C_INFO = 0xFF2F6FED;
    private static final int C_INFO_TEXT = 0xFF2F6FED;
    private static final int C_AMP = 0xFF0891B2;
    private static final int C_AMP_TEXT = 0xFF0E7490;
    private static final int C_TEMP = 0xFFD98514;
    private static final int C_TEMP_TEXT = 0xFFA56410;
    private static final int C_MUTED = 0xFF8B95A1;
    private static final int C_NEUTRAL = 0xFF9AA6B4;
    private static final int C_LOSS_SOFT = 0xFFF6D2D2;

    private PowerRingView ring;
    private ChartView chart;
    private TextView tvDot, tvRecord, tvState, tvEta, tvPower;
    private TextView tvTemp, tvTempState, tvVolt, tvVoltState, tvAmp, tvAmpState;
    private TextView tvChartTitle, tvChartHint;
    private TextView chipPower, chipVolt, chipAmp, chipTemp;
    private TextView btnToggle;

    private TextView tvHScore, tvHUnit, tvHGrade, tvHDesc;
    private TextView tvHUsed, tvHLost, tvHProgress, tvHNote;
    private MeterView meterCapacity, meterProgress;
    private HealthTracker health;

    private TextView tvITech, tvIPower, tvIStatus, tvICycles, tvIDesign, tvIFull, tvIMeasured;
    private TextView tvICharger, tvISamples, tvISource, tvIPath;
    private boolean pathExpanded;

    private SharedPreferences prefs;
    private String pendingCsv;
    private int chipMetric = ChartView.METRIC_POWER;

    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ring = findViewById(R.id.ring);
        chart = findViewById(R.id.chart);
        tvDot = findViewById(R.id.tv_dot);
        tvRecord = findViewById(R.id.tv_record);
        tvState = findViewById(R.id.tv_state);
        tvEta = findViewById(R.id.tv_eta);
        tvPower = findViewById(R.id.tv_power);
        tvTemp = findViewById(R.id.tv_temp);
        tvTempState = findViewById(R.id.tv_temp_state);
        tvVolt = findViewById(R.id.tv_volt);
        tvVoltState = findViewById(R.id.tv_volt_state);
        tvAmp = findViewById(R.id.tv_amp);
        tvAmpState = findViewById(R.id.tv_amp_state);
        tvChartTitle = findViewById(R.id.tv_chart_title);
        tvChartHint = findViewById(R.id.tv_chart_hint);
        chipPower = findViewById(R.id.chip_power);
        chipVolt = findViewById(R.id.chip_volt);
        chipAmp = findViewById(R.id.chip_amp);
        chipTemp = findViewById(R.id.chip_temp);
        btnToggle = findViewById(R.id.btn_toggle);

        tvHScore = findViewById(R.id.tv_h_score);
        tvHUnit = findViewById(R.id.tv_h_unit);
        tvHGrade = findViewById(R.id.tv_h_grade);
        tvHDesc = findViewById(R.id.tv_h_desc);
        tvHUsed = findViewById(R.id.tv_h_used);
        tvHLost = findViewById(R.id.tv_h_lost);
        tvHProgress = findViewById(R.id.tv_h_progress);
        tvHNote = findViewById(R.id.tv_h_note);
        meterCapacity = findViewById(R.id.meter_capacity);
        meterProgress = findViewById(R.id.meter_progress);

        tvITech = findViewById(R.id.tv_i_tech);
        tvIPower = findViewById(R.id.tv_i_power);
        tvIStatus = findViewById(R.id.tv_i_status);
        tvICycles = findViewById(R.id.tv_i_cycles);
        tvIDesign = findViewById(R.id.tv_i_design);
        tvIFull = findViewById(R.id.tv_i_full);
        tvIMeasured = findViewById(R.id.tv_i_measured);
        tvICharger = findViewById(R.id.tv_i_charger);
        tvISamples = findViewById(R.id.tv_i_samples);
        tvISource = findViewById(R.id.tv_i_source);
        tvIPath = findViewById(R.id.tv_i_path);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        health = HealthTracker.get(this);
        meterCapacity.setMode(MeterView.MODE_CAPACITY);
        meterProgress.setMode(MeterView.MODE_PROGRESS);
        // 记录功能被关掉时服务不会启动，这里补一次静态信息，健康卡才不会一直「待测」
        Sampler.feedDeviceInfo(this, health);

        setupChips();
        setupSourceRow();
        setupButtons();

        requestNotifPermission();

        if (prefs.getBoolean(KEY_RECORDING, true)) {
            ChargeService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }

    /* ---------------- 交互 ---------------- */

    private void setupButtons() {
        btnToggle.setOnClickListener(v -> toggleRecording());
        findViewById(R.id.btn_export).setOnClickListener(v -> doExport());
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            SampleStore.RING.clear();
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupChips() {
        chipPower.setOnClickListener(v -> selectMetric(ChartView.METRIC_POWER));
        chipVolt.setOnClickListener(v -> selectMetric(ChartView.METRIC_VOLT));
        chipAmp.setOnClickListener(v -> selectMetric(ChartView.METRIC_AMP));
        chipTemp.setOnClickListener(v -> selectMetric(ChartView.METRIC_TEMP));
        applyChips();
    }

    private void selectMetric(int metric) {
        if (chipMetric == metric) return;
        chipMetric = metric;
        applyChips();
    }

    private void applyChips() {
        paintChip(chipPower, C_ENERGY, C_ENERGY_TEXT, chipMetric == ChartView.METRIC_POWER);
        paintChip(chipVolt, C_INFO, C_INFO_TEXT, chipMetric == ChartView.METRIC_VOLT);
        paintChip(chipAmp, C_AMP, C_AMP_TEXT, chipMetric == ChartView.METRIC_AMP);
        paintChip(chipTemp, C_TEMP, C_TEMP_TEXT, chipMetric == ChartView.METRIC_TEMP);
        chart.setMetric(chipMetric);

        switch (chipMetric) {
            case ChartView.METRIC_VOLT:
                tvChartTitle.setText("电压变化");
                break;
            case ChartView.METRIC_AMP:
                tvChartTitle.setText("电流变化");
                break;
            case ChartView.METRIC_TEMP:
                tvChartTitle.setText("温度变化");
                break;
            default:
                tvChartTitle.setText("充电速度");
                break;
        }
        // 单位放在标题区，纵轴刻度就只放数字，标签才能窄
        tvChartHint.setText("近 5 分钟 · " + ChartView.unitOf(chipMetric));
    }

    private void paintChip(TextView v, int accent, int textColor, boolean on) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (on) {
            bg.setColor((accent & 0x00FFFFFF) | 0x1F000000); // 12% 同色底
            bg.setStroke(Math.max(1, dp(1)), accent);
            v.setTextColor(textColor);
        } else {
            bg.setColor(0xFFFFFFFF);
            bg.setStroke(Math.max(1, dp(1)), 0xFFE4E8EE);
            v.setTextColor(0xFF5A6673);
        }
        v.setBackground(bg);
    }

    /** 数据源行：点一下才露出内核路径，默认不拿路径吓人 */
    private void setupSourceRow() {
        findViewById(R.id.row_source).setOnClickListener(v -> {
            if (pathExpanded || tvIPath.getText().length() == 0) return;
            pathExpanded = true;
            tvIPath.setVisibility(View.VISIBLE);
        });
    }

    /* ---------------- 电池健康 ---------------- */

    private void renderHealth() {
        Reading r = SampleStore.LATEST;
        HealthEstimator.Result h = health.result();
        int total = h.totalMah();
        int actual = h.actualMah();
        int loss = h.lossMah();

        // ① 大数字：有容量口径就一定是百分比，算不出来才退回"分"
        if (h.known()) {
            tvHScore.setText(String.valueOf(h.score));
            tvHUnit.setText(h.percentBased() ? "%" : "分");
            int color = gradeColor(h.score);
            paintGrade(HealthEstimator.grade(h.score), color, color);
        } else {
            tvHScore.setText("--");
            tvHUnit.setText("");
            paintGrade("待测", C_MUTED, C_MUTED);
        }

        // ② 副标题：说清这个数字是怎么来的
        if (h.capacityBased) {
            String base = actual > 0 && total > 0
                    ? actual + " / " + total + " mAh"
                    : "已测出实际容量";
            setIfChanged(tvHDesc, base + " · " + sourceLabel(h));
        } else if (h.known()) {
            setIfChanged(tvHDesc, "初步估算 · 待实测校准");
        } else {
            setIfChanged(tvHDesc, "正在读取电池信息");
        }

        // ③ 容量条：整条 = 总容量，右侧浅色段 = 老化损失
        //    没测出数值时默认无损耗（实际 = 总容量），条画满
        float frac = h.actualFraction();
        boolean hasLoss = total > 0 && actual > 0 && loss > 0;
        meterCapacity.setValues(frac, hasLoss ? gradeColor(h.score) : C_ENERGY,
                hasLoss ? C_LOSS_SOFT : 0);

        if (total > 0) {
            int shown = actual > 0 ? actual : total;
            setIfChanged(tvHUsed, "● 现在可用 " + shown + " mAh");
            setIfChanged(tvHLost, loss > 0 ? "● 老化损失 " + loss + " mAh" : "● 暂无可见损耗");
            tvHLost.setTextColor(loss > 0 ? 0xFFB33A3A : C_MUTED);
        } else {
            setIfChanged(tvHUsed, "● 现在可用 -- mAh");
            setIfChanged(tvHLost, "● 老化损失 0 mAh");
            tvHLost.setTextColor(C_MUTED);
        }

        renderProgress(r, h);
        renderHealthNote(r, h);
    }

    private void renderProgress(Reading r, HealthEstimator.Result h) {
        boolean charging = PowerMath.isCharging(r.status) && !Double.isNaN(r.amp) && r.amp > 0.05;
        int delta = health.progressLevelDelta(r.level);

        // 已经拿到容量口径：进度满、转绿，避免用户以为还在跑
        if (h.capacityBased) {
            meterProgress.setValues(1f, C_ENERGY, 0);
            tvHProgress.setText(HealthEstimator.SRC_SOH.equals(h.source) ? "系统读数" : "已完成");
            return;
        }

        float f = charging ? health.progressFraction(r.level) : 0f;
        meterProgress.setValues(f, C_INFO, 0);

        if (!charging) {
            tvHProgress.setText("等待充电");
        } else if (delta > 0) {
            tvHProgress.setText("已充 " + delta + "% / " + health.progressTargetPct() + "%");
        } else {
            tvHProgress.setText("刚开始记录");
        }
    }

    /** 口径文案：让用户知道这个百分比是从哪来的，而不是一个来路不明的数字 */
    private String sourceLabel(HealthEstimator.Result h) {
        if (HealthEstimator.SRC_SOH.equals(h.source)) return "系统读数";
        if (HealthEstimator.SRC_FULL.equals(h.source)) return "系统容量";
        if (HealthEstimator.SRC_MEASURED.equals(h.source)) return "实测容量";
        return "估算";
    }

    private void renderHealthNote(Reading r, HealthEstimator.Result h) {
        boolean charging = PowerMath.isCharging(r.status) && !Double.isNaN(r.amp) && r.amp > 0.05;
        String note;
        if (h.capacityBased && HealthEstimator.SRC_SOH.equals(h.source)) {
            note = "这个百分比直接读自系统电池接口，和系统设置里显示的电池健康度同源，没有做任何二次换算。";
        } else if (h.capacityBased && HealthEstimator.SRC_FULL.equals(h.source)) {
            note = "按「满充容量 ÷ 标称容量」计算，与系统的容量口径一致。插上充电器充一会儿，还能做一次独立实测来交叉核对。";
        } else if (h.capacityBased) {
            note = "这个数字来自实测：记录真正流入电池的电荷量。它通常比系统标称低 3%~8%，属于偏保守的下限值，不是电池虚标。";
        } else if (charging) {
            note = "正在累计本次充电的电量，电量涨够 " + health.progressTargetPct()
                    + " 个百分点就出结果，期间可以正常用手机、也可以切后台。";
        } else if (h.known()) {
            note = "现在只有循环次数这类间接指标。插上充电器并保持本页面开着，充一会儿就能测出真实容量。";
        } else {
            note = "插上充电器并保持本页面开着，电量涨够 " + health.progressTargetPct()
                    + " 个百分点就会自动算出容量，不用按任何按钮。";
        }
        tvHNote.setText(note);
    }

    private String gradeKey;

    private void paintGrade(String text, int textColor, int accent) {
        String key = text + '|' + textColor + '|' + accent;
        if (key.equals(gradeKey)) return; // 每 500ms 渲染一次，没变就别重建背景
        gradeKey = key;
        tvHGrade.setText(text);
        tvHGrade.setTextColor(textColor);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(9));
        bg.setColor((accent & 0x00FFFFFF) | 0x14000000);
        tvHGrade.setBackground(bg);
    }

    private int gradeColor(int score) {
        if (score >= 80) return C_ENERGY_TEXT;
        if (score >= 60) return C_TEMP_TEXT;
        return 0xFFD93B3B;
    }

    /* ---------------- 设备信息 ---------------- */

    /** 每 500ms 刷一次界面，内容没变就不 setText —— 一次多余的 setText 就是一次 measure/layout */
    private static void setIfChanged(TextView v, CharSequence text) {
        if (TextUtils.equals((CharSequence) v.getTag(), text)) return;
        v.setTag(text);
        v.setText(text);
    }

    private void renderDeviceInfo(Reading r) {
        DeviceInfo d = SampleStore.DEVICE;
        setIfChanged(tvITech, d == null ? "未提供" : d.technologyLabel());
        setIfChanged(tvIPower, plugLabel(r.plugged));

        String healthText = d == null ? null : DeviceInfo.healthLabel(d.healthText);
        if (healthText == null && r.health > 0) healthText = HealthEstimator.statusLabel(r.health);
        setIfChanged(tvIStatus, healthText == null ? "未提供" : healthText);

        setIfChanged(tvICycles, d != null && d.cycles >= 0 ? d.cycles + " 次" : "未提供");
        setIfChanged(tvIDesign, d != null && d.designMah > 0 ? d.designMah + " mAh" : "未提供");
        setIfChanged(tvIFull, d != null && d.fullMah > 0 ? d.fullMah + " mAh" : "未提供");
        // 实测值即使被判为不可信也照原样显示，附一句说明 —— 藏起来用户会更困惑
        HealthEstimator.Result hr = health.result();
        int measured = hr.measuredMah;
        String measuredText;
        if (measured <= 0) {
            measuredText = "未测出";
        } else if (HealthEstimator.isPlausibleMeasured(hr.designMah, measured)) {
            measuredText = measured + " mAh";
        } else {
            measuredText = measured + " mAh（偏低已忽略）";
        }
        setIfChanged(tvIMeasured, measuredText);
        setIfChanged(tvICharger, chargerText(d));

        SampleRing sampleRing = SampleStore.RING;
        int n = sampleRing.size();
        setIfChanged(tvISamples, n > 1
                ? n + " 点 · " + spanText((sampleRing.lastTs() - sampleRing.firstTs()) / 1000)
                : n + " 点");

        boolean sysfs = "sysfs".equals(SampleStore.SOURCE);
        setIfChanged(tvISource, sysfs ? "系统电池接口" : "系统电池服务");
        String path = SampleStore.SOURCE_PATH;
        if (path == null || "-".equals(path)) {
            tvIPath.setVisibility(View.GONE);
            pathExpanded = false;
        } else {
            tvIPath.setText(path);
            if (pathExpanded) tvIPath.setVisibility(View.VISIBLE);
        }
    }

    private String chargerText(DeviceInfo d) {
        if (d == null) return "未提供";
        StringBuilder sb = new StringBuilder();
        if (d.chargerMaxMv > 0) {
            sb.append(PowerMath.fmt(d.chargerMaxMv / 1000.0, 1)).append(" V");
        }
        if (d.chargerMaxMa > 0) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(d.chargerMaxMa).append(" mA");
        }
        if (sb.length() == 0 && !PowerMath.isInvalid(d.maxChargingUa)) {
            sb.append("最大 ").append(PowerMath.fmt(d.maxChargingUa / 1_000_000.0, 1)).append(" A");
        }
        return sb.length() == 0 ? "未提供" : sb.toString();
    }

    /** EXTRA_PLUGGED 是位掩码，可能同时有多个 */
    private static String plugLabel(int plugged) {
        if (plugged == 0) return "未连接";
        StringBuilder sb = new StringBuilder();
        if ((plugged & 1) != 0) sb.append("AC 充电器");
        if ((plugged & 2) != 0) appendPlus(sb).append("USB");
        if ((plugged & 4) != 0) appendPlus(sb).append("无线充电");
        if ((plugged & 8) != 0) appendPlus(sb).append("底座");
        return sb.length() == 0 ? "未知" : sb.toString();
    }

    private static StringBuilder appendPlus(StringBuilder sb) {
        if (sb.length() > 0) sb.append(" + ");
        return sb;
    }

    /* ---------------- 渲染 ---------------- */

    private void render() {
        Reading r = SampleStore.LATEST;
        boolean recording = prefs.getBoolean(KEY_RECORDING, true);
        btnToggle.setText(recording ? "停止记录" : "开始记录");

        tvDot.setTextColor(recording ? C_ENERGY : C_NEUTRAL);
        tvRecord.setText(recording
                ? "记录中 " + (r.timeMs > 0 ? clockFmt.format(new Date(r.timeMs)) : "")
                : "已停止");

        ring.setProgress(r.level < 0 ? -1f : r.level / 100f);

        int kind = stateKind(r);
        tvState.setText(stateLabel(kind));
        tvState.setTextColor(textColorFor(kind));
        ring.setAccentColor(accentFor(kind));

        double w = r.powerW();
        // 直接 U × I：充电为正（输入），放电为负（输出）
        tvPower.setText(Double.isNaN(w) ? "--" : PowerMath.fmt(w, 2));

        renderEta(r, recording);
        renderTiles(r, w);
        renderHealth();
        renderDeviceInfo(r);
        chart.invalidate();
    }

    private void renderEta(Reading r, boolean recording) {
        if (!recording || r.level < 0 || kindIsNotCharging(stateKind(r))) {
            tvEta.setText("");
            return;
        }
        long now = r.timeMs > 0 ? r.timeMs : System.currentTimeMillis();
        long[] first = SampleStore.RING.oldestLevelSince(now - 5 * 60_000L);
        if (first == null) {
            tvEta.setText("");
            return;
        }
        long eta = EtaEstimator.etaSeconds((int) first[0], first[1], r.level, now);
        String human = EtaEstimator.humanize(eta);
        if (human == null) {
            tvEta.setText("");
        } else if ("即将充满".equals(human)) {
            tvEta.setText(human);
        } else {
            tvEta.setText("预计 " + human + "后充满");
        }
    }

    private void renderTiles(Reading r, double power) {
        if (Double.isNaN(r.tempC)) {
            tvTemp.setText("--");
            tvTempState.setText("");
        } else {
            tvTemp.setText(PowerMath.fmt(r.tempC, 0));
            if (r.tempC < 38) {
                tvTempState.setText("正常");
                tvTempState.setTextColor(C_ENERGY_TEXT);
            } else if (r.tempC <= 42) {
                tvTempState.setText("偏热");
                tvTempState.setTextColor(C_TEMP_TEXT);
            } else {
                tvTempState.setText("过热");
                tvTempState.setTextColor(0xFFD93B3B);
            }
        }

        if (Double.isNaN(r.volt)) {
            tvVolt.setText("--");
            tvVoltState.setText("");
        } else {
            tvVolt.setText(PowerMath.fmt(r.volt, 2));
            if (r.volt >= 3.6 && r.volt <= 4.45) {
                tvVoltState.setText("正常");
                tvVoltState.setTextColor(C_ENERGY_TEXT);
            } else {
                tvVoltState.setText("异常");
                tvVoltState.setTextColor(C_TEMP_TEXT);
            }
        }

        if (Double.isNaN(r.amp)) {
            tvAmp.setText("--");
            tvAmpState.setText("无数据");
            tvAmpState.setTextColor(C_MUTED);
            return;
        }
        tvAmp.setText(PowerMath.fmt(r.amp, 2));
        if (r.amp < 0) {
            tvAmpState.setText("放电");
            tvAmpState.setTextColor(C_TEMP_TEXT);
        } else if (power >= 18) {
            tvAmpState.setText("超快充");
            tvAmpState.setTextColor(C_ENERGY_TEXT);
        } else if (power >= 7.5) {
            tvAmpState.setText("快充中");
            tvAmpState.setTextColor(C_ENERGY_TEXT);
        } else if (power >= 2) {
            tvAmpState.setText("充电中");
            tvAmpState.setTextColor(C_ENERGY_TEXT);
        } else {
            tvAmpState.setText("涓流");
            tvAmpState.setTextColor(C_MUTED);
        }
    }

    /* ---------------- 语义映射 ---------------- */

    private static final int KIND_SUPER = 0;
    private static final int KIND_FAST = 1;
    private static final int KIND_NORMAL = 2;
    private static final int KIND_FULL = 3;
    private static final int KIND_DISCHARGE = 4;
    private static final int KIND_NO_DATA = 5;
    private static final int KIND_TRICKLE = 6;
    private static final int KIND_IDLE = 7;

    private static int stateKind(Reading r) {
        double w = r.powerW();
        if (r.status == PowerMath.STATUS_FULL) return KIND_FULL;
        if (!Double.isNaN(r.amp) && r.amp < 0) return KIND_DISCHARGE;
        if (r.status == PowerMath.STATUS_DISCHARGING) return KIND_DISCHARGE;
        if (Double.isNaN(w)) return KIND_NO_DATA;
        if (w >= 18) return KIND_SUPER;
        if (w >= 7.5) return KIND_FAST;
        if (w >= 2) return KIND_NORMAL;
        if (r.plugged != 0) return KIND_TRICKLE;
        return KIND_IDLE;
    }

    private static boolean kindIsNotCharging(int kind) {
        return kind == KIND_DISCHARGE || kind == KIND_NO_DATA || kind == KIND_IDLE;
    }

    private static String stateLabel(int kind) {
        switch (kind) {
            case KIND_SUPER:
                return "超级快充";
            case KIND_FAST:
                return "正在快充";
            case KIND_NORMAL:
                return "正在充电";
            case KIND_FULL:
                return "已充满";
            case KIND_DISCHARGE:
                return "正在放电";
            case KIND_NO_DATA:
                return "无法读取电流";
            case KIND_TRICKLE:
                return "涓流充电";
            default:
                return "未在充电";
        }
    }

    private static int textColorFor(int kind) {
        switch (kind) {
            case KIND_SUPER:
            case KIND_FAST:
            case KIND_NORMAL:
            case KIND_TRICKLE:
                return C_ENERGY_TEXT;
            case KIND_FULL:
                return C_INFO_TEXT;
            case KIND_DISCHARGE:
                return C_TEMP_TEXT;
            default:
                return C_MUTED;
        }
    }

    private static int accentFor(int kind) {
        switch (kind) {
            case KIND_SUPER:
            case KIND_FAST:
            case KIND_NORMAL:
            case KIND_TRICKLE:
                return C_ENERGY;
            case KIND_FULL:
                return C_INFO;
            case KIND_DISCHARGE:
                return C_TEMP;
            default:
                return C_NEUTRAL;
        }
    }

    private static String spanText(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        long min = seconds / 60;
        long sec = seconds % 60;
        if (min < 60) return sec == 0 ? min + " 分钟" : min + " 分 " + sec + " 秒";
        long hour = min / 60;
        long restMin = min % 60;
        return restMin == 0 ? hour + " 小时" : hour + " 小时 " + restMin + " 分";
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleRecording() {
        boolean recording = prefs.getBoolean(KEY_RECORDING, true);
        if (recording) {
            prefs.edit().putBoolean(KEY_RECORDING, false).apply();
            ChargeService.stop(this);
            Toast.makeText(this, "已停止记录", Toast.LENGTH_SHORT).show();
        } else {
            prefs.edit().putBoolean(KEY_RECORDING, true).apply();
            ChargeService.start(this);
        }
        render();
    }

    /* ---------------- 导出 ---------------- */

    private void doExport() {
        SampleRing sampleRing = SampleStore.RING;
        if (sampleRing.isEmpty()) {
            Toast.makeText(this, "还没有采样数据", Toast.LENGTH_SHORT).show();
            return;
        }
        String csv = CsvFormat.build(sampleRing.snapshot());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                String path = CsvExporter.exportToDownloads(this, csv);
                Toast.makeText(this, "已导出到 " + path, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }

        pendingCsv = csv;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(CsvExporter.MIME);
        i.putExtra(Intent.EXTRA_TITLE, CsvExporter.newFileName());
        startActivityForResult(i, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && data != null && pendingCsv != null) {
            Uri uri = data.getData();
            try {
                if (uri != null) {
                    CsvExporter.write(this, uri, pendingCsv);
                    Toast.makeText(this, "已导出", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            pendingCsv = null;
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }
}
