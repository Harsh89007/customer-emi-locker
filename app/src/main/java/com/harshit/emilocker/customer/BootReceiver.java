package com.harshit.emilocker.customer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || 
            "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
            
            // चेक करो कि क्या डिवाइस रजिस्टर हो चुका है?
            SharedPreferences prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
            String sessionId = prefs.getString("session_id", null);
            
            if (sessionId != null) {
                // फोन ऑन होते ही बिना ऐप खोले VIP सर्विस चालू कर दो
                Intent serviceIntent = new Intent(context, LockListenerService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}

