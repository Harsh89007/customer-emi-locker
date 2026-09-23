package com.harshit.emilocker.customer;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.FirebaseFirestore;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = FirebaseFirestore.getInstance();

        // बैकग्राउंड सर्विस स्टार्ट करें जो हमेशा लॉक/अनलॉक चेक करेगी
        startService(new Intent(this, LockListenerService.class));

        // QR स्कैनर खोलें
        ScanOptions options = new ScanOptions();
        options.setPrompt("Scan Dealer QR");
        options.setBeepEnabled(true);
        barcodeLauncher.launch(options);
    }

    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> barcodeLauncher = 
        registerForActivityResult(new ScanContract(), result -> {
            if(result.getContents() != null) {
                String sessionId = result.getContents();
                String deviceModel = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
                
                Map<String, Object> data = new HashMap<>();
                data.put("model", deviceModel);
                data.put("is_locked", false); // शुरू में फोन अनलॉक रहेगा

                db.collection("Customers").document(sessionId).set(data)
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "Device Registered!", Toast.LENGTH_LONG).show();
                        finish(); // ऐप बंद कर दें, सर्विस बैकग्राउंड में काम करेगी
                    });
            }
        });
}
