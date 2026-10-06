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

/**
 * Lock screen — MUST use same prefs as LockListenerService + MainActivity:
 * DeviceProtectedStorage + "AppPrefs" + key "session_id"
 *
 * Fixes:
 * - Offline code: any of 6 dealer codes from AppPrefs unlock_codes_display
 * - Dealer unlock: force_unlock via onCreate + onNewIntent
 * - Firestore is_locked=false listener unlock
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
    private String sessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // SAME prefs as LockListenerService / MainActivity (critical!)
        Context deviceContext = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                deviceContext = createDeviceProtectedStorageContext();
            } catch (Exception ignored) {
            }
        }
        prefs = deviceContext.getSharedPreferences("AppPrefs", MODE_PRIVATE);
        sessionId = prefs.getString("session_id", null);

        if (handleForceUnlock(getIntent())) {
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

        // Live Firestore: unlock when dealer presses Unlock + refresh codes
        if (sessionId != null && !sessionId.isEmpty()) {
            listener = FirebaseFirestore.getInstance()
                    .collection("Customers")
                    .document(sessionId)
                    .addSnapshotListener((snapshot, error) -> {
                        if (error != null || snapshot == null || !snapshot.exists()) return;
                        try {
                            Boolean locked = snapshot.getBoolean("is_locked");
                            if (locked != null && !locked) {
                                // Dealer unlocked from panel
                                performFullUnlock();
                                return;
                            }
                            Object codes = snapshot.get("unlock_codes");
                            if (codes instanceof List) {
                                cacheCodesFromList((List<?>) codes);
                            }
                            String dealerUid = snapshot.getString("dealer_uid");
                            if (dealerUid == null) {
                                dealerUid = snapshot.getString("dealerId");
                            }
                            if (dealerUid != null && !dealerUid.isEmpty()) {
                                fetchDealerQRCode(dealerUid);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "listener: " + e.getMessage());
                        }
                    });
        } else {
            Log.e(TAG, "session_id missing — offline codes / dealer unlock limited");
        }

        // UPI from local dealer_uid if any
        String dealerUid = prefs.getString("dealer_uid", null);
        if (dealerUid != null && !dealerUid.isEmpty()) {
            fetchDealerQRCode(dealerUid);
        }

        markLockVisible(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleForceUnlock(intent);
    }

    /** Returns true if activity should finish (force unlock). */
    private boolean handleForceUnlock(Intent intent) {
        if (intent != null && intent.getBooleanExtra("force_unlock", false)) {
            allowExit = true;
            clearWifiSession();
            markLockVisible(false);
            try {
                DevicePolicyManager dpmLocal =
                        (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (dpmLocal != null && dpmLocal.isDeviceOwnerApp(getPackageName())) {
                    stopLockTask();
                }
            } catch (Exception ignored) {
            }
            finish();
            return true;
        }
        return false;
    }

    private void loadValidCodesFromPrefs() {
        validCodes.clear();
        String local = prefs.getString("unlock_codes_display", null);
        if (local == null || local.trim().isEmpty()) return;
        for (String part : local.replace("\n", " ").split("\\s+")) {
            String c = part.trim();
            if (c.length() >= 4) validCodes.add(c);
        }
        Log.d(TAG, "Loaded " + validCodes.size() + " offline codes from prefs");
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
            if (i > 0) display.append(i % 3 == 0 ? "\n" : "   ");
            display.append(c);
            i++;
        }
        if (display.length() > 0) {
            prefs.edit().putString("unlock_codes_display", display.toString()).apply();
        }
        Log.d(TAG, "Cached " + validCodes.size() + " codes from Firestore");
    }

    /**
     * Customer enters ANY ONE of the 6 codes shown on dealer panel.
     */
    private void tryOfflineUnlock() {
        if (etUnlockCode == null) return;
        String input = etUnlockCode.getText() != null
                ? etUnlockCode.getText().toString().trim() : "";
        if (input.isEmpty()) {
            Toast.makeText(this, "Code dalo", Toast.LENGTH_SHORT).show();
            return;
        }

        // Always refresh from prefs (same AppPrefs as listener)
        loadValidCodesFromPrefs();

        // If still empty, try one-shot Firestore fetch
        if (validCodes.isEmpty() && sessionId != null) {
            try {
                FirebaseFirestore.getInstance().collection("Customers").document(sessionId)
                        .get()
                        .addOnSuccessListener(snap -> {
                            if (snap != null && snap.exists()) {
                                Object codes = snap.get("unlock_codes");
                                if (codes instanceof List) {
                                    cacheCodesFromList((List<?>) codes);
                                }
                            }
                            verifyAndUnlock(input);
                        })
                        .addOnFailureListener(e -> verifyAndUnlock(input));
                return;
            } catch (Exception e) {
                Log.e(TAG, "fetch codes: " + e.getMessage());
            }
        }

        verifyAndUnlock(input);
    }

    private void verifyAndUnlock(String input) {
        boolean match = false;
        for (String c : validCodes) {
            if (c.equals(input) || c.replace(" ", "").equals(input)) {
                match = true;
                break;
            }
        }

        if (!match) {
            Toast.makeText(this,
                    "Galat code (dealer panel ke 6 codes me se koi ek dalo)",
                    Toast.LENGTH_LONG).show();
            if (etUnlockCode != null) etUnlockCode.setText("");
            return;
        }

        Toast.makeText(this, "Code sahi — Unlocking...", Toast.LENGTH_SHORT).show();
        performFullUnlock();
    }

    /** Full unlock: prefs + Firestore + stop kiosk + finish */
    private void performFullUnlock() {
        if (allowExit) return;
        allowExit = true;

        prefs.edit()
                .putBoolean("is_offline_locked", false)
                .putLong("wifi_session_until", 0L)
                .remove("unlock_codes_display")
                .apply();

        if (sessionId != null && !sessionId.isEmpty()) {
            try {
                FirebaseFirestore.getInstance()
                        .collection("Customers")
                        .document(sessionId)
                        .update("is_locked", false);
            } catch (Exception ignored) {
            }
        }

        markLockVisible(false);
        clearWifiSession();

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
                        "com.android.systemui",
                        "com.google.android.permissioncontroller",
                        "com.android.permissioncontroller",
                        "com.android.phone"
                });
                startLockTask();
            }
        } catch (Exception e) {
            Log.e(TAG, "kiosk settings: " + e.getMessage());
        }
    }

    private void openWifiSafely() {
        startWifiSession();

        // 1) Temporarily leave pure kiosk so Settings can open (Device Owner requirement)
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
                        "com.android.phone",
                        "com.android.networkstack.tethering",
                        "com.google.android.permissioncontroller",
                        "com.android.permissioncontroller"
                });
                try {
                    stopLockTask(); // critical: allow leaving lock task to open Wi-Fi UI
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "pre-wifi kiosk: " + e.getMessage());
        }

        handler.postDelayed(() -> {
            boolean opened = tryOpenWifiUi();
            if (!opened) {
                clearWifiSession();
                enterKioskStrict();
                Toast.makeText(this,
                        "Wi-Fi settings nahi khuli. Settings se manually try karein.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this,
                    "Wi-Fi jodo (60 sec). Wapas aane par lock lagega.",
                    Toast.LENGTH_LONG).show();
            // Soft re-enter kiosk WITH settings allowed so user can stay in Wi-Fi screen
            enterKioskWithSettings();
        }, 400);

        handler.postDelayed(() -> {
            if (!allowExit && isStillLocked()) {
                clearWifiSession();
                enterKioskStrict();
                bringLockBack();
            }
        }, WIFI_SESSION_MS);
    }

    private boolean tryOpenWifiUi() {
        // Prefer full Wi-Fi settings (more reliable under DO than Panel on many OEMs)
        Intent wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
        wifi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_HISTORY);
        if (startIntentSafe(wifi)) return true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (startIntentSafe(new Intent(Settings.Panel.ACTION_WIFI)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return true;
            if (startIntentSafe(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return true;
        }

        Intent wireless = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        wireless.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (startIntentSafe(wireless)) return true;

        Intent settings = new Intent(Settings.ACTION_SETTINGS);
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return startIntentSafe(settings);
    }

    private boolean startIntentSafe(Intent intent) {
        try {
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
        if (allowExit) return;
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
        if (allowExit) return;
        markLockVisible(false);
        if (isWifiSessionActive()) return;
        if (isStillLocked()) {
            handler.postDelayed(() -> {
                if (!allowExit && isStillLocked() && !isWifiSessionActive()) {
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
                if (!allowExit && isStillLocked() && !isWifiSessionActive()) {
                    bringLockBack();
                }
            }, 2000);
        }
    }

    @Override
    public void onBackPressed() {
        if (allowExit) {
            super.onBackPressed();
        }
    }

    private void fetchDealerQRCode(String dealerUid) {
        if (dealerUid == null || dealerUid.isEmpty()) {
            tryAdminPaymentQr();
            return;
        }
        // Cache for next open
        prefs.edit().putString("dealer_uid", dealerUid).apply();

        FirebaseFirestore.getInstance().collection("Dealers").document(dealerUid)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot != null && documentSnapshot.exists()) {
                        String dealerUpi = documentSnapshot.getString("upi_id");
                        if (dealerUpi == null || dealerUpi.isEmpty()) {
                            dealerUpi = documentSnapshot.getString("upi");
                        }
                        if (dealerUpi != null && !dealerUpi.isEmpty()) {
                            generateQRCode(dealerUpi);
                            return;
                        }
                    }
                    // Dealer doc me UPI nahi → admin fallback
                    tryAdminPaymentQr();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to load dealer UPI: " + e.getMessage());
                    tryAdminPaymentQr();
                });
    }

    /** Fallback: AdminSettings/PaymentInfo (old path used by overlay) */
    private void tryAdminPaymentQr() {
        FirebaseFirestore.getInstance().collection("AdminSettings").document("PaymentInfo")
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc != null && doc.exists()) {
                        String upi = doc.getString("upi_id");
                        if (upi == null || upi.isEmpty()) upi = doc.getString("upi");
                        if (upi != null && !upi.isEmpty()) {
                            generateQRCode(upi);
                        } else {
                            Log.e(TAG, "No upi_id in AdminSettings/PaymentInfo");
                        }
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Admin UPI fail: " + e.getMessage()));
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

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (listener != null) {
            listener.remove();
            listener = null;
        }
        if (!allowExit) {
            markLockVisible(false);
        }
        super.onDestroy();
    }
}
