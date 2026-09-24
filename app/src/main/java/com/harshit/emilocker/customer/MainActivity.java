package com.harshit.emilocker.customer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private FirebaseFirestore db;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        db = FirebaseFirestore.getInstance();
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyAdminReceiver.class);

        checkAndProceed();
    }

    private void checkAndProceed() {
        try {
            boolean isDeviceOwner = dpm.isDeviceOwnerApp(getPackageName());
            if (isDeviceOwner) {
                // नया फोन है और ओनरशिप मिल गई है
                startService(new Intent(this, LockListenerService.class));
                startQRScanner();
                return;
            }

            int setupComplete = Settings.Secure.getInt(getContentResolver(), "user_setup_complete", 0);
            if (setupComplete == 0) {
                Toast.makeText(this, "कृपया पहले Test DPC से ओनरशिप लें!", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            // --- रनिंग डिवाइस का सेफ परमिशन लॉजिक ---
            if (!dpm.isAdminActive(adminComponent)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "कृपया EMI सिक्योरिटी एक्टिवेट करें।");
                startActivity(intent);
                finish(); 
                Toast.makeText(this, "एक्टिवेट करने के बाद ऐप दोबारा खोलें", Toast.LENGTH_LONG).show();
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                finish(); 
                Toast.makeText(this, "Allow display over other apps चालू करके ऐप दोबारा खोलें", Toast.LENGTH_LONG).show();
                return;
            }

            // अगर दोनों परमिशन मिल गईं तो सीधे स्कैनर खोलो
            startService(new Intent(this, LockListenerService.class));
            startQRScanner();

        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // हमने स्कैनर का नया तरीका इस्तेमाल किया है जो Activity में बिना क्रैश हुए चलता है
    private void startQRScanner() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setPrompt("Dealer QR Scan Karein");
        integrator.setBeepEnabled(true);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if(result != null) {
            if(result.getContents() != null) {
                String sessionId = result.getContents();
                String deviceModel = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
                boolean isFullySecure = dpm.isDeviceOwnerApp(getPackageName()); 

                Map<String, Object> dbData = new HashMap<>();
                dbData.put("model", deviceModel);
                dbData.put("is_locked", false);
                dbData.put("is_released", false);
                dbData.put("security_level", isFullySecure ? "Device Owner (100%)" : "Admin + Overlay (Running)");

                db.collection("Customers").document(sessionId).set(dbData)
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "Device Registered!", Toast.LENGTH_LONG).show();
                        finish();
                    }).addOnFailureListener(e -> {
                        Toast.makeText(this, "Registration Failed!", Toast.LENGTH_SHORT).show();
                        finish();
                    });
            } else {
                finish(); // अगर बैक कर दिया
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}

