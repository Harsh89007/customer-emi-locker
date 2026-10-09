package com.harshit.emilocker.customer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Reference InstallationBroadcast — package install/replace */
public class InstallationBroadcast extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Log.d("InstallRx", String.valueOf(intent != null ? intent.getAction() : null));
        try {
            Intent s = new Intent(context, LockListenerService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(s); else context.startService(s);
            PreventiveService.start(context);
        } catch (Exception ignored) {}
    }
}
