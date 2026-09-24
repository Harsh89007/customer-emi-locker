package com.harshit.emilocker.customer;

import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import com.google.firebase.firestore.FirebaseFirestore;

public class LockListenerService extends Service {
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private String mySessionId;
    
    private View overlayView;
    private WindowManager windowManager;

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
                if (snapshot != null) {
                    // 1. रिलीज़ बग फिक्स: अगर डीलर ने डेटा डिलीट कर दिया (!exists)
                    if (!snapshot.exists() || (snapshot.getBoolean("is_released") != null && snapshot.getBoolean("is_released"))) {
                        releaseOwnershipAndUninstall();
                        return;
                    }

                    // 2. लॉक / अनलॉक लॉजिक
                    Boolean isLocked = snapshot.getBoolean("is_locked");
                    if (isLocked != null && isLocked) {
                        lockDevice();
                    } else {
                        unlockDevice();
                    }
                }
            });
    }

    private void lockDevice() {
        if (dpm.isDeviceOwnerApp(getPackageName())) {
            // New Device: Activity Kiosk Mode
            Intent lockIntent = new Intent(this, LockActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(lockIntent);
        } else {
            // Running Device: 100% Blocking Overlay Screen
            showOverlayLock();
        }
    }

    private void unlockDevice() {
        hideOverlayLock();
        // (LockActivity अपने आप बंद हो जाएगी क्योंकि उसमें भी Firebase Listener है)
    }

    // Running Device के लिए अनब्रेकेबल लाल स्क्रीन (Overlay)
    private void showOverlayLock() {
        if (overlayView == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            overlayView = inflater.inflate(R.layout.activity_lock, null);

            int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                    WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);

            try {
                windowManager.addView(overlayView, params);
            } catch (Exception e) {}
        }
    }

    private void hideOverlayLock() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {}
            overlayView = null;
        }
    }

    private void releaseOwnershipAndUninstall() {
        Toast.makeText(this, "Device Released! Uninstalling...", Toast.LENGTH_LONG).show();
        hideOverlayLock();

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
