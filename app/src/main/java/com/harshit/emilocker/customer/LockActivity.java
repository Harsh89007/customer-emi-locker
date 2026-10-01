package com.harshit.emilocker.customer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
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
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private ListenerRegistration listener;
    private ImageView qrCodeImage;
    private boolean openingWifi = false; // Wi-Fi खोलते समय re-lock मत करो

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent() != null && getIntent().getBooleanExtra("force_unlock", false)) {
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
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_lock);
        qrCodeImage = findViewById(R.id.qr_code_image);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyAdminReceiver.class);

        // Kiosk: सिर्फ अपना app (Settings package allow नहीं — security)
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
                dpm.addUserRestriction(adminComponent,
                        android.os.UserManager.DISALLOW_FACTORY_RESET);
                startLockTask();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Button btnConnectWifi = findViewById(R.id.btn_connect_wifi);
        if (btnConnectWifi != null) {
            btnConnectWifi.setOnClickListener(v -> openWifiSafely());
        }

        Context deviceContext = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            deviceContext = createDeviceProtectedStorageContext();
        }
        SharedPreferences prefs = deviceContext.getSharedPreferences("AppPrefs", MODE_PRIVATE);
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

    /**
     * Kiosk में Wi-Fi: थोड़ी देर lock-task हटाओ → panel खोलो → onResume पर वापस lock.
     * Full Settings app allow नहीं करते।
     */
    private void openWifiSafely() {
        openingWifi = true;
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                try {
                    stopLockTask();
                } catch (Exception ignored) {
                }
            }

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
                openingWifi = false;
                reenterLockTask();
                Toast.makeText(this,
                        "Wi-Fi नहीं खुला। Data SIM डालें या Notification से Wi-Fi ON करें।",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            openingWifi = false;
            reenterLockTask();
            Toast.makeText(this, "Wi-Fi error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void reenterLockTask() {
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
                startLockTask();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Wi-Fi panel से वापस आए तो फिर kiosk
        if (openingWifi) {
            openingWifi = false;
            reenterLockTask();
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
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Wi-Fi खोलते समय वापस मत घसीटो
        if (openingWifi) return;
        if (listener != null) {
            Intent i = new Intent(this, LockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        }
    }

    @Override
    public void onBackPressed() {
        // blocked
    }

    private void unlockAndClose() {
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                stopLockTask();
            }
        } catch (Exception ignored) {
        }
        if (listener != null) listener.remove();
        finish();
    }
        }
