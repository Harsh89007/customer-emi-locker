package com.harshit.emilocker.customer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * GPS on/off broadcasts fire very often on OEM ROMs.
 * Restarting services here caused notification / permission spam.
 */
public class GpsLocationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("GpsLocRx", intent != null ? String.valueOf(intent.getAction()) : "null");
        // intentionally no-op
    }
}
