package com.harshit.emilocker.customer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

public class LockActivity extends Activity {
    private DevicePolicyManager dpm;
    private ListenerRegistration listener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. फोन को फुल स्क्रीन करना और नेविगेशन/स्टेटस बार ब्लॉक करना
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN);
        
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                             WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                             WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_lock);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        try {
            if (dpm.isDeviceOwnerApp(getPackageName())) {
                startLockTask(); // 100% Kiosk Mode
            }
        } catch (Exception e) {}

        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        String sessionId = prefs.getString("session_id", null);

        // 2. ऑटोमैटिक अनलॉक लॉजिक (कोई बटन नहीं)
        if (sessionId != null) {
            listener = FirebaseFirestore.getInstance().collection("Customers").document(sessionId)
                .addSnapshotListener((snapshot, error) -> {
                    if (snapshot != null) {
                        // अगर डीलर ने रिलीज़/डिलीट कर दिया
                        if (!snapshot.exists()) {
                            unlockAndClose();
                            return;
                        }
                        // अगर डीलर ने अनलॉक कर दिया
                        Boolean isLocked = snapshot.getBoolean("is_locked");
                        if (isLocked != null && !isLocked) {
                            unlockAndClose();
                        }
                    }
                });
        }
    }

    private void unlockAndClose() {
        try {
            if (dpm.isDeviceOwnerApp(getPackageName())) {
                stopLockTask();
            }
        } catch (Exception e) {}
        if (listener != null) listener.remove();
        finish(); // लाल स्क्रीन अपने आप हट जाएगी
    }

    @Override
    public void onBackPressed() {
        // बैक बटन काम नहीं करेगा
    }
}

