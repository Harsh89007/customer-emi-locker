package com.harshit.emilocker.customer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
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
    private ListenerRegistration listener;
    private ImageView qrCodeImage;

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
        
        if (getIntent() != null && getIntent().getBooleanExtra("force_unlock", false)) {
            try {
                DevicePolicyManager dpmLocal =
                        (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (dpmLocal != null && dpmLocal.isDeviceOwnerApp(getPackageName())) {
                    stopLockTask();
                }
            } catch (Exception ignored) {}
            finish();
            return;
        }
        
        setContentView(R.layout.activity_lock);
        
        qrCodeImage = findViewById(R.id.qr_code_image);

        // वाई-फाई बटन का नया और पावरफुल लॉजिक
        Button btnConnectWifi = findViewById(R.id.btn_connect_wifi);
        if (btnConnectWifi != null) {
            btnConnectWifi.setOnClickListener(v -> {
                try {
                    Intent panelIntent;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        panelIntent = new Intent(Settings.Panel.ACTION_WIFI); // ज़्यादातर Q+ फोंस पर यह परफेक्ट काम करता है
                    } else {
                        panelIntent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                    }
                    panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(panelIntent);
                } catch (Exception e) {
                    // अगर Panel API ब्लॉक है (कुछ Samsung/Oppo में), तो सीधा Settings खोलें
                    try {
                        Intent fallbackIntent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fallbackIntent);
                    } catch (Exception ex) {
                        Toast.makeText(this, "Wi-Fi पैनल खोलने में समस्या आ रही है", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                android.content.ComponentName myAdmin = new android.content.ComponentName(this, MyAdminReceiver.class);
                // "com.android.settings" की वजह से वाई-फाई का पैनल लॉक स्क्रीन पर काम करेगा
                dpm.setLockTaskPackages(myAdmin, new String[]{getPackageName(), "com.android.settings"});
                dpm.addUserRestriction(myAdmin, android.os.UserManager.DISALLOW_FACTORY_RESET);
                startLockTask(); 
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        Context deviceContext = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            deviceContext = createDeviceProtectedStorageContext();
        }
        SharedPreferences prefs = deviceContext.getSharedPreferences("AppPrefs", MODE_PRIVATE);
        String sessionId = prefs.getString("session_id", null);

        if (sessionId != null) {
            listener = FirebaseFirestore.getInstance().collection("Customers").document(sessionId)
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
            .addOnFailureListener(e -> Log.e("LockActivity", "Failed to load dealer UPI: " + e.getMessage()));
    }

    private void generateQRCode(String upiId) {
        try {
            String upiUrl = "upi://pay?pa=" + upiId + "&pn=EMI_Payment&cu=INR";
            MultiFormatWriter writer = new MultiFormatWriter();
            BitMatrix matrix = writer.encode(upiUrl, BarcodeFormat.QR_CODE, 400, 400);
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bitmap = encoder.createBitmap(matrix);
            qrCodeImage.setImageBitmap(bitmap);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

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
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                stopLockTask();
            }
        } catch (Exception e) {}
        if (listener != null) listener.remove();
        finish();
    }
}
