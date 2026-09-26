package com.harshit.emilocker.customer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && intent.getAction() != null) {
            
            // नया लॉजिक: डायरेक्ट बूट (LOCKED_BOOT_COMPLETED) सपोर्ट के लिए Device Protected Context का इस्तेमाल
            Context deviceContext = context;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                deviceContext = context.createDeviceProtectedStorageContext();
            }

            // अब हम सुरक्षित स्टोरेज से डेटा पढ़ रहे हैं, जो बिना पिन डाले भी एक्सेस हो जाता है
            SharedPreferences prefs = deviceContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);
            String sessionId = prefs.getString("session_id", null);
            boolean isOfflineLocked = prefs.getBoolean("is_offline_locked", false);
            
            if (sessionId != null) {
                // 1. अगर फोन लॉक था, तो सर्विस का इंतज़ार मत करो, तुरंत Lock स्क्रीन खोल दो (0 Second Delay)
                if (isOfflineLocked) {
                    Intent lockIntent = new Intent(context, LockActivity.class);
                    lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    context.startActivity(lockIntent);
                }

                // 2. बैकग्राउंड सर्विस को स्टार्ट कर दो ताकि वो Firebase से कनेक्ट होकर लाइव स्टेटस चेक कर सके
                try {
                    Intent serviceIntent = new Intent(context, LockListenerService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Exception e) {
                    // Service start failed exception handling
                    e.printStackTrace();
                }
            }
        }
    }
}
