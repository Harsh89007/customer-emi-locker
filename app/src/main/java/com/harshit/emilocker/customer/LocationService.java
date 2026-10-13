package com.harshit.emilocker.customer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Disabled: location-type FGS caused permanent "Location" notification
 * that users cannot dismiss. Real GPS only runs inside LockListener
 * during get_location command (few seconds).
 */
public class LocationService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
