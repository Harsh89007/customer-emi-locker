package com.harshit.emilocker.customer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Reference-style LocationService — keeps location FG channel alive;
 * actual location fetch remains in LockListenerService commands (get_location).
 */
public class LocationService extends Service {
    private static final String CH = "emi_location";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(4410, notif("Location protection active"));
        return START_STICKY;
    }

    private Notification notif(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "Location", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
        return new NotificationCompat.Builder(this, CH)
                .setContentTitle("Kings EMI Locker")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
