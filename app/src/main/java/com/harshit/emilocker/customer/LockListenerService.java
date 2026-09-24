package com.harshit.emilocker.customer;

import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import com.google.firebase.firestore.FirebaseFirestore;

public class LockListenerService extends Service {
    private FirebaseFirestore db;
    private String myDocumentId = "SESSION_12345"; 
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    @Override
    public void onCreate() {
        super.onCreate();
        db = FirebaseFirestore.getInstance();
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyAdminReceiver.class);
        
        db.collection("Customers").document(myDocumentId)
            .addSnapshotListener((snapshot, e) -> {
                if (snapshot != null && snapshot.exists()) {
                    // 1. रिमोट अनइंस्टॉल / ओनरशिप रिलीज़ चेक
                    Boolean isReleased = snapshot.getBoolean("is_released");
                    if (isReleased != null && isReleased) {
                        releaseOwnershipAndUninstall();
                        return;
                    }

                    // 2. लॉक / अनलॉक चेक
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
            // ओनरशिप और एडमिन राइट्स छोड़ना
            if (dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.clearDeviceOwnerApp(getPackageName());
            }
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent);
            }
        } catch (Exception ignored) {}

        // अनइंस्टॉल का डायलॉग खोलना और सर्विस बंद करना
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

