package com.harshit.emilocker.customer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

public class LockActivity extends Activity {
    private static final long WIFI_SESSION_MS = 60_000L; // 60 sec password डालने का समय

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private ListenerRegistration listener;
    private ImageView qrCodeImage;
    private boolean allowExit = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent() != null && getIntent().getBooleanExtra("force_unlock", false)) {
            allowExit = true;
            clearWifiSession();
            try {
                DevicePolicyManager dpmLocal =
                        (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (dpmLocal != null && dpmLocal.isDeviceOwnerApp(getPackageName())) {
                    stopLockTask();
                }
            } catch (Exception ignored) {
            }
            finish();
            return;
        }

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.activity_lock);
        qrCodeImage = findViewById(R.id.qr_code_image);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyAdminReceiver.class);

        Context deviceContext = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            deviceContext = createDeviceProtectedStorageContext();
        }
        prefs = deviceContext.getSharedPreferences("AppPrefs", MODE_PRIVATE);

        markLockVisible(true);

        if (!isWifiSessionActive()) {
            enterKioskStrict();
        } else {
            enterKioskWithSettings();
        }

        Button btnConnectWifi = findViewById(R.id.btn_connect_wifi);
        if (btnConnectWifi != null) {
            btnConnectWifi.setOnClickListener(v -> openWifiSafely());
        }

        String sessionId = prefs.getString("session_id", null);
        if (sessionId != null) {
            listener = FirebaseFirestore.getInstance()
                    .collection("Customers").document(sessionId)
                    .addSnapshotListener((snapshot, error) -> {
                        if (snapshot != null && snapshot.exists()) {
                            Boolean isLocked = snapshot.getBoolean("is_locked");
                            if (isLocked != null && !isLocked) {
                                unlockAndClose();
                            } else {
                                String dealerUid = snapshot.getString("dealer_uid");
                                if (dealerUid != null && !dealerUid.isEmpty()) {
                                    fetchDealerQRCode(dealerUid);
                                }
                            }
                        }
                    });
        }
    }

    private void markLockVisible(boolean visible) {
        if (prefs != null) {
            prefs.edit().putBoolean("lock_activity_visible", visible).apply();
        }
    }

    private boolean isWifiSessionActive() {
        if (prefs == null) return false;
        long until = prefs.getLong("wifi_session_until", 0L);
        return System.currentTimeMillis() < until;
    }

    private void startWifiSession() {
        if (prefs != null) {
            prefs.edit()
                    .putLong("wifi_session_until", System.currentTimeMillis() + WIFI_SESSION_MS)
                    .apply();
        }
    }

    private void clearWifiSession() {
        if (prefs != null) {
            prefs.edit().putLong("wifi_session_until", 0L).apply();
        }
    }

    /** सिर्फ अपना app — Home / Settings से भागना बंद */
    private void enterKioskStrict() {
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
                try {
                    dpm.addUserRestriction(adminComponent,
                            android.os.UserManager.DISALLOW_FACTORY_RESET);
                } catch (Exception ignored) {
                }
                try {
                    dpm.addUserRestriction(adminComponent,
                            android.os.UserManager.DISALLOW_SAFE_BOOT);
                } catch (Exception ignored) {
                }
                startLockTask();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Wi-Fi session: Settings package allow (सिर्फ 60 sec) */
    private void enterKioskWithSettings() {
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(adminComponent, new String[]{
                        getPackageName(),
                        "com.android.settings",
                        "com.samsung.android.settings",
                        "com.android.systemui"
                });
                startLockTask();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Wi-Fi: 60 sec session।
     * Customer network चुन + password डाल सकता है।
     * Session खत्म / Done / वापस आने पर strict kiosk।
     * Permanent unlock नहीं।
     */
    private void openWifiSafely() {
        startWifiSession();
        enterKioskWithSettings();

        boolean opened = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                opened = true;
            } catch (Exception ignored) {
            }
            if (!opened) {
                try {
                    startActivity(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
                    opened = true;
                } catch (Exception ignored) {
                }
            }
        }
        if (!opened) {
            try {
                Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                opened = true;
            } catch (Exception ignored) {
            }
        }

        if (!opened) {
            clearWifiSession();
            enterKioskStrict();
            Toast.makeText(this, "Wi-Fi नहीं खुला। Data SIM लगाएँ।", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Wi-Fi जोड़ो (60 sec)। Done दबाओ — lock वापस आएगा।",
                Toast.LENGTH_LONG).show();

        // 60 sec बाद session बंद + strict lock
        handler.postDelayed(() -> {
            if (!allowExit && isStillLocked()) {
                clearWifiSession();
                enterKioskStrict();
                bringLockBack();
            }
        }, WIFI_SESSION_MS);
    }

    private boolean isStillLocked() {
        if (allowExit) return false;
        if (prefs == null) return true;
        return prefs.getBoolean("is_offline_locked", true);
    }

    private void bringLockBack() {
        try {
            enterKioskStrict();
            Intent i = new Intent(this, LockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        markLockVisible(true);
        // Wi-Fi panel से वापस → session खत्म, strict kiosk
        if (isStillLocked() && !isWifiSessionActive()) {
            clearWifiSession();
            enterKioskStrict();
        } else if (isStillLocked() && isWifiSessionActive()) {
            // अभी session चल रहा — settings allow रखो
            enterKioskWithSettings();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        markLockVisible(false);
        // Wi-Fi session में जबरदस्ती वापस मत घसीटो
        if (allowExit || isWifiSessionActive()) return;
        if (isStillLocked()) {
            handler.postDelayed(() -> {
                if (isStillLocked() && !isWifiSessionActive()) {
                    bringLockBack();
                }
            }, 2500);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (allowExit || isWifiSessionActive()) return;
        if (isStillLocked()) {
            handler.postDelayed(() -> {
                if (isStillLocked() && !isWifiSessionActive()) {
                    bringLockBack();
                }
            }, 2000);
        }
    }

    private void fetchDealerQRCode(String dealerUid) {
        FirebaseFirestore.getInstance().collection("Dealers").document(dealerUid)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        String dealerUpi = documentSnapshot.getString("upi_id");
                        if (dealerUpi != null && !dealerUpi.isEmpty()) {
                            generateQRCode(dealerUpi);
                        }
                    }
                })
                .addOnFailureListener(e ->
                        Log.e("LockActivity", "Failed to load dealer UPI: " + e.getMessage()));
    }

    private void generateQRCode(String upiId) {
        try {
            String upiUrl = "upi://pay?pa=" + upiId + "&pn=EMI_Payment&cu=INR";
            MultiFormatWriter writer = new MultiFormatWriter();
            BitMatrix matrix = writer.encode(upiUrl, BarcodeFormat.QR_CODE, 400, 400);
            BarcodeEncoder encoder = new BarcodeEncoder();
            qrCodeImage.setImageBitmap(encoder.createBitmap(matrix));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onBackPressed() {
        // blocked
    }

    private void unlockAndClose() {
        allowExit = true;
        clearWifiSession();
        markLockVisible(false);
        handler.removeCallbacksAndMessages(null);
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                stopLockTask();
            }
        } catch (Exception ignored) {
        }
        if (listener != null) listener.remove();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        markLockVisible(false);
        handler.removeCallbacksAndMessages(null);
        if (listener != null) {
            listener.remove();
            listener = null;
        }
    }
}
