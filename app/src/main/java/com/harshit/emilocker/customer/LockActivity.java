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
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private ListenerRegistration listener;
    private ImageView qrCodeImage;
    private boolean openingWifi = false;
    private boolean allowExit = false; // सिर्फ force_unlock / admin unlock
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent() != null && getIntent().getBooleanExtra("force_unlock", false)) {
            allowExit = true;
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

        enterKiosk();

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

    private void enterKiosk() {
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                // सिर्फ अपना package — Settings allow नहीं
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

    /**
     * Wi-Fi: पहले बिना stopLockTask try।
     * ज़रूरत पड़े तो थोड़ी देर stop → panel → 800ms में ज़बरदस्ती वापस kiosk + यह activity.
     */
    private void openWifiSafely() {
        openingWifi = true;

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
            // Panel lock-task में नहीं खुला → थोड़ी देर kiosk हटाकर खोलो
            try {
                if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                    stopLockTask();
                }
            } catch (Exception ignored) {
            }
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
            enterKiosk();
            Toast.makeText(this,
                    "Wi-Fi नहीं खुला। Data SIM इस्तेमाल करें।",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // 800ms बाद ज़बरदस्ती lock वापस — customer home पर भी फँसे तो screen लौट आए
        handler.postDelayed(() -> {
            openingWifi = false;
            if (isStillLocked()) {
                bringLockBack();
            }
        }, 800);
    }

    private boolean isStillLocked() {
        if (allowExit) return false;
        if (prefs == null) return true;
        return prefs.getBoolean("is_offline_locked", true);
    }

    private void bringLockBack() {
        try {
            enterKiosk();
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
        openingWifi = false;
        if (isStillLocked()) {
            enterKiosk();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Home / Recents → 1 sec में वापस
        if (!allowExit && isStillLocked()) {
            handler.postDelayed(() -> {
                if (isStillLocked()) {
                    bringLockBack();
                }
            }, 1000);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (allowExit) return;
        // Wi-Fi या Home — दोनों में जल्दी वापस
        handler.postDelayed(() -> {
            if (isStillLocked()) {
                bringLockBack();
            }
        }, openingWifi ? 800 : 300);
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
        handler.removeCallbacksAndMessages(null);
        if (listener != null) {
            listener.remove();
            listener = null;
        }
    }
}
