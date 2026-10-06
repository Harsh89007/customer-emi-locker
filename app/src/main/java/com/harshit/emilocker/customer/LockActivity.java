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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lock screen:
 * - Codes NOT shown (dealer panel only)
 * - Customer enters offline code to unlock
 * - Wi-Fi 60s session with expanded lock-task allowlist
 */
public class LockActivity extends Activity {
    private static final String TAG = "LockActivity";
    private static final long WIFI_SESSION_MS = 60_000L;

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private ListenerRegistration listener;
    private ImageView qrCodeImage;
    private TextView tvLockMessage;
    private EditText etUnlockCode;
    private boolean allowExit = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private final List<String> validCodes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("EmiLockerPrefs", MODE_PRIVATE);

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

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);

        setContentView(R.layout.activity_lock);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyAdminReceiver.class);

        qrCodeImage = findViewById(R.id.qr_code_image);
        tvLockMessage = findViewById(R.id.tv_lock_message);
        etUnlockCode = findViewById(R.id.et_unlock_code);

        loadValidCodesFromPrefs();

        if (!isWifiSessionActive()) {
            enterKioskStrict();
        } else {
            enterKioskWithSettings();
        }

        Button btnConnectWifi = findViewById(R.id.btn_connect_wifi);
        if (btnConnectWifi != null) {
            btnConnectWifi.setOnClickListener(v -> openWifiSafely());
        }

        Button btnSubmitCode = findViewById(R.id.btn_submit_code);
        if (btnSubmitCode != null) {
            btnSubmitCode.setOnClickListener(v -> tryOfflineUnlock());
        }

        // Firestore: lock state + refresh codes (local only, not shown) + UPI
        String customerId = prefs.getString("customer_id", null);
        String dealerUid = prefs.getString("dealer_uid", null);

        if (customerId != null && !customerId.isEmpty()) {
            listener = FirebaseFirestore.getInstance()
                    .collection("Customers")
                    .document(customerId)
                    .addSnapshotListener((snapshot, error) -> {
                        if (error != null || snapshot == null || !snapshot.exists()) return;
                        try {
                            Boolean locked = snapshot.getBoolean("is_locked");
                            if (locked != null && !locked) {
                                unlockAndClose();
                                return;
                            }
                            Object codes = snapshot.get("unlock_codes");
                            if (codes instanceof List) {
                                cacheCodesFromList((List<?>) codes);
                            }
                            if (dealerUid == null || dealerUid.isEmpty()) {
                                String d = snapshot.getString("dealer_uid");
                                if (d != null) fetchDealerQRCode(d);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "listener: " + e.getMessage());
                        }
                    });
        }

        if (dealerUid != null && !dealerUid.isEmpty()) {
            fetchDealerQRCode(dealerUid);
        }

        markLockVisible(true);
    }

    private void loadValidCodesFromPrefs() {
        validCodes.clear();
        String local = prefs.getString("unlock_codes_display", null);
        if (local == null || local.trim().isEmpty()) return;
        for (String part : local.replace("\n", " ").split("\\s+")) {
            String c = part.trim();
            if (c.length() >= 4) validCodes.add(c);
        }
    }

    private void cacheCodesFromList(List<?> list) {
        validCodes.clear();
        StringBuilder display = new StringBuilder();
        int i = 0;
        for (Object o : list) {
            if (o == null) continue;
            String c = String.valueOf(o).trim();
            if (c.isEmpty()) continue;
            validCodes.add(c);
            if (i > 0) display.append(i % 3 == 0 ? "\n" : "  ");
            display.append(c);
            i++;
        }
        if (display.length() > 0) {
            prefs.edit().putString("unlock_codes_display", display.toString()).apply();
        }
    }

    /** Customer types code from dealer — never shown on this screen */
    private void tryOfflineUnlock() {
        if (etUnlockCode == null) return;
        String input = etUnlockCode.getText() != null
                ? etUnlockCode.getText().toString().trim() : "";
        if (input.isEmpty()) {
            Toast.makeText(this, "Code dalo", Toast.LENGTH_SHORT).show();
            return;
        }

        if (validCodes.isEmpty()) loadValidCodesFromPrefs();

        boolean match = false;
        for (String c : validCodes) {
            if (c.equalsIgnoreCase(input) || c.replace(" ", "").equalsIgnoreCase(input)) {
                match = true;
                break;
            }
        }

        if (!match) {
            Toast.makeText(this, "Galat code", Toast.LENGTH_SHORT).show();
            etUnlockCode.setText("");
            return;
        }

        // Offline unlock success
        prefs.edit()
                .putBoolean("is_offline_locked", false)
                .putBoolean("is_locked", false)
                .apply();

        String customerId = prefs.getString("customer_id", null);
        if (customerId != null && !customerId.isEmpty()) {
            try {
                FirebaseFirestore.getInstance().collection("Customers").document(customerId)
                        .update("is_locked", false);
            } catch (Exception ignored) {
            }
        }

        Toast.makeText(this, "Unlocked!", Toast.LENGTH_SHORT).show();
        unlockAndClose();
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
                startLockTask();
            }
        } catch (Exception e) {
            Log.e(TAG, "kiosk strict: " + e.getMessage());
        }
    }

    /** Allow Settings / SystemUI so Wi-Fi panel can open under Device Owner */
    private void enterKioskWithSettings() {
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(adminComponent, new String[]{
                        getPackageName(),
                        "com.android.settings",
                        "com.android.settings.intelligence",
                        "com.samsung.android.settings",
                        "com.samsung.android.lool",
                        "com.miui.securitycenter",
                        "com.miui.home",
                        "com.android.systemui",
                        "com.google.android.permissioncontroller",
                        "com.android.permissioncontroller",
                        "com.android.networkstack.tethering",
                        "com.android.phone",
                        "com.android.systemui.qs"
                });
                startLockTask();
            }
        } catch (Exception e) {
            Log.e(TAG, "kiosk settings: " + e.getMessage());
        }
    }

    private void openWifiSafely() {
        startWifiSession();
        enterKioskWithSettings();

        // Small delay so lock-task packages apply before opening Settings
        handler.postDelayed(() -> {
            boolean opened = tryOpenWifiUi();
            if (!opened) {
                clearWifiSession();
                enterKioskStrict();
                Toast.makeText(this,
                        "Wi-Fi settings nahi khuli. Settings se manually Wi-Fi on karein.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this,
                    "Wi-Fi jodo (60 sec). Wapas aane par lock lagega.",
                    Toast.LENGTH_LONG).show();
        }, 300);

        handler.postDelayed(() -> {
            if (!allowExit && isStillLocked()) {
                clearWifiSession();
                enterKioskStrict();
                bringLockBack();
            }
        }, WIFI_SESSION_MS);
    }

    private boolean tryOpenWifiUi() {
        // 1) Android 10+ system Wi-Fi panel (best)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (startIntentSafe(new Intent(Settings.Panel.ACTION_WIFI))) return true;
            if (startIntentSafe(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) return true;
        }
        // 2) Full Wi-Fi settings
        Intent wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
        wifi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (startIntentSafe(wifi)) return true;

        // 3) Wireless settings
        Intent wireless = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        wireless.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (startIntentSafe(wireless)) return true;

        // 4) Generic settings
        Intent settings = new Intent(Settings.ACTION_SETTINGS);
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return startIntentSafe(settings);
    }

    private boolean startIntentSafe(Intent intent) {
        try {
            if (intent.resolveActivity(getPackageManager()) == null) return false;
            startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startIntent: " + e.getMessage());
            return false;
        }
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

    @Override
    public void onBackPressed() {
        // block back while locked
        if (allowExit) super.onBackPressed();
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
                        Log.e(TAG, "Failed to load dealer UPI: " + e.getMessage()));
    }

    private void generateQRCode(String upiId) {
        try {
            String upiUrl = upiId.startsWith("upi://")
                    ? upiId
                    : "upi://pay?pa=" + upiId + "&pn=EMI%20Payment&cu=INR";
            MultiFormatWriter writer = new MultiFormatWriter();
            BitMatrix matrix = writer.encode(upiUrl, BarcodeFormat.QR_CODE, 400, 400);
            BarcodeEncoder encoder = new BarcodeEncoder();
            if (qrCodeImage != null) {
                qrCodeImage.setImageBitmap(encoder.createBitmap(matrix));
            }
        } catch (Exception e) {
            Log.e(TAG, "QR: " + e.getMessage());
        }
    }

    private void unlockAndClose() {
        allowExit = true;
        clearWifiSession();
        markLockVisible(false);
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                stopLockTask();
            }
        } catch (Exception ignored) {
        }
        if (listener != null) {
            listener.remove();
            listener = null;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (listener != null) {
            listener.remove();
            listener = null;
        }
        markLockVisible(false);
        super.onDestroy();
    }
   }
