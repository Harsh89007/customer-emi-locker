package com.harshit.emilocker.customer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Boot + package replaced → start LockListener + Preventive (reference-style keep-alive).
 * Running-device overlay path unchanged (LockListener handles it).
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.d(TAG, "boot action=" + action);

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

        try {
            PreventiveService.start(context);
        } catch (Exception e) {
            Log.e(TAG, "Preventive: " + e.getMessage());
        }

        // If was locked offline, bring lock UI after boot
        try {
            Context deviceCtx = context;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                deviceCtx = context.createDeviceProtectedStorageContext();
            }
            SharedPreferences prefs = deviceCtx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
            boolean locked = prefs.getBoolean("is_offline_locked", false)
                    || prefs.getBoolean("is_locked", false);
            if (locked) {
                Intent lock = new Intent(context, LockActivity.class);
                lock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(lock);
            }
        } catch (Exception e) {
            Log.e(TAG, "lock after boot: " + e.getMessage());
        }
    }
}
