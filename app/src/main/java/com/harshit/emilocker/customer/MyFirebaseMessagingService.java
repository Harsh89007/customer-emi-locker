package com.harshit.emilocker.customer;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
                // कस्टमर का फोन लॉक करने के लिए LockScreenActivity को कॉल करें
                Intent lockIntent = new Intent(this, LockScreenActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(lockIntent);
                break;
                
            case "GET_LOCATION":
                // क्योंकि अब GPS ज़बरदस्ती ON है, आप यहाँ से लोकेशन निकालकर Firebase पर भेज सकते हैं
                break;
                
            case "RELEASE_DEVICE":
                // EMI पूरी होने पर ऐप से एडमिन पावर हमेशा के लिए हटाना
                try {
                    dpm.clearDeviceOwnerApp(getPackageName());
                } catch (Exception e) {
                    Log.e("MDM", "Release Failed", e);
                }
                break;
        }
    }

    @Override
    public void onNewToken(String token) {
        Log.d("FCM_TOKEN", "New Token: " + token);
        // यह टोकन आपके सर्वर (डीलर पैनल) पर सेव होगा
    }
}

