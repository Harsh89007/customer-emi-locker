package com.harshit.emilocker.customer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;

public class LockListenerService extends Service {
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private String mySessionId;
    private SharedPreferences prefs;
    
    // ओवरले के लिए वेरिएबल्स
    private WindowManager windowManager;
    private View lockViewOverlay;
    private boolean isOverlayShowing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 1. VIP नोटिफिकेशन बनाना (ताकि Android इसे कभी Kill न करे)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("EMI_VIP", "System Service", NotificationManager.IMPORTANCE_HIGH);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);

            Notification notification = new Notification.Builder(this, "EMI_VIP")
                .setContentTitle("System Settings")
                .setContentText("Service is running normally...")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setOngoing(true) // इसे कस्टमर हटा नहीं पाएगा
                .build();
            startForeground(1001, notification);
        }

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        mySessionId = prefs.getString("session_id", null);
        
        if (mySessionId == null) {
            stopSelf();
            return;
        }

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyAdminReceiver.class);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // 2. फोन चालू होते ही ऑफलाइन मेमोरी चेक करो
        boolean wasLocked = prefs.getBoolean("is_offline_locked", false);
        if (wasLocked) {
            showUnbreakableLock();
        }
        
        // 3. Firebase से रियल-टाइम अपडेट (Offline-to-Online Fix के साथ)
        FirebaseFirestore.getInstance().collection("Customers").document(mySessionId)
            .addSnapshotListener((snapshot, e) -> {
                if (e != null) {
                    return; // अगर नेटवर्क एरर हो तो कुछ मत करो, ऑफलाइन लॉक काम करता रहेगा
                }
                
                if (snapshot != null) {
                    if (snapshot.exists()) {
                        // अगर डेटाबेस में कस्टमर मौजूद है
                        Boolean isReleased = snapshot.getBoolean("is_released");
                        if (isReleased != null && isReleased) {
                            releaseOwnershipAndUninstall();
                            return;
                        }

                        Boolean isLocked = snapshot.getBoolean("is_locked");
                        if (isLocked != null && isLocked) {
                            prefs.edit().putBoolean("is_offline_locked", true).apply();
                            showUnbreakableLock();
                        } else {
                            prefs.edit().putBoolean("is_offline_locked", false).apply();
                            hideLock();
                        }
                    } else {
                        // सबसे ज़रूरी नया लॉजिक: 
                        // अगर Firebase से कस्टमर का डेटा डिलीट कर दिया गया है (Snapshot does not exist)
                        // तो ऐप अपने-आप को तुरंत रिलीज़ करके हटा लेगा।
                        releaseOwnershipAndUninstall();
                    }
                }
            });
    }

    // यह फंक्शन स्क्रीन पर पक्का लॉक लगाता है (बिना Activity के)
    private void showUnbreakableLock() {
        if (isOverlayShowing || windowManager == null) return;

        try {
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            lockViewOverlay = inflater.inflate(R.layout.activity_lock, null);

            ImageView qrCodeImage = lockViewOverlay.findViewById(R.id.qr_code_image);
            FirebaseFirestore.getInstance().collection("AdminSettings").document("PaymentInfo")
                .get().addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        String adminUpi = documentSnapshot.getString("upi_id");
                        if (adminUpi != null && !adminUpi.isEmpty()) {
                            try {
                                String upiUrl = "upi://pay?pa=" + adminUpi + "&pn=EMI_Payment&cu=INR";
                                MultiFormatWriter writer = new MultiFormatWriter();
                                BitMatrix matrix = writer.encode(upiUrl, BarcodeFormat.QR_CODE, 400, 400);
                                BarcodeEncoder encoder = new BarcodeEncoder();
                                Bitmap bitmap = encoder.createBitmap(matrix);
                                qrCodeImage.setImageBitmap(bitmap);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });

            int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                    WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);

            windowManager.addView(lockViewOverlay, params);
            isOverlayShowing = true;
            
            // अगर Device Owner है तो Kiosk Mode भी लगा दो
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                Intent lockIntent = new Intent(this, LockActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(lockIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void hideLock() {
        if (isOverlayShowing && lockViewOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(lockViewOverlay);
            } catch (Exception ignored) {}
            isOverlayShowing = false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; 
    }

    private void releaseOwnershipAndUninstall() {
        hideLock();
        prefs.edit().putBoolean("is_offline_locked", false).apply();
        Toast.makeText(this, "Device Released! Uninstalling...", Toast.LENGTH_LONG).show();
        
        try {
            if (dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.clearDeviceOwnerApp(getPackageName());
            }
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent);
            }
        } catch (Exception ignored) {}

        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
