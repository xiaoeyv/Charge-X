package com.xiaoeyv.chargex;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * 前台服务：1 秒一次采样，锁屏/切后台不断。通知栏同步显示实时读数。
 */
public class ChargeService extends Service {

    public static final String ACTION_STOP = "com.xiaoeyv.chargex.action.STOP";

    private static final String CHANNEL_ID = "charge_monitor";
    private static final int NOTIF_ID = 0x1001;
    private static final long SAMPLE_INTERVAL_MS = 1000L;
    private static final long NOTIF_INTERVAL_MS = 5000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Sampler sampler;
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver batteryReceiver;
    private long lastNotifAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Reading r = sampler.sample();
            Reading snap = new Reading();
            snap.copyFrom(r);
            SampleStore.LATEST = snap;
            SampleStore.RING.add(r.timeMs, r.volt, r.amp, r.tempC,
                    PowerMath.isCharging(r.status), r.level);
            SampleStore.SOURCE = r.sysfs ? "sysfs" : "BatteryManager";

            HealthTracker health = SampleStore.HEALTH;
            if (health != null) {
                health.onSample(r.timeMs, r.amp, r.level, PowerMath.isCharging(r.status),
                        r.health, r.tempC);
            }

            long now = System.currentTimeMillis();
            if (now - lastNotifAt >= NOTIF_INTERVAL_MS) {
                lastNotifAt = now;
                updateNotification(snap);
            }
            handler.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sampler = new Sampler(this);
        SampleStore.SOURCE_PATH = sampler.sysfsPath();
        createChannel();

        HealthTracker health = HealthTracker.get(this);
        DeviceInfo dev = SampleStore.DEVICE;
        health.setDeviceInfo(sampler.designMah(), sampler.fullMah(), sampler.cycleCount(),
                sampler.battery().health(), dev == null ? -1 : dev.sohPct);

        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                sampler.onBatteryIntent(intent);
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "charge:sampling");
            wakeLock.setReferenceCounted(false);
            try {
                wakeLock.acquire();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 首帧还没有读数，先给个"等待"文案，避免通知栏闪现 0.000V
        startForeground(NOTIF_ID, buildNotification(null));
        SampleStore.RUNNING = true;
        SampleStore.SOURCE = sampler.sysfsAvailable() ? "sysfs" : "BatteryManager";
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        SampleStore.RUNNING = false;
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {
            }
            batteryReceiver = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(Reading r) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_charge)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(summary(r))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openAppIntent());

        if (r != null) {
            double w = r.powerW();
            if (!Double.isNaN(w)) {
                // 用功率占 150W 的比例做一条进度条，纯视觉参考
                int p = (int) Math.round(Math.min(1.0, Math.abs(w) / 150.0) * 100);
                b.setProgress(100, p, false);
            }
        }
        return b.build();
    }

    private void updateNotification(Reading r) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(NOTIF_ID, buildNotification(r));
        } catch (Exception ignored) {
        }
    }

    private String summary(Reading r) {
        if (r == null) return getString(R.string.notif_waiting);
        StringBuilder sb = new StringBuilder();
        sb.append(PowerMath.fmt(r.volt, 3)).append("V  ")
                .append(PowerMath.fmt(r.amp, 3)).append("A  ")
                .append(PowerMath.fmt(r.powerW(), 2)).append("W");
        if (!Double.isNaN(r.tempC)) {
            sb.append("  ").append(PowerMath.fmt(r.tempC, 1)).append("°C");
        }
        return sb.toString();
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ChargeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ChargeService.class));
    }
}
