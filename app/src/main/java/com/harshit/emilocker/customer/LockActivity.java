package com.harshit.emilocker.customer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent; // <-- यह लाइन मिसिंग थी
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
        
        // नेविगेशन बार और स्टेटस बार को पूरी तरह छुपाना
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
                startLockTask(); // Kiosk Mode
            }
        } catch (Exception e) {}

        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        String sessionId = prefs.getString("session_id", null);

        if (sessionId != null) {
            listener = FirebaseFirestore.getInstance().collection("Customers").document(sessionId)
                .addSnapshotListener((snapshot, error) -> {
                    if (snapshot != null && snapshot.exists()) {
                        Boolean isLocked = snapshot.getBoolean("is_locked");
                        if (isLocked != null && !isLocked) {
                            unlockAndClose();
                        }
                    }
                });
        }
    }

    // अगर कस्टमर होम बटन दबाने की कोशिश करता है, तो यह तुरंत वापस खुलेगा
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (listener != null) {
            Intent i = new Intent(this, LockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
    }

    @Override
    public void onBackPressed() {
        // बैक बटन को काम करने से रोक दिया गया है
    }

    private void unlockAndClose() {
        try {
            if (dpm.isDeviceOwnerApp(getPackageName())) {
                stopLockTask();
            }
        } catch (Exception e) {}
        if (listener != null) listener.remove();
        finish();
    }
}
