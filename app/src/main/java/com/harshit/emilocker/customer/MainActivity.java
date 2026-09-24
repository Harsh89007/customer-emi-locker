package com.harshit.emilocker.customer;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private FirebaseFirestore db;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private TextView errorText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. बिना XML के सीधा Java UI (100% क्रैश-प्रूफ ढांचा)
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);
        layout.setPadding(50, 50, 50, 50);
        layout.setBackgroundColor(Color.parseColor("#0F172A"));

        Button btn = new Button(this);
        btn.setText("START DEVICE SETUP");
        btn.setBackgroundColor(Color.parseColor("#38BDF8"));
        btn.setTextColor(Color.BLACK);
        btn.setTextSize(18);

        errorText = new TextView(this);
        errorText.setTextColor(Color.RED);
        errorText.setTextSize(16);
        errorText.setPadding(0, 40, 0, 0);

        layout.addView(btn);
        layout.addView(errorText);
        setContentView(layout);

        // 2. बटन दबाने के बाद ही सारे रिस्की काम होंगे, ताकि ऐप क्रैश न हो
        btn.setOnClickListener(v -> {
            try {
                errorText.setText("Checking Security System...");
                try { FirebaseApp.initializeApp(this); } catch (Exception ignored) {}
                
                db = FirebaseFirestore.getInstance();
                dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                adminComponent = new ComponentName(this, MyAdminReceiver.class);
                
                checkAndProceed();
            } catch (Exception e) {
                // अगर कोई एरर होगा, तो क्रैश होने के बजाय यहाँ लाल रंग में लिख जाएगा
                errorText.setText("CRASH ERROR DETAILS:\n" + e.toString());
            }
        });
    }

    private void checkAndProceed() throws Exception {
        boolean isDeviceOwner = dpm.isDeviceOwnerApp(getPackageName());

        if (isDeviceOwner) {
            startService(new Intent(this, LockListenerService.class));
            startQRScanner();
            return;
        }

        int setupComplete = Settings.Secure.getInt(getContentResolver(), "user_setup_complete", 0);
        if (setupComplete == 0) {
            errorText.setText("यह नया डिवाइस है! कृपया पहले Test DPC से ओनरशिप लें!");
            return;
        }

        if (!dpm.isAdminActive(adminComponent)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "कृपया EMI सिक्योरिटी एक्टिवेट करें।");
            startActivity(intent);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        startService(new Intent(this, LockListenerService.class));
        startQRScanner();
    }

    private void startQRScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("Dealer QR Scan Karein");
        options.setBeepEnabled(true);
        barcodeLauncher.launch(options);
    }

    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> barcodeLauncher = 
        registerForActivityResult(new ScanContract(), result -> {
            if(result.getContents() != null) {
                try {
                    String sessionId = result.getContents();
                    String deviceModel = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
                    boolean isFullySecure = dpm.isDeviceOwnerApp(getPackageName()); 

                    Map<String, Object> data = new HashMap<>();
                    data.put("model", deviceModel);
                    data.put("is_locked", false);
                    data.put("is_released", false);
                    data.put("security_level", isFullySecure ? "Device Owner (100%)" : "Admin + Overlay (Running)");

                    db.collection("Customers").document(sessionId).set(data)
                        .addOnSuccessListener(aVoid -> {
                            Toast.makeText(this, "Device Registered!", Toast.LENGTH_LONG).show();
                            finish();
                        }).addOnFailureListener(e -> {
                            errorText.setText("Firebase Error: " + e.getMessage());
                        });
                } catch (Exception e) {
                    errorText.setText("Scanner Error: " + e.toString());
                }
            } else {
                errorText.setText("Scan Cancelled");
            }
        });
}

