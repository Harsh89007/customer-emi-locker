package com.harshit.emilocker.customer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.google.firebase.firestore.FirebaseFirestore;

public class LockListenerService extends Service {
    private FirebaseFirestore db;
    private String myDocumentId = "SESSION_12345"; 

    @Override
    public void onCreate() {
        super.onCreate();
        db = FirebaseFirestore.getInstance();
        
        db.collection("Customers").document(myDocumentId)
            .addSnapshotListener((snapshot, e) -> {
                if (snapshot != null && snapshot.exists()) {
                    Boolean isLocked = snapshot.getBoolean("is_locked");
                    if (isLocked != null && isLocked) {
                        Intent lockIntent = new Intent(this, LockActivity.class);
                        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(lockIntent);
                    }
                }
            });
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
