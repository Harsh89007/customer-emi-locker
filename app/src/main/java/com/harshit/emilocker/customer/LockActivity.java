package com.harshit.emilocker.customer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import com.google.firebase.firestore.FirebaseFirestore;

public class LockActivity extends Activity {
    private FirebaseFirestore db;
    private String myDocumentId = "SESSION_12345";
    private DevicePolicyManager dpm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);
        
        db = FirebaseFirestore.getInstance();
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

        // सिर्फ डिवाइस ओनर होने पर ही Kiosk Mode चालू करें, वरना क्रैश हो जाएगा
        try {
            if (dpm.isDeviceOwnerApp(getPackageName())) {
                startLockTask(); 
            }
        } catch (Exception e) {
            // रनिंग डिवाइस में क्रैश से बचने के लिए इसे इग्नोर किया गया है
        }

        Button checkStatusBtn = findViewById(R.id.btn_check_status);
        checkStatusBtn.setOnClickListener(v -> {
            Toast.makeText(this, "पेमेंट स्टेटस चेक कर रहे हैं...", Toast.LENGTH_SHORT).show();
            
            db.collection("Customers").document(myDocumentId).get()
                .addOnSuccessListener(document -> {
                    if (document.exists()) {
                        Boolean isLocked = document.getBoolean("is_locked");
                        if (isLocked != null && !isLocked) {
                            try {
                                if (dpm.isDeviceOwnerApp(getPackageName())) {
                                    stopLockTask();
                                }
                            } catch (Exception e) {}
                            finish();
                        } else {
                            Toast.makeText(this, "अभी तक पेमेंट अपडेट नहीं हुआ है!", Toast.LENGTH_LONG).show();
                        }
                    }
                }).addOnFailureListener(e -> {
                    Toast.makeText(this, "एरर: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
        });
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, "कृपया पहले EMI जमा करें", Toast.LENGTH_SHORT).show();
    }
}
