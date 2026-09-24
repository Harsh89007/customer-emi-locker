package com.harshit.emilocker.customer;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.FirebaseFirestore;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
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

        checkDeviceStatusAndProceed();
    }

    private void checkDeviceStatusAndProceed() {
        boolean isDeviceOwner = dpm.isDeviceOwnerApp(getPackageName());

        if (isDeviceOwner) {
            // नया फोन है और Test DPC से ओनरशिप मिल चुकी है -> स्कैनर चालू करो
            startService(new Intent(this, LockListenerService.class));
            startQRScanner();
        } else {
            // ओनरशिप नहीं है, अब चेक करेंगे कि फोन रनिंग है या रिसेट
            if (isRunningDevice()) {
                // रनिंग फोन है -> परमिशन मांगो
                requestRunningDevicePermissions();
            } else {
                // नया फोन है लेकिन ओनरशिप नहीं मिली -> ऐप बंद कर दो
                Toast.makeText(this, "यह नया डिवाइस है! कृपया पहले Test DPC से ओनरशिप ट्रांसफर करें।", Toast.LENGTH_LONG).show();
                finish(); 
            }
        }
    }

    private boolean isRunningDevice() {
        try {
            AccountManager accountManager = AccountManager.get(this);
            Account[] accounts = accountManager.getAccounts();
            return accounts.length > 0;
        } catch (SecurityException e) {
            return false;
        }
    }

    private void requestRunningDevicePermissions() {
        boolean allPermissionsGranted = true;

        if (!dpm.isAdminActive(adminComponent)) {
            allPermissionsGranted = false;
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "कृपया EMI सिक्योरिटी एक्टिवेट करें।");
            startActivity(intent);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            allPermissionsGranted = false;
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this, "कृपया 'Allow display over other apps' चालू करें", Toast.LENGTH_LONG).show();
        }

        if (allPermissionsGranted) {
            startService(new Intent(this, LockListenerService.class));
            startQRScanner();
        }
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
                String sessionId = result.getContents();
                String deviceModel = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
                boolean isFullySecure = dpm.isDeviceOwnerApp(getPackageName()); 

                Map<String, Object> data = new HashMap<>();
                data.put("model", deviceModel);
                data.put("is_locked", false);
                data.put("security_level", isFullySecure ? "Device Owner (100%)" : "Admin + Overlay (Running)");

                db.collection("Customers").document(sessionId).set(data)
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "Device Registered Successfully!", Toast.LENGTH_LONG).show();
                        finish();
                    }).addOnFailureListener(e -> {
                        Toast.makeText(this, "Registration Failed!", Toast.LENGTH_SHORT).show();
                        finish();
                    });
            } else {
                Toast.makeText(this, "Scan Cancelled", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
}
