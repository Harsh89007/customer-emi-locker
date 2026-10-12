package com.harshit.emilocker.customer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

/**
 * Soft keep-alive: restart LockListener every 2 minutes only.
 * Previous 20s + Preventive restart caused open/close loops.
 */
public class AppRunningService extends Service {
    private static final String CH = "emi_running";
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                Intent a = new Intent(AppRunningService.this, LockListenerService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(a);
                else startService(a);
            } catch (Exception ignored) {}
            h.postDelayed(this, 120_000L); // 2 min
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        startForeground(4411, notif());
        h.post(tick);
    }

    private Notification notif() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "Protection", NotificationManager.IMPORTANCE_MIN));
            }
        }
        return new NotificationCompat.Builder(this, CH)
                .setContentTitle("Kings EMI Locker")
                .setContentText("Protection running")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build();
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }
    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
