package com.harshit.emilocker.customer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // फोन रीस्टार्ट या चालू होने का एक्शन चेक करना
        if (intent != null && intent.getAction() != null) {
            SharedPreferences prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
            String sessionId = prefs.getString("session_id", null);
            boolean isOfflineLocked = prefs.getBoolean("is_offline_locked", false);
            
            // अगर ऐप सेटअप हो चुका है (Session ID है), तभी आगे बढ़ो
            if (sessionId != null) {
                try {
                    // 1. सर्विस को तुरंत जगाओ (यह उठते ही Firebase चेक करेगी और अगर डिलीट हुआ होगा तो अनइंस्टॉल कर लेगी)
                    Intent serviceIntent = new Intent(context, LockListenerService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Exception e) {
                    // 2. बैकअप प्लान: किसी वजह से अगर Android सर्विस को रोक दे, 
                    // और फोन ऑफलाइन लॉक था, तो सीधा लाल स्क्रीन (LockActivity) खोल दो।
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

