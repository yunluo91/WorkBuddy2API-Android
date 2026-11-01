package com.fujiang.wb2api;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** 前台服务：让 Python 网关在锁屏 / 切后台时也活着。 */
public class GatewayService extends Service {

    public static final String ACTION_START = "com.fujiang.wb2api.action.START";
    public static final String ACTION_STOP = "com.fujiang.wb2api.action.STOP";

    private static final String CHANNEL_ID = "gateway";
    private static final int NOTIF_ID = 0x2F01;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ticking = false;

    /**
     * WB-WAKELOCK 部分唤醒锁。
     *
     * 为什么需要：屏幕一关，CPU 可能进浅睡，socket 不再被及时服务，
     * 上游连接超时、健康检查失败，用户看到的就是"掉线"。
     * PARTIAL_WAKE_LOCK 让 CPU 保持醒着（屏幕仍然可以关）。
     *
     * 只在这个服务活着期间持有，onDestroy 里一定释放 ——
     * 忘了释放会明显费电，这点比"多活一会儿"更重要。
     * 做法跟 WorkBuddy-Hub 的 BridgeService 一样。
     */
    private PowerManager.WakeLock wakeLock = null;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, build("WorkBuddy2API · " + PyRunner.phase()));
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        acquireWakeLock();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wb2api:gateway");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable t) {
            android.util.Log.w("GatewayService", "wake lock 拿不到", t);
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        wakeLock = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, build("WorkBuddy2API · 启动中"));

        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            ticking = false;
            handler.removeCallbacks(tick);
            releaseWakeLock();
            PyRunner.shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        Prefs prefs = new Prefs(this);
        if (!PyRunner.isRunning() && !PyRunner.isStarting()) {
            PyRunner.start(this, prefs.port(), prefs.lan(), prefs.panelPwd());
        }
        if (!ticking) {
            ticking = true;
            handler.postDelayed(tick, 1500);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ticking = false;
        handler.removeCallbacks(tick);
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification build(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, GatewayService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(getString(R.string.notif_title))
                .setContentText(text + " · " + PyRunner.baseUrl())
                .setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        // 自定义小图标，别拿彩色启动图当通知图标（Android 7+ 会渲染成白块）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Icon icon = Icon.createWithResource(this, R.drawable.ic_notif);
            b.addAction(new Notification.Action.Builder(icon,
                    getString(R.string.notif_stop), stopPi).build());
        } else {
            b.addAction(R.drawable.ic_notif, getString(R.string.notif_stop), stopPi);
        }
        return b.build();
    }
}
