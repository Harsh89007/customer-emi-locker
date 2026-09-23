package com.harshit.emilocker.customer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.google.firebase.firestore.FirebaseFirestore;

public class LockListenerService extends Service {
    private FirebaseFirestore db;
    
    // यह सेशन आईडी वही होनी चाहिए जो स्कैन की गई थी (बाद में हम इसे लोकल स्टोरेज से उठाएंगे)
    private String myDocumentId = "SESSION_12345"; 

    @Override
    public void onCreate() {
        super.onCreate();
        db = FirebaseFirestore.getInstance();
        
        // Firebase पर रियल-टाइम नज़र रखना
        db.collection("Customers").document(myDocumentId)
            .addSnapshotListener((snapshot, e) -> {
                if (snapshot != null && snapshot.exists()) {
                    Boolean isLocked = snapshot.getBoolean("is_locked");
                    if (isLocked != null && isLocked) {
                        // अगर डीलर ने true किया, तो तुरंत लॉक स्क्रीन खोल दो
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
