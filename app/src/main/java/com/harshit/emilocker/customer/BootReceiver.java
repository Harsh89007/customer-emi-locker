package com.harshit.emilocker.customer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Boot → start ONLY LockListenerService (Firestore brain).
 * Do NOT start AppRunningService / Preventive restart loops (caused crash loops).
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        Log.d(TAG, "action=" + intent.getAction());

        try {
            Intent svc = new Intent(context, LockListenerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "LockListener: " + e.getMessage());
        }

        // Bring lock UI if was locked
        try {
            Context deviceCtx = context;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    deviceCtx = context.createDeviceProtectedStorageContext();
                } catch (Exception ignored) {}
            }
            SharedPreferences prefs = deviceCtx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
            boolean locked = prefs.getBoolean("is_offline_locked", false);
            if (!locked) {
                prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
                locked = prefs.getBoolean("is_offline_locked", false);
            }
            if (locked) {
                Intent lock = new Intent(context, LockActivity.class);
                lock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                context.startActivity(lock);
            }
        } catch (Exception e) {
            Log.e(TAG, "lock after boot: " + e.getMessage());
        }
    }
}
