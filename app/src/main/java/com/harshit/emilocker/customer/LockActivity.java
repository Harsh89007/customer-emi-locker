package com.harshit.emilocker.customer;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.FirebaseFirestore;

public class LockActivity extends AppCompatActivity {
    private FirebaseFirestore db;
    private String myDocumentId = "SESSION_12345";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);
        db = FirebaseFirestore.getInstance();

        // Kiosk Mode चालू करें (यह स्क्रीन को पिन कर देगा, कोई बैक या होम बटन काम नहीं करेगा)
        startLockTask();

        Button checkStatusBtn = findViewById(R.id.btn_check_status);
        checkStatusBtn.setOnClickListener(v -> {
            Toast.makeText(this, "पेमेंट स्टेटस चेक कर रहे हैं...", Toast.LENGTH_SHORT).show();
            
            // Firebase में दोबारा चेक करें कि क्या डीलर ने अनलॉक कर दिया
            db.collection("Customers").document(myDocumentId).get()
                .addOnSuccessListener(document -> {
                    Boolean isLocked = document.getBoolean("is_locked");
                    if (isLocked != null && !isLocked) {
                        // डीलर ने अनलॉक कर दिया है
                        stopLockTask(); // Kiosk Mode बंद
                        finish(); // लॉक स्क्रीन खत्म
                    } else {
                        Toast.makeText(this, "अभी तक पेमेंट अपडेट नहीं हुआ है!", Toast.LENGTH_LONG).show();
                    }
                });
        });
    }

    // बैक बटन को पूरी तरह डिसेबल कर दें
    @Override
    public void onBackPressed() {
        // यहाँ कुछ नहीं लिखना है, ताकि बैक बटन काम ही न करे
        Toast.makeText(this, "कृपया पहले EMI जमा करें", Toast.LENGTH_SHORT).show();
    }
}
