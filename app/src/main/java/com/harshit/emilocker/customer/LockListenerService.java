package com.harshit.emilocker.customer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
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

    private Runnable lockEnforcer = new Runnable() {
        @Override
        public void run() {
            if (isCurrentlyLocked) {
                Intent lockIntent = new Intent(LockListenerService.this, LockActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(lockIntent);
                handler.postDelayed(this, 1500);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 1. Android को सर्विस न मारने का आदेश (VIP Foreground Mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("EMI_SEC", "Security Sync", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);

            Notification notification = new Notification.Builder(this, "EMI_SEC")
                .setContentTitle("Security Active")
                .setContentText("Device is protected")
                .setSmallIcon(android.R.drawable.ic_secure) // तुम्हारे ऐप का डिफॉल्ट आइकॉन
                .build();
            startForeground(1001, notification);
        }

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
                            handler.post(lockEnforcer);
                        }
                    } else {
                        isCurrentlyLocked = false;
                    }
                }
            });
    }

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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
