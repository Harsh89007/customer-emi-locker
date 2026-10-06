package com.harshit.emilocker.customer;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private TextView errorText;
    private Button btn;
    private SharedPreferences prefs;
    private boolean autoStartRequested = false;
    private boolean pendingAutoQr = false;
    private boolean qrLaunched = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context deviceContext = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            deviceContext = createDeviceProtectedStorageContext();
        }
        prefs = deviceContext.getSharedPreferences("AppPrefs", MODE_PRIVATE);

        initSdk();
        
        // सभी सिक्योरिटी रूल्स और परमिशन यहाँ लागू होंगी
        enforceStrictSecurityPolicies();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
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
        if (savedSession != null && !savedSession.trim().isEmpty()) {
            btn.setText("✔ DEVICE IS SECURED");
            btn.setEnabled(false);
            btn.setBackgroundColor(Color.parseColor("#10B981"));

            String savedModel = prefs.getString("saved_model", Build.MANUFACTURER + " " + Build.MODEL);
            String savedImei = prefs.getString("saved_imei", "N/A");
            String savedDate = prefs.getString("saved_date", "Date Not Available");

            TextView infoText = new TextView(this);
            infoText.setTextColor(Color.WHITE);
            infoText.setTextSize(16);
            infoText.setPadding(0, 60, 0, 0);
            infoText.setGravity(Gravity.CENTER);
            infoText.setLineSpacing(0, 1.4f);
            infoText.setText("Model: " + savedModel + "\nIMEI: " + savedImei + "\nSync Date: " + savedDate);
            layout.addView(infoText);

            try {
                startSecurityService();
            } catch (Exception e) {
                errorText.setText("Service error: " + e.getMessage());
            }
            return;
        }

        boolean fromTransfer = getIntent().getBooleanExtra(
                MyAdminReceiver.EXTRA_OPEN_DEALER_QR, false);
        boolean isOwner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        if (fromTransfer || isOwner) {
            btn.setText("SCAN DEALER QR");
            errorText.setText("नया फोन — Device Owner मिल गया। QR स्कैन करें।");
            pendingAutoQr = true;
        }

        btn.setOnClickListener(v -> {
            try {
                errorText.setText("Checking Security System...");
                initSdk();
                checkAndProceed();
            } catch (Exception e) {
                errorText.setText("CRASH ERROR:\n" + e.toString());
            }
        });
    }

    private void enforceStrictSecurityPolicies() {
        if (dpm != null && adminComponent != null && dpm.isDeviceOwnerApp(getPackageName())) {
            
            // 1. Location Permissions Auto-Grant (Fine, Coarse और Background)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setPermissionGrantState(adminComponent, getPackageName(), Manifest.permission.ACCESS_FINE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    dpm.setPermissionGrantState(adminComponent, getPackageName(), Manifest.permission.ACCESS_COARSE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dpm.setPermissionGrantState(adminComponent, getPackageName(), Manifest.permission.ACCESS_BACKGROUND_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                }
            } catch (Exception e) { 
                e.printStackTrace(); 
            }

            // 2. अन्य ज़रूरी सिक्योरिटी रिस्ट्रिक्टशंस (डेवलपर ऑप्शन, फैक्ट्री रीसेट और सेफ बूट ब्लॉक)
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES);
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT);
            } catch (Exception e) { 
                e.printStackTrace(); 
            }

            // 3. ADB को सिस्टम से ऑफ रखना
            try {
                dpm.setGlobalSetting(adminComponent, Settings.Global.ADB_ENABLED, "0");
            } catch (Exception e) { 
                e.printStackTrace(); 
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // ऐप चालू होते ही बैकग्राउंड में परमिशन को दोबारा फोर्सफुली पुश करना
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dpm.setPermissionGrantState(adminComponent, getPackageName(), Manifest.permission.ACCESS_BACKGROUND_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                }
            } catch (Exception ignored) {}
        }

        if (pendingAutoQr && !qrLaunched && prefs.getString("session_id", null) == null) {
            qrLaunched = true;
            startQRScanner();
        }
    }

    private void initSdk() {
        try {
            FirebaseApp.initializeApp(this);
        } catch (Exception ignored) {}
        if (db == null) {
            try { db = FirebaseFirestore.getInstance(); } catch (Exception ignored) {}
        }
        if (dpm == null) {
            dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        }
        if (adminComponent == null) {
            adminComponent = MyAdminReceiver.getComponentName(this);
        }
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
        initSdk();
        boolean isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        if (isDeviceOwner) {
            startQRScanner();
            return;
        }

        int setupComplete = Settings.Secure.getInt(getContentResolver(), "user_setup_complete", 0);
        if (setupComplete == 0) {
            errorText.setText("यह नया डिवाइस है। पहले Loader से Device Owner बनाएँ।");
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
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
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
            String manufacturer = Build.MANUFACTURER.toLowerCase();
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
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        }
    }

    private void startQRScanner() {
        pendingAutoQr = false;
        ScanOptions options = new ScanOptions();
        // Portrait + framed QR box (reference-style). No other behaviour change.
        options.setOrientationLocked(true);
        options.setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity.class);
        options.setPrompt("QR code box ke andar fit karein");
        options.setBeepEnabled(true);
        options.setCameraId(0);
        options.setBarcodeImageEnabled(false);
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        barcodeLauncher.launch(options);
    }

    private String getSafeImei() {
        String imei = "N/A (Running Device)";
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                // READ_PHONE_STATE परमिशन को फोर्सफुली ग्रांट करना ताकि Security Blocked ना आए
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setPermissionGrantState(
                            adminComponent,
                            getPackageName(),
                            android.Manifest.permission.READ_PHONE_STATE,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                }
                
                android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        imei = tm.getImei();
                    } else {
                        imei = tm.getDeviceId();
                    }
                }
            }
        } catch (Exception e) {
            imei = "N/A (Security Blocked)";
        }

        // यदि IMEI फैच करने में दिक्कत आए या Blocked हो, तो Android ID का फॉलबैक इस्तेमाल करें
        if (imei == null || imei.trim().isEmpty() || imei.contains("Blocked")) {
            try {
                imei = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            } catch (Exception ignored) {}
        }

        return (imei != null && !imei.trim().isEmpty()) ? imei : "N/A (Unavailable)";
    }

    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                qrLaunched = false;
                if (result.getContents() != null) {
                    try {
                        initSdk();
                        String sessionId = result.getContents();
                        String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
                        boolean isFullySecure = dpm != null && dpm.isDeviceOwnerApp(getPackageName());
                        String fetchedImei = getSafeImei();
                        String currentDate = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(new Date());

                        Map<String, Object> data = new HashMap<>();
                        data.put("model", deviceModel);
                        data.put("imei", fetchedImei);
                        data.put("security_level", isFullySecure ? "Device Owner (100%)" : "Admin + Overlay (Running)");

                        db.collection("Customers").document(sessionId).update(data)
                                .addOnSuccessListener(aVoid -> {
                                    prefs.edit()
                                            .putString("session_id", sessionId)
                                            .putString("saved_model", deviceModel)
                                            .putString("saved_imei", fetchedImei)
                                            .putString("saved_date", currentDate)
                                            .commit();

                                    try { startSecurityService(); } catch (Exception ignored) {}

                                    Toast.makeText(MainActivity.this, "Setup Complete! Device Secured.", Toast.LENGTH_LONG).show();
                                    Intent i = new Intent(MainActivity.this, MainActivity.class);
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    startActivity(i);
                                    finish();
                                });
                    } catch (Exception e) {
                        errorText.setText("Scanner Error: " + e.toString());
                    }
                }
            });
        }
