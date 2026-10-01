package com.harshit.emilocker.customer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.FactoryResetProtectionPolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class LockListenerService extends Service {
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private String mySessionId;
    private SharedPreferences prefs;

    private WindowManager windowManager;
    private View lockViewOverlay;
    private boolean isOverlayShowing = false;

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "EMI_VIP", "System Service", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) manager.createNotificationChannel(channel);

                Notification notification = new Notification.Builder(this, "EMI_VIP")
                        .setContentTitle("System Settings")
                        .setContentText("Service is running normally...")
                        .setSmallIcon(android.R.drawable.ic_secure)
                        .setOngoing(true)
                        .build();
                startForeground(1001, notification);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            Context deviceContext = this;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                deviceContext = createDeviceProtectedStorageContext();
                try {
                    deviceContext.moveSharedPreferencesFrom(this, "AppPrefs");
                } catch (Exception ignored) {
                }
            }

            prefs = deviceContext.getSharedPreferences("AppPrefs", MODE_PRIVATE);
            mySessionId = prefs.getString("session_id", null);

            if (mySessionId == null || mySessionId.trim().isEmpty()) {
                stopSelf();
                return;
            }

            dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            adminComponent = new ComponentName(this, MyAdminReceiver.class);
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    dpm.setPermissionGrantState(adminComponent, getPackageName(),
                            android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                } catch (Exception ignored) {
                }
                try {
                    dpm.setPermissionGrantState(adminComponent, getPackageName(),
                            android.Manifest.permission.READ_PHONE_STATE,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                } catch (Exception ignored) {
                }
                try {
                    dpm.setPermissionGrantState(adminComponent, getPackageName(),
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    dpm.setPermissionGrantState(adminComponent, getPackageName(),
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                } catch (Exception ignored) {
                }
            }

            boolean wasLocked = prefs.getBoolean("is_offline_locked", false);
            if (wasLocked) {
                showUnbreakableLock();
            }

            FirebaseFirestore.getInstance().collection("Customers").document(mySessionId)
                    .addSnapshotListener((snapshot, e) -> {
                        if (e != null || snapshot == null) return;
                        try {
                            if (!snapshot.exists()) {
                                releaseOwnershipAndUninstall();
                                return;
                            }

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

                            String pendingCommand = snapshot.getString("pending_command");
                            String commandId = snapshot.getString("command_id");
                            String lastProcessedId = prefs.getString("last_command_id", "");

                            if (pendingCommand != null
                                    && !pendingCommand.isEmpty()
                                    && !"none".equals(pendingCommand)
                                    && commandId != null
                                    && !commandId.equals(lastProcessedId)) {
                                prefs.edit().putString("last_command_id", commandId).apply();
                                processCommand(pendingCommand, commandId, snapshot);
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processCommand(String command, String commandId,
                                com.google.firebase.firestore.DocumentSnapshot snapshot) {
        Map<String, Object> params = null;
        try {
            Object p = snapshot.get("command_params");
            if (p instanceof Map) {
                //noinspection unchecked
                params = (Map<String, Object>) p;
            }
        } catch (Exception ignored) {
        }

        switch (command) {
            case "get_location":
                handleGetLocation(commandId);
                break;
            case "get_phone_number":
                handleGetPhoneNumber(commandId);
                break;
            case "set_wallpaper":
                handleSetWallpaper(commandId, params);
                break;
            case "reset_wallpaper":
                handleResetWallpaper(commandId);
                break;
            case "set_password":
                handleSetPassword(commandId, params);
                break;
            case "remove_password":
                handleRemovePassword(commandId);
                break;
            case "play_warning":
                handlePlayWarning(commandId);
                break;
            case "auto_lock_on":
                prefs.edit().putBoolean("auto_lock_enabled", true).apply();
                writeCommandResult(commandId, "success", "Auto Lock ON");
                break;
            case "auto_lock_off":
                prefs.edit().putBoolean("auto_lock_enabled", false).apply();
                writeCommandResult(commandId, "success", "Auto Lock OFF");
                break;
            case "sim_lock_on":
                prefs.edit().putBoolean("sim_lock_enabled", true).apply();
                writeCommandResult(commandId, "success", "SIM Change Lock ON");
                break;
            case "sim_lock_off":
                prefs.edit().putBoolean("sim_lock_enabled", false).apply();
                writeCommandResult(commandId, "success", "SIM Change Lock OFF");
                break;
            case "set_frp_account":
                handleSetFrpAccount(commandId, params);
                break;
            default:
                writeCommandResult(commandId, "error", "Unknown command: " + command);
                break;
        }
    }

    private void writeCommandResult(String commandId, String status, String message) {
        writeCommandResult(commandId, status, message, null);
    }

    private void writeCommandResult(String commandId, String status, String message,
                                    Map<String, Object> extra) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("message", message);
        result.put("command_id", commandId);
        result.put("timestamp", System.currentTimeMillis());
        if (extra != null) result.putAll(extra);

        Map<String, Object> update = new HashMap<>();
        update.put("command_result", result);
        update.put("pending_command", "none");

        FirebaseFirestore.getInstance().collection("Customers").document(mySessionId)
                .update(update)
                .addOnFailureListener(e -> e.printStackTrace());
    }

    /** FRP — Device Owner + Android 11+ */
    private void handleSetFrpAccount(String commandId, Map<String, Object> params) {
        if (params == null) {
            writeCommandResult(commandId, "error", "params missing");
            return;
        }
        String accountId = params.get("account_id") != null
                ? String.valueOf(params.get("account_id")).trim() : "";
        String email = params.get("email") != null
                ? String.valueOf(params.get("email")).trim() : "";

        if (accountId.isEmpty() && email.isEmpty()) {
            writeCommandResult(commandId, "error", "account_id or email required");
            return;
        }

        try {
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                writeCommandResult(commandId, "error",
                        "Not Device Owner — FRP only on owned phones");
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                writeCommandResult(commandId, "error", "FRP policy needs Android 11+");
                return;
            }

            ArrayList<String> accounts = new ArrayList<>();
            if (!accountId.isEmpty()) {
                accounts.add(accountId);
            } else {
                accounts.add(email);
            }

            FactoryResetProtectionPolicy policy =
                    new FactoryResetProtectionPolicy.Builder()
                            .setFactoryResetProtectionAccounts(accounts)
                            .setFactoryResetProtectionEnabled(true)
                            .build();
            dpm.setFactoryResetProtectionPolicy(adminComponent, policy);

            Intent frp = new Intent("com.google.android.gms.auth.FRP_CONFIG_CHANGED");
            frp.setPackage("com.google.android.gms");
            frp.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            sendBroadcast(frp);

            try {
                Bundle bundle = new Bundle();
                bundle.putStringArray("factoryResetProtectionAdmin",
                        accounts.toArray(new String[0]));
                dpm.setApplicationRestrictions(adminComponent,
                        "com.google.android.gms", bundle);
            } catch (Exception ignored) {
            }

            prefs.edit()
                    .putString("frp_account_id", accountId)
                    .putString("frp_email", email)
                    .apply();

            writeCommandResult(commandId, "success",
                    "FRP set for: " + (!accountId.isEmpty() ? accountId : email));
        } catch (Exception e) {
            writeCommandResult(commandId, "error",
                    e.getMessage() != null ? e.getMessage() : "FRP failed");
        }
    }

    private void handleGetLocation(String commandId) {
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    dpm.setPermissionGrantState(adminComponent, getPackageName(),
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    dpm.setPermissionGrantState(adminComponent, getPackageName(),
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        dpm.setPermissionGrantState(adminComponent, getPackageName(),
                                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    }
                } catch (Exception ignored) {
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        dpm.setLocationEnabled(adminComponent, true);
                    } catch (Exception ignored) {
                    }
                }
            }

            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) {
                writeCommandResult(commandId, "error", "LocationManager not available");
                return;
            }

            Location loc = null;
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }
            } catch (Exception ignored) {
            }
            try {
                if (loc == null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
            } catch (Exception ignored) {
            }
            try {
                if (loc == null && lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                    loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                }
            } catch (Exception ignored) {
            }

            if (loc != null && (System.currentTimeMillis() - loc.getTime()) < 2 * 60 * 1000) {
                Map<String, Object> extra = new HashMap<>();
                extra.put("latitude", loc.getLatitude());
                extra.put("longitude", loc.getLongitude());
                extra.put("accuracy", loc.getAccuracy());
                writeCommandResult(commandId, "success", "Location fetched", extra);
                return;
            }

            final boolean[] done = {false};
            final Handler timeoutHandler = new Handler(Looper.getMainLooper());

            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (done[0]) return;
                    done[0] = true;
                    try {
                        lm.removeUpdates(this);
                    } catch (Exception ignored) {
                    }
                    timeoutHandler.removeCallbacksAndMessages(null);

                    Map<String, Object> extra = new HashMap<>();
                    extra.put("latitude", location.getLatitude());
                    extra.put("longitude", location.getLongitude());
                    extra.put("accuracy", location.getAccuracy());
                    writeCommandResult(commandId, "success", "Location fetched", extra);
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };

            boolean requested = false;
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                            0, 0, listener, Looper.getMainLooper());
                    requested = true;
                }
            } catch (Exception ignored) {
            }
            try {
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                            0, 0, listener, Looper.getMainLooper());
                    requested = true;
                }
            } catch (Exception ignored) {
            }

            if (!requested) {
                if (loc != null) {
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("latitude", loc.getLatitude());
                    extra.put("longitude", loc.getLongitude());
                    extra.put("accuracy", loc.getAccuracy());
                    writeCommandResult(commandId, "success",
                            "Location fetched (cached, GPS off)", extra);
                } else {
                    writeCommandResult(commandId, "error",
                            "Location is OFF on device. Turn on Location once.");
                }
                return;
            }

            timeoutHandler.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                try {
                    lm.removeUpdates(listener);
                } catch (Exception ignored) {
                }

                Location last = null;
                try {
                    last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                } catch (Exception ignored) {
                }
                if (last == null) {
                    try {
                        last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    } catch (Exception ignored) {
                    }
                }
                if (last != null) {
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("latitude", last.getLatitude());
                    extra.put("longitude", last.getLongitude());
                    extra.put("accuracy", last.getAccuracy());
                    writeCommandResult(commandId, "success",
                            "Location fetched (timeout fallback)", extra);
                } else {
                    writeCommandResult(commandId, "error",
                            "Location timeout — GPS signal weak or Location OFF");
                }
            }, 25000);

        } catch (SecurityException se) {
            writeCommandResult(commandId, "error", "Location permission denied");
        } catch (Exception e) {
            writeCommandResult(commandId, "error",
                    e.getMessage() != null ? e.getMessage() : "Location error");
        }
    }

    private void handleGetPhoneNumber(String commandId) {
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setPermissionGrantState(adminComponent, getPackageName(),
                        android.Manifest.permission.READ_PHONE_STATE,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dpm.setPermissionGrantState(adminComponent, getPackageName(),
                            android.Manifest.permission.READ_PHONE_NUMBERS,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                }
            }

            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String number = null;
            if (tm != null) number = tm.getLine1Number();
            if (number == null || number.trim().isEmpty()) number = "Not available";

            Map<String, Object> extra = new HashMap<>();
            extra.put("phone_number", number);
            writeCommandResult(commandId, "success", "Phone number fetched", extra);
        } catch (Exception e) {
            writeCommandResult(commandId, "error", e.getMessage());
        }
    }

    private void handleSetWallpaper(String commandId, Map<String, Object> params) {
        if (params == null || !params.containsKey("url")) {
            writeCommandResult(commandId, "error", "url missing in command_params");
            return;
        }
        String imageUrl = String.valueOf(params.get("url"));

        new Thread(() -> {
            try {
                URL url = new URL(imageUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();
                InputStream is = conn.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                is.close();

                if (bitmap != null) {
                    WallpaperManager.getInstance(getApplicationContext()).setBitmap(bitmap);
                    writeCommandResult(commandId, "success", "Wallpaper set");
                } else {
                    writeCommandResult(commandId, "error", "Failed to decode image");
                }
            } catch (Exception e) {
                writeCommandResult(commandId, "error", e.getMessage());
            }
        }).start();
    }

    private void handleResetWallpaper(String commandId) {
        try {
            WallpaperManager.getInstance(getApplicationContext()).clear();
            writeCommandResult(commandId, "success", "Wallpaper reset");
        } catch (Exception e) {
            writeCommandResult(commandId, "error", e.getMessage());
        }
    }

    private void handleSetPassword(String commandId, Map<String, Object> params) {
        if (params == null || !params.containsKey("password")) {
            writeCommandResult(commandId, "error", "password missing in command_params");
            return;
        }
        String password = String.valueOf(params.get("password"));
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setPasswordQuality(adminComponent,
                        DevicePolicyManager.PASSWORD_QUALITY_NUMERIC);
                boolean success = dpm.resetPassword(password, 0);
                writeCommandResult(commandId, success ? "success" : "error",
                        success ? "Password set" : "resetPassword returned false");
            } else {
                writeCommandResult(commandId, "error", "Not Device Owner");
            }
        } catch (Exception e) {
            writeCommandResult(commandId, "error", e.getMessage());
        }
    }

    private void handleRemovePassword(String commandId) {
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                boolean success = dpm.resetPassword("", 0);
                dpm.setPasswordQuality(adminComponent,
                        DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
                writeCommandResult(commandId, success ? "success" : "error",
                        success ? "Password removed" : "Failed to remove password");
            } else {
                writeCommandResult(commandId, "error", "Not Device Owner");
            }
        } catch (Exception e) {
            writeCommandResult(commandId, "error", e.getMessage());
        }
    }

    private void handlePlayWarning(String commandId) {
        try {
            ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3000);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    toneGen.release();
                } catch (Exception ignored) {
                }
            }, 3500);
            writeCommandResult(commandId, "success", "Warning audio played");
        } catch (Exception e) {
            writeCommandResult(commandId, "error", e.getMessage());
        }
    }

    private void showUnbreakableLock() {
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                ComponentName myAdmin = new ComponentName(this, MyAdminReceiver.class);
                try {
                    dpm.setLockTaskPackages(myAdmin, new String[]{getPackageName()});
                } catch (Exception ignored) {
                }
                try {
                    dpm.lockNow();
                } catch (Exception ignored) {
                }
                Intent lockIntent = new Intent(this, LockActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(lockIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (isOverlayShowing || windowManager == null) return;

        try {
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            lockViewOverlay = inflater.inflate(R.layout.activity_lock, null);

            android.widget.Button btnConnectWifi =
                    lockViewOverlay.findViewById(R.id.btn_connect_wifi);
            if (btnConnectWifi != null) {
                btnConnectWifi.setOnClickListener(v -> {
                    Intent panelIntent;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                    } else {
                        panelIntent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                    }
                    panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(panelIntent);
                    } catch (Exception ex) {
                        try {
                            Intent i2 = new Intent(Settings.ACTION_WIFI_SETTINGS);
                            i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i2);
                        } catch (Exception ex2) {
                            ex2.printStackTrace();
                        }
                    }
                });
            }

            ImageView qrCodeImage = lockViewOverlay.findViewById(R.id.qr_code_image);
            FirebaseFirestore.getInstance().collection("AdminSettings").document("PaymentInfo")
                    .get().addOnSuccessListener(documentSnapshot -> {
                        if (documentSnapshot.exists()) {
                            String adminUpi = documentSnapshot.getString("upi_id");
                            if (adminUpi != null && !adminUpi.isEmpty() && qrCodeImage != null) {
                                try {
                                    String upiUrl = "upi://pay?pa=" + adminUpi
                                            + "&pn=EMI_Payment&cu=INR";
                                    MultiFormatWriter writer = new MultiFormatWriter();
                                    BitMatrix matrix = writer.encode(
                                            upiUrl, BarcodeFormat.QR_CODE, 400, 400);
                                    BarcodeEncoder encoder = new BarcodeEncoder();
                                    qrCodeImage.setImageBitmap(encoder.createBitmap(matrix));
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        }
                    });

            int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);

            windowManager.addView(lockViewOverlay, params);
            isOverlayShowing = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void hideLock() {
        if (isOverlayShowing && lockViewOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(lockViewOverlay);
            } catch (Exception ignored) {
            }
            isOverlayShowing = false;
            lockViewOverlay = null;
        }

        try {
            Intent unlock = new Intent(this, LockActivity.class);
            unlock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            unlock.putExtra("force_unlock", true);
            startActivity(unlock);
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void releaseOwnershipAndUninstall() {
        hideLock();
        if (prefs != null) {
            prefs.edit().putBoolean("is_offline_locked", false).apply();
        }
        Toast.makeText(this, "Device Released! Uninstalling...", Toast.LENGTH_LONG).show();

        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.clearDeviceOwnerApp(getPackageName());
            }
            if (dpm != null && adminComponent != null && dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent);
            }
        } catch (Exception ignored) {
        }

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
    public IBinder onBind(Intent intent) {
        return null;
    }
}
