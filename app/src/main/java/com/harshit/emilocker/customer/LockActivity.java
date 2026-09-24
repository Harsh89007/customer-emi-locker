package com.harshit.emilocker.customer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import com.google.firebase.firestore.FirebaseFirestore;

public class LockActivity extends Activity {
    private FirebaseFirestore db;
    private String myDocumentId = "SESSION_12345";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);
        db = FirebaseFirestore.getInstance();

        // Kiosk Mode (स्क्रीन लॉक) शुरू करें
        startLockTask(); 

        Button checkStatusBtn = findViewById(R.id.btn_check_status);
        checkStatusBtn.setOnClickListener(v -> {
            Toast.makeText(this, "पेमेंट स्टेटस चेक कर रहे हैं...", Toast.LENGTH_SHORT).show();
            
            db.collection("Customers").document(myDocumentId).get()
                .addOnSuccessListener(document -> {
                    if (document.exists()) {
                        Boolean isLocked = document.getBoolean("is_locked");
                        if (isLocked != null && !isLocked) {
                            stopLockTask();
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
