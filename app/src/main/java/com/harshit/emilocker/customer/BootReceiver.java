package com.harshit.emilocker.customer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() != null) {
            SharedPreferences prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
            String sessionId = prefs.getString("session_id", null);
            boolean isOfflineLocked = prefs.getBoolean("is_offline_locked", false);
            
            if (sessionId != null) {
                try {
                    // 1. सर्विस को जगाने की कोशिश
                    Intent serviceIntent = new Intent(context, LockListenerService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Exception e) {
                    // 2. बैकअप प्लान: अगर सर्विस ब्लॉक हो गई, तो सीधा लाल स्क्रीन खोल दो!
                    if (isOfflineLocked) {
                        Intent lockIntent = new Intent(context, LockActivity.class);
                        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        context.startActivity(lockIntent);
                    }
                }
            }
        }
    }
}
