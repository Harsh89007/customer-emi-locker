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
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.List;

/**
 * Professional blue lock screen (EMI style).
 * Kiosk + Wi‑Fi 60s session. Unlock codes from Firestore / prefs.
 */
public class LockActivity extends Activity {
    private static final long WIFI_SESSION_MS = 60_000L;

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private ListenerRegistration listener;
    private ImageView qrCodeImage;
    private TextView tvUnlockCodes;
    private TextView tvLockMessage;
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
        tvUnlockCodes = findViewById(R.id.tv_unlock_codes);
        tvLockMessage = findViewById(R.id.tv_lock_message);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyAdminReceiver.class);

        Context deviceContext = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            deviceContext = createDeviceProtectedStorageContext();
        }
        prefs = deviceContext.getSharedPreferences("AppPrefs", MODE_PRIVATE);

        markLockVisible(true);
        showLocalUnlockCodes();

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
                                // Unlock codes from server
                                try {
                                    Object codes = snapshot.get("unlock_codes");
                                    if (codes instanceof List) {
                                        @SuppressWarnings("unchecked")
                                        List<Object> list = (List<Object>) codes;
                                        StringBuilder sb = new StringBuilder();
                                        int i = 0;
                                        for (Object o : list) {
                                            if (i > 0 && i % 3 == 0) sb.append("\n");
                                            else if (i > 0) sb.append("   ");
                                            sb.append(String.valueOf(o));
                                            i++;
                                        }
                                        if (tvUnlockCodes != null && sb.length() > 0) {
                                            tvUnlockCodes.setText(sb.toString());
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                                String msg = snapshot.getString("lock_message");
                                if (msg != null && !msg.isEmpty() && tvLockMessage != null) {
                                    tvLockMessage.setText(msg);
                                }
                                String dealerUid = snapshot.getString("dealer_uid");
                                if (dealerUid != null && !dealerUid.isEmpty()) {
                                    fetchDealerQRCode(dealerUid);
                                }
                            }
                        }
                    });
        }
    }

    private void showLocalUnlockCodes() {
        if (tvUnlockCodes == null || prefs == null) return;
        String local = prefs.getString("unlock_codes_display", null);
        if (local != null && !local.isEmpty()) {
            tvUnlockCodes.setText(local);
        }
    }

    private void markLockVisible(boolean visible) {
        if (prefs != null) {
            prefs.edit().putBoolean("lock_activity_visible", visible).apply();
        }
    }

    private boolean isWifiSessionActive() {
        if (prefs == null) return false;
        return System.currentTimeMillis() < prefs.getLong("wifi_session_until", 0L);
    }

    private void clearWifiSession() {
        if (prefs != null) {
            prefs.edit().putLong("wifi_session_until", 0L).apply();
        }
    }

    private void startWifiSession() {
        if (prefs != null) {
            prefs.edit()
                    .putLong("wifi_session_until", System.currentTimeMillis() + WIFI_SESSION_MS)
                    .apply();
        }
    }

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

        Toast.makeText(this, "Wi-Fi जोड़ो (60 sec)। वापस आने पर lock लगेगा।",
                Toast.LENGTH_LONG).show();

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
        if (isStillLocked() && !isWifiSessionActive()) {
            clearWifiSession();
            enterKioskStrict();
        } else if (isStillLocked() && isWifiSessionActive()) {
            enterKioskWithSettings();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        markLockVisible(false);
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
            if (qrCodeImage != null) {
                qrCodeImage.setImageBitmap(encoder.createBitmap(matrix));
            }
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
