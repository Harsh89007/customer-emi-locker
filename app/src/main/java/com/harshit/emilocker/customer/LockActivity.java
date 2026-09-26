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
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
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

        setContentView(R.layout.activity_lock);
        
        qrCodeImage = findViewById(R.id.qr_code_image);

        // वाई-फाई बटन का लॉजिक (ऑफ़लाइन डेडलॉक से बचने के लिए)
        Button btnConnectWifi = findViewById(R.id.btn_connect_wifi);
        if (btnConnectWifi != null) {
            btnConnectWifi.setOnClickListener(v -> {
                Intent panelIntent;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    panelIntent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                } else {
                    panelIntent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                }
                panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(panelIntent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        // Firebase से Admin वाली UPI ID उठाकर QR जनरेट करना
        FirebaseFirestore.getInstance().collection("AdminSettings").document("PaymentInfo")
            .get().addOnSuccessListener(documentSnapshot -> {
                if (documentSnapshot.exists()) {
                    String adminUpi = documentSnapshot.getString("upi_id");
                    if (adminUpi != null && !adminUpi.isEmpty()) {
                        generateQRCode(adminUpi);
                    }
                }
            });

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                // 1. एडमिन कंपोनेंट बनाएँ
                android.content.ComponentName myAdmin = new android.content.ComponentName(this, MyAdminReceiver.class);
                
                // 2. सिस्टम को बताएं कि इस ऐप को बिना परमिशन पूछे लॉक करने का अधिकार है
                // ध्यान दें: यहाँ "com.android.settings" जोड़ा गया है ताकि वाई-फाई पैनल लॉक स्क्रीन के ऊपर भी खुल सके
                dpm.setLockTaskPackages(myAdmin, new String[]{getPackageName(), "com.android.settings"});
                
                // 3. फ़ोन को Settings से फ़ैक्टरी रिसेट होने से भी ब्लॉक करें
                dpm.addUserRestriction(myAdmin, android.os.UserManager.DISALLOW_FACTORY_RESET);
                
                // 4. अब लॉक करें (कोई पॉप-अप नहीं आएगा)
                startLockTask(); 
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // नया लॉजिक: Device Protected Storage का इस्तेमाल ताकि रीबूट होने पर बिना पिन के भी session_id मिल सके
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
                        }
                    }
                });
        }
    }

    // यह फंक्शन UPI ID को स्कैन होने वाले QR कोड में बदलता है
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

    // अगर कस्टमर होम बटन दबाने की कोशिश करता है, तो यह तुरंत वापस खुलेगा
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
