package com.harshit.emilocker.customer;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private Button btn;
    private SharedPreferences prefs;
    private boolean autoStartRequested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);
        layout.setPadding(50, 50, 50, 50);
        layout.setBackgroundColor(Color.parseColor("#0F172A"));

        btn = new Button(this);
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

        String savedSession = prefs.getString("session_id", null);
        if (savedSession != null) {
            btn.setText("DEVICE IS SECURED");
            btn.setEnabled(false);
            startSecurityService(); 
            return; 
        }

        btn.setOnClickListener(v -> {
            try {
                errorText.setText("Checking Security System...");
                try { FirebaseApp.initializeApp(this); } catch (Exception ignored) {}
                
                db = FirebaseFirestore.getInstance();
                dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                adminComponent = new ComponentName(this, MyAdminReceiver.class);
                
                checkAndProceed();
            } catch (Exception e) {
                errorText.setText("CRASH ERROR:\n" + e.toString());
            }
        });
    }

    private void startSecurityService() {
        Intent serviceIntent = new Intent(this, LockListenerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void checkAndProceed() throws Exception {
        boolean isDeviceOwner = dpm.isDeviceOwnerApp(getPackageName());

        if (isDeviceOwner) {
            startQRScanner();
            return;
        }

        int setupComplete = Settings.Secure.getInt(getContentResolver(), "user_setup_complete", 0);
        if (setupComplete == 0) {
            errorText.setText("यह नया डिवाइस है! कृपया Test DPC से ओनरशिप लें!");
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

        if (!autoStartRequested) {
            requestAutoStartPermission();
            autoStartRequested = true;
            errorText.setText("परमिशन देने के बाद दोबारा START बटन दबाएं");
            return;
        }

        startQRScanner();
    }

    private void requestAutoStartPermission() {
        try {
            String manufacturer = android.os.Build.MANUFACTURER.toLowerCase();
            Intent intent = new Intent();
            if ("xiaomi".equals(manufacturer)) {
                intent.setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            } else if ("oppo".equals(manufacturer) || "realme".equals(manufacturer)) {
                intent.setComponent(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            } else if ("vivo".equals(manufacturer)) {
                intent.setComponent(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
            } else {
                throw new Exception("Custom intent not found");
            }
            startActivity(intent);
            Toast.makeText(this, "यहाँ Auto-Start चालू करें", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
            Toast.makeText(this, "Battery Usage में जाकर 'Background Activity' ON करें", Toast.LENGTH_LONG).show();
        }
    }

    private void startQRScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("Dealer QR Scan Karein");
        options.setBeepEnabled(true);
        barcodeLauncher.launch(options);
    }

    // --- नया: बिना परमिशन मांगे ऑटो-ग्रांट (Auto-Grant) और असली IMEI निकालने वाला फंक्शन ---
    private String getSafeImei() {
        String imei = "N/A (Running Device)";
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                
                // 1. जादू: खुद को छुपकर READ_PHONE_STATE परमिशन देना (कोई पॉपअप नहीं आएगा)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setPermissionGrantState(adminComponent, getPackageName(),
                            android.Manifest.permission.READ_PHONE_STATE,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                }

                // 2. परमिशन मिलने के बाद असली IMEI निकालना
                android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    imei = tm.getImei();
                } else {
                    imei = tm.getDeviceId();
                }
            }
        } catch (Exception e) {
            imei = "N/A (Security Blocked)";
        }
        return (imei != null && !imei.trim().isEmpty()) ? imei : "N/A (Running Device)";
    }
    
    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> barcodeLauncher = 
        registerForActivityResult(new ScanContract(), result -> {
            if(result.getContents() != null) {
                try {
                    String sessionId = result.getContents();
                    String deviceModel = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
                    boolean isFullySecure = dpm.isDeviceOwnerApp(getPackageName()); 

                    String fetchedImei = getSafeImei(); // अब यह असली IMEI लाएगा!

                    Map<String, Object> data = new HashMap<>();
                    data.put("model", deviceModel);
                    data.put("imei", fetchedImei); 
                    data.put("security_level", isFullySecure ? "Device Owner (100%)" : "Admin + Overlay (Running)");

                    db.collection("Customers").document(sessionId).update(data)
                        .addOnSuccessListener(aVoid -> {
                            prefs.edit().putString("session_id", sessionId).apply();
                            startSecurityService(); 
                            Toast.makeText(this, "Device Registered!", Toast.LENGTH_LONG).show();
                            btn.setText("DEVICE IS SECURED");
                            btn.setEnabled(false);
                            errorText.setText("");
                        }).addOnFailureListener(e -> {
                            errorText.setText("Firebase Sync Error: " + e.getMessage());
                        });
                } catch (Exception e) {
                    errorText.setText("Scanner Error: " + e.toString());
                }
            } else {
                errorText.setText("Scan Cancelled");
            }
        });
}
