package com.harshit.emilocker.customer;

import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.widget.Toast;
import com.google.firebase.firestore.FirebaseFirestore;

public class LockListenerService extends Service {
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private String mySessionId;
    private boolean isCurrentlyLocked = false;
    private Handler handler = new Handler();

    // यह हर 1.5 सेकंड में चेक करेगा और लॉक स्क्रीन को हटने नहीं देगा
    private Runnable lockEnforcer = new Runnable() {
        @Override
        public void run() {
            if (isCurrentlyLocked) {
                Intent lockIntent = new Intent(LockListenerService.this, LockActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(lockIntent);
                handler.postDelayed(this, 1500); // 1.5 सेकंड का अग्रेसिव लूप
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        mySessionId = prefs.getString("session_id", null);
        
        if (mySessionId == null) {
            stopSelf();
            return;
        }

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyAdminReceiver.class);
        
        FirebaseFirestore.getInstance().collection("Customers").document(mySessionId)
            .addSnapshotListener((snapshot, e) -> {
                if (snapshot != null && snapshot.exists()) {
                    
                    Boolean isReleased = snapshot.getBoolean("is_released");
                    if (isReleased != null && isReleased) {
                        releaseOwnershipAndUninstall();
                        return;
                    }

                    Boolean isLocked = snapshot.getBoolean("is_locked");
                    if (isLocked != null && isLocked) {
                        if (!isCurrentlyLocked) {
                            isCurrentlyLocked = true;
                            handler.post(lockEnforcer); // लूप चालू करो
                        }
                    } else {
                        isCurrentlyLocked = false; // अनलॉक होने पर लूप बंद करो
                    }
                }
            });
    }

    // यह कमांड ऐप बंद होने के बाद भी सर्विस को बैकग्राउंड में दोबारा ज़िंदा कर देता है
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; 
    }

    private void releaseOwnershipAndUninstall() {
        isCurrentlyLocked = false;
        Toast.makeText(this, "Device Released! Uninstalling...", Toast.LENGTH_LONG).show();
        
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

