package com.harshit.emilocker.customer;

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

            // यह चेक करता है कि फोन सेटअप विज़ार्ड में है (नया) या चालू फोन है
            int setupComplete = Settings.Secure.getInt(getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 0);
            
            if (setupComplete == 0) {
                // नया फोन है, ऐप गलती से खुल गया
                Toast.makeText(this, "कृपया पहले Test DPC से ओनरशिप लें!", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            // --- रनिंग डिवाइस का सेफ परमिशन लॉजिक (बिना क्रैश के) ---
            if (!dpm.isAdminActive(adminComponent)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "कृपया EMI सिक्योरिटी एक्टिवेट करें।");
                startActivity(intent);
                finish(); // क्रैश से बचने के लिए ऐप बंद कर दो, यूजर खुद दोबारा खोलेगा
                Toast.makeText(this, "एक्टिवेट करने के बाद ऐप दोबारा खोलें", Toast.LENGTH_LONG).show();
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                finish(); // क्रैश से बचने के लिए ऐप बंद कर दो
                Toast.makeText(this, "Allow display over other apps चालू करके ऐप दोबारा खोलें", Toast.LENGTH_LONG).show();
                return;
            }

            // अगर दोनों परमिशन मिल गईं
            startService(new Intent(this, LockListenerService.class));
            startQRScanner();

        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
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
                data.put("is_released", false);
                data.put("security_level", isFullySecure ? "Device Owner (100%)" : "Admin + Overlay (Running)");

                db.collection("Customers").document(sessionId).set(data)
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "Device Registered!", Toast.LENGTH_LONG).show();
                        finish();
                    }).addOnFailureListener(e -> {
                        Toast.makeText(this, "Failed!", Toast.LENGTH_SHORT).show();
                        finish();
                    });
            } else {
                finish();
            }
        });
}
