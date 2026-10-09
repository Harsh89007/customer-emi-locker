package com.harshit.emilocker.customer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Reference-style PhoneStatReceiver.
 * On SIM / phone state change → ensure LockListenerService running
 * (actual SIM-lock logic stays inside LockListenerService).
 */
public class PhoneStatReceiver extends BroadcastReceiver {

    private static final String TAG = "PhoneStatReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.d(TAG, "action=" + action);

        try {
            Intent svc = new Intent(context, LockListenerService.class);
            svc.putExtra("from_phone_stat", true);
            svc.putExtra("phone_action", action);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }

        try {
            PreventiveService.start(context);
        } catch (Exception ignored) {
        }
    }
}
