package com.harshit.emilocker.customer;

import android.Manifest; 
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
        
        // नया फंक्शन कॉल: जो सिक्योरिटी रूल्स को सख्ती से लागू करेगा
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

    // यह वह नया फंक्शन है जो लोकेशन और डेवलपर ऑप्शंस को ब्लॉक करेगा
    private void enforceStrictSecurityPolicies() {
        if (dpm != null && adminComponent != null && dpm.isDeviceOwnerApp(getPackageName())) {
            try {
                // 1. लोकेशन परमिशन को बिना पूछे हमेशा के लिए Allow (Auto-Grant) करना
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setPermissionGrantState(adminComponent, getPackageName(), Manifest.permission.ACCESS_FINE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    dpm.setPermissionGrantState(adminComponent, getPackageName(), Manifest.permission.ACCESS_COARSE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                }

                // 2. GPS को ज़बरदस्ती चालू करना
                dpm.setSecureSetting(adminComponent, Settings.Secure.LOCATION_MODE, String.valueOf(Settings.Secure.LOCATION_MODE_HIGH_ACCURACY));

                // 3. कस्टमर को लोकेशन बंद करने से रोकना
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_LOCATION);

                // 4. Developer Options और USB Debugging को हमेशा के लिए डिसेबल करना
                dpm.setGlobalSetting(adminComponent, Settings.Global.ADB_ENABLED, "0");
                dpm.setGlobalSetting(adminComponent, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0");
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES);

            } catch (Exception e) {
                e.printStackTrace(); 
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingAutoQr && !qrLaunched && prefs.getString("session_id", null) == null) {
            qrLaunched = true;
            startQRScanner();
        }
    }

    private void initSdk() {
        try {
            FirebaseApp.initializeApp(this);
        } catch (Exception ignored) {
        }
        if (db == null) {
            try {
                db = FirebaseFirestore.getInstance();
            } catch (Exception ignored) {
            }
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
                intent.setComponent(new ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            } else if ("oppo".equals(manufacturer) || "realme".equals(manufacturer)) {
                intent.setComponent(new ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            } else if ("vivo".equals(manufacturer)) {
                intent.setComponent(new ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
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
            Toast.makeText(this, "Battery Usage में Background Activity ON करें", Toast.LENGTH_LONG).show();
        }
    }

    private void startQRScanner() {
        pendingAutoQr = false;
        ScanOptions options = new ScanOptions();
        options.setPrompt("Dealer QR Scan Karein");
        options.setBeepEnabled(true);
        options.setOrientationLocked(false);
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        barcodeLauncher.launch(options);
    }

    private String getSafeImei() {
        String imei = "N/A (Running Device)";
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setPermissionGrantState(
                            adminComponent,
                            getPackageName(),
                            android.Manifest.permission.READ_PHONE_STATE,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                }
                android.telephony.TelephonyManager tm =
                        (android.telephony.TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
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
                qrLaunched = false;

                if (result.getContents() != null) {
                    try {
                        initSdk();
                        String sessionId = result.getContents();

                        if (sessionId.contains("{") || sessionId.length() > 50) {
                            errorText.setText("गलत QR! कृपया Sync वाला तीसरा QR स्कैन करें।");
                            return;
                        }

                        String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
                        boolean isFullySecure = dpm != null && dpm.isDeviceOwnerApp(getPackageName());
                        String fetchedImei = getSafeImei();
                        String currentDate = new SimpleDateFormat(
                                "dd MMM yyyy, hh:mm a", Locale.getDefault()).format(new Date());

                        Map<String, Object> data = new HashMap<>();
                        data.put("model", deviceModel);
                        data.put("imei", fetchedImei);
                        data.put("security_level", isFullySecure
                                ? "Device Owner (100%)"
                                : "Admin + Overlay (Running)");

                        if (db == null) {
                            errorText.setText("Firebase not ready");
                            return;
                        }

                        errorText.setText("Syncing Data...");

                        db.collection("Customers").document(sessionId).update(data)
                                .addOnSuccessListener(aVoid -> {
                                    prefs.edit()
                                            .putString("session_id", sessionId)
                                            .putString("saved_model", deviceModel)
                                            .putString("saved_imei", fetchedImei)
                                            .putString("saved_date", currentDate)
                                            .commit();

                                    try {
                                        startSecurityService();
                                    } catch (Exception ignored) {
                                    }

                                    Toast.makeText(MainActivity.this,
                                            "Setup Complete! Device Secured.",
                                            Toast.LENGTH_LONG).show();

                                    Intent i = new Intent(MainActivity.this, MainActivity.class);
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    startActivity(i);
                                    finish();
                                })
                                .addOnFailureListener(e -> {
                                    errorText.setText("Firebase Sync Error: " + e.getMessage());
                                });
                    } catch (Exception e) {
                        errorText.setText("Scanner Error: " + e.toString());
                    }
                } else {
                    errorText.setText("Scan Cancelled — बटन दबाकर फिर स्कैन करें");
                }
            });
}
