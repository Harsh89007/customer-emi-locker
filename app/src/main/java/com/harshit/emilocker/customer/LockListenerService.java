package com.harshit.emilocker.customer;

import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;
import com.google.firebase.firestore.FirebaseFirestore;

public class LockListenerService extends Service {
    private FirebaseFirestore db;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private String mySessionId;

    @Override
    public void onCreate() {
        super.onCreate();
        
        // फोन की मेमोरी से असली सेशन आईडी निकाल रहे हैं
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        mySessionId = prefs.getString("session_id", null);
        
        // अगर आईडी नहीं मिली तो सर्विस बंद कर दो
        if (mySessionId == null) {
            stopSelf();
            return;
        }

        db = FirebaseFirestore.getInstance();
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyAdminReceiver.class);
        
        db.collection("Customers").document(mySessionId)
            .addSnapshotListener((snapshot, e) -> {
                if (snapshot != null && snapshot.exists()) {
                    Boolean isReleased = snapshot.getBoolean("is_released");
                    if (isReleased != null && isReleased) {
                        releaseOwnershipAndUninstall();
                        return;
                    }

                    Boolean isLocked = snapshot.getBoolean("is_locked");
                    if (isLocked != null && isLocked) {
                        Intent lockIntent = new Intent(this, LockActivity.class);
                        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(lockIntent);
                    }
                }
            });
    }

    private void releaseOwnershipAndUninstall() {
        try {
            if (dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.clearDeviceOwnerApp(getPackageName());
            }
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent);
            }
        } catch (Exception ignored) {}

        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

