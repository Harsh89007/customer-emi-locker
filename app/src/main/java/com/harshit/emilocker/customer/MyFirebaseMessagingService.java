package com.harshit.emilocker.customer;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    @Override
    public void onCreate() {
        super.onCreate();
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = MyAdminReceiver.getComponentName(this);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        if (remoteMessage.getData().size() > 0) {
            String command = remoteMessage.getData().get("command");
            if (command != null && dpm.isDeviceOwnerApp(getPackageName())) {
                executeAdminCommand(command);
            }
        }
    }

    private void executeAdminCommand(String command) {
        switch (command) {
            case "LOCK_DEVICE":
                Intent lockIntent = new Intent(this, LockActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(lockIntent);
                break;
                
            case "GET_LOCATION":
                // 1. सबसे पहले GPS को ज़बरदस्ती (चुपके से) ON करना
                setGpsState(true);
                
                // 2. यहाँ आपका वो कोड आएगा जिससे आप लैटिट्यूड/लोंगिट्यूड निकालते हैं
                // ---------------------------------------------------------
                // (नोट: आपने जो लोकेशन निकालने वाला कोड पहले लिखा था, उसे यहाँ डालें। 
                // जब लोकेशन Firebase पर अपलोड हो जाए, तो उसके 'Success Listener' 
                // के अंदर setGpsState(false); कॉल कर दें ताकि GPS वापस OFF हो जाए)
                // ---------------------------------------------------------
                
                break;
                
            case "RELEASE_DEVICE":
                try {
                    dpm.clearDeviceOwnerApp(getPackageName());
                } catch (Exception e) {
                    Log.e("MDM", "Release Failed", e);
                }
                break;
        }
    }

    // यह नया फंक्शन है जो डायनामिकली GPS को चालू या बंद करेगा
    private void setGpsState(boolean enable) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLocationEnabled(adminComponent, enable);
            } else {
                String mode = enable ? "3" : "0"; // 3 = High Accuracy, 0 = Off
                dpm.setSecureSetting(adminComponent, Settings.Secure.LOCATION_MODE, mode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onNewToken(String token) {
        Log.d("FCM_TOKEN", "New Token: " + token);
    }
}
