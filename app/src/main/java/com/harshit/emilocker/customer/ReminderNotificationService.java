package com.harshit.emilocker.customer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Reference-style payment reminder notification.
 */
public class ReminderNotificationService extends Service {
    private static final String CH = "emi_reminder";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String msg = intent != null && intent.getStringExtra("message") != null
                ? intent.getStringExtra("message")
                : "कृपया अपनी EMI का भुगतान करें / Please pay your EMI";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CH, "EMI Reminder", NotificationManager.IMPORTANCE_HIGH));
        }
        if (nm != null) {
            nm.notify(4420, new NotificationCompat.Builder(this, CH)
                    .setContentTitle("Kings EMI Locker")
                    .setContentText(msg)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build());
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
