package com.harshit.emilocker.customer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.FactoryResetProtectionPolicy;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LockListenerService extends Service {
    private static final long WATCHDOG_INTERVAL_MS = 4000L;
    private static final long WIFI_SESSION_MS = 60_000L;
    private static final String PREF_SIM_SERIALS = "sim_lock_serials";
    private static final String PREF_PWD_TOKEN = "reset_pwd_token_b64";

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private String mySessionId;
    private boolean firestoreAttached = false;
    private com.google.firebase.firestore.ListenerRegistration customersReg;
    private SharedPreferences prefs;

    private WindowManager windowManager;
    private View lockViewOverlay;
    private boolean isOverlayShowing = false;
    private long lastForceAt = 0L;
    private BroadcastReceiver simReceiver;

    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (prefs != null && prefs.getBoolean("is_offline_locked", false)) {
                    long until = prefs.getLong("wifi_session_until", 0L);
                    boolean wifiActive = System.currentTimeMillis() < until;
                    boolean visible = prefs.getBoolean("lock_activity_visible", false);
                    if (!wifiActive && !visible) {
                        forceLockScreen(false);
                    }
                }
                // SIM change check even when not locked
                if (prefs != null && prefs.getBoolean("sim_lock_enabled", false)) {
                    checkSimChangeAndLock();
                }
            } catch (Exception ignored) {
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

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
            // Also try regular (non-device-protected) prefs — OEM storage split can hide session_id
            if (mySessionId == null || mySessionId.trim().isEmpty()) {
                try {
                    SharedPreferences reg = getSharedPreferences("AppPrefs", MODE_PRIVATE);
                    mySessionId = reg.getString("session_id", null);
                    if (mySessionId != null && !mySessionId.trim().isEmpty()) {
                        prefs.edit().putString("session_id", mySessionId).apply();
                    }
                } catch (Exception ignored) {}
            }

            if (mySessionId == null || mySessionId.trim().isEmpty()) {
                // Keep FG service alive; session may arrive after QR. Do NOT stopSelf —
                // stopSelf killed background unlock/release until user opened app.
                android.util.Log.w("LockListener", "session_id missing — waiting");
                return;
            }

            dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            adminComponent = new ComponentName(this, MyAdminReceiver.class);
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            grantPhoneAndLocationPermissions();
            ensurePasswordTokenReady();
            applyDefaultOwnerProtections();

            boolean wasLocked = prefs.getBoolean("is_offline_locked", false);
            if (wasLocked) {
                showUnbreakableLock();
            }

            registerSimReceiver();
        // PreventiveService optional — avoid restart storms
            watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);

            firestoreAttached = true;
            customersReg = FirebaseFirestore.getInstance().collection("Customers").document(mySessionId)
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
                                // Only generate NEW codes when lock *starts* (false → true),
                                // not on every Firestore snapshot while already locked.
                                boolean alreadyLocked = prefs.getBoolean("is_offline_locked", false);
                                boolean lockUiVisible = prefs.getBoolean("lock_activity_visible", false);
                                prefs.edit().putBoolean("is_offline_locked", true).apply();
                                if (!alreadyLocked) {
                                    // Fresh lock cycle → always new 6 codes for dealer
                                    forceNewUnlockCodesOnServer();
                                    showUnbreakableLock(); // ONLY on lock START
                                } else {
                                    // Recovery if codes missing after reboot
                                    ensureUnlockCodesOnServer();
                                    // Re-show lock UI only if not already visible (avoid open/close loop)
                                    if (!lockUiVisible) {
                                        showUnbreakableLock();
                                    }
                                }
                            } else {
                                // Rename: cannot shadow onCreate()'s wasLocked inside lambda
                                boolean wasOfflineLocked = prefs.getBoolean("is_offline_locked", false);
                                prefs.edit().putBoolean("is_offline_locked", false)
                                        .putLong("wifi_session_until", 0L)
                                        .remove("unlock_codes_display")
                                        .apply();
                                if (wasOfflineLocked) {
                                    hideLock(); // ONLY on unlock transition
                                }
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

    private void grantPhoneAndLocationPermissions() {
        if (prefs != null && prefs.getBoolean("perms_granted_once", false)) {
            return;
        }
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                dpm.setPermissionGrantState(adminComponent, getPackageName(),
                        android.Manifest.permission.READ_PHONE_NUMBERS,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Exception ignored) {
            }
        }
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
        try {
            if (prefs != null) {
                prefs.edit().putBoolean("perms_granted_once", true).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private void registerSimReceiver() {
        try {
            if (simReceiver != null) return;
            simReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (prefs != null && prefs.getBoolean("sim_lock_enabled", false)) {
                        checkSimChangeAndLock();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.SIM_STATE_CHANGED");
            filter.addAction("android.intent.action.ACTION_SIM_STATE_CHANGED");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(simReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(simReceiver, filter);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Current SIM serials / subscription ids snapshot */
    private Set<String> collectCurrentSimIds() {
        Set<String> ids = new HashSet<>();
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null) {
                try {
                    String serial = tm.getSimSerialNumber();
                    if (serial != null && !serial.trim().isEmpty()) {
                        ids.add("icc:" + serial.trim());
                    }
                } catch (Exception ignored) {
                }
                try {
                    String sub = tm.getSubscriberId();
                    if (sub != null && !sub.trim().isEmpty()) {
                        ids.add("imsi:" + sub.trim());
                    }
                } catch (Exception ignored) {
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager sm =
                        (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
                    if (list != null) {
                        for (SubscriptionInfo info : list) {
                            ids.add("sub:" + info.getSubscriptionId());
                            try {
                                CharSequence icc = info.getIccId();
                                if (icc != null && icc.length() > 0) {
                                    ids.add("icc:" + icc.toString());
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ids;
    }

    private void saveCurrentSimsAsBaseline() {
        Set<String> ids = collectCurrentSimIds();
        prefs.edit().putStringSet(PREF_SIM_SERIALS, ids).apply();
    }

    private void checkSimChangeAndLock() {
        try {
            Set<String> baseline = prefs.getStringSet(PREF_SIM_SERIALS, null);
            if (baseline == null || baseline.isEmpty()) {
                // First time after ON without baseline — save now, don't lock
                saveCurrentSimsAsBaseline();
                return;
            }
            Set<String> now = collectCurrentSimIds();
            // Empty now = SIM removed — treat as change
            if (now.isEmpty() || !now.equals(baseline)) {
                prefs.edit().putBoolean("is_offline_locked", true).apply();
                // Also push lock to Firebase so admin sees LOCKED
                try {
                    Map<String, Object> up = new HashMap<>();
                    up.put("is_locked", true);
                    up.put("sim_change_detected", true);
                    up.put("sim_change_at", System.currentTimeMillis());
                    FirebaseFirestore.getInstance().collection("Customers").document(mySessionId)
                            .update(up);
                } catch (Exception ignored) {
                }
                forceLockScreen(true);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void forceLockScreen(boolean fromUserWifiEnd) {
        long now = System.currentTimeMillis();
        if (!fromUserWifiEnd && (now - lastForceAt) < 3000L) {
            return;
        }
        lastForceAt = now;

        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                try {
                    dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
                } catch (Exception ignored) {
                }
                try {
                    dpm.lockNow();
                } catch (Exception ignored) {
                }
            }
            Intent lockIntent = new Intent(this, LockActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(lockIntent);
            return; // already showing LockActivity
        } catch (Exception ignored) {
        }
        // Non-DO fallback: overlay path
        if (!isOverlayShowing) {
            showUnbreakableLock();
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

        // Acknowledge command immediately so queue never sticks
        try {
            Map<String, Object> ack = new HashMap<>();
            ack.put("pending_command", "none");
            ack.put("last_command_status", "running");
            ack.put("last_command_name", command);
            if (commandId != null) ack.put("last_command_id_server", commandId);
            FirebaseFirestore.getInstance().collection("Customers").document(mySessionId)
                    .update(ack);
        } catch (Exception ignored) {}

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
                grantPhoneAndLocationPermissions();
                saveCurrentSimsAsBaseline();
                prefs.edit().putBoolean("sim_lock_enabled", true).apply();
                writeCommandResult(commandId, "success",
                        "SIM Change Lock ON — current SIMs saved. Change = auto lock");
                break;
            case "sim_lock_off":
                prefs.edit()
                        .putBoolean("sim_lock_enabled", false)
                        .remove(PREF_SIM_SERIALS)
                        .apply();
                writeCommandResult(commandId, "success", "SIM Change Lock OFF");
                break;
            case "set_frp_account":
                handleSetFrpAccount(commandId, params);
                break;
            // ---- Phone controls (Device Owner) ----
            case "camera_on":
                setCameraDisabledCmd(commandId, false);
                break;
            case "camera_off":
                setCameraDisabledCmd(commandId, true);
                break;
            case "outgoing_calls_on":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_OUTGOING_CALLS, false, "Outgoing calls ON");
                break;
            case "outgoing_calls_off":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_OUTGOING_CALLS, true, "Outgoing calls OFF");
                break;
            case "usb_on":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER, false, "USB file transfer ON");
                break;
            case "usb_off":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER, true, "USB file transfer OFF");
                break;
            case "debug_on":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES, false, "Debugging ON");
                break;
            case "debug_off":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES, true, "Debugging OFF");
                break;
            case "install_apps_on":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_INSTALL_APPS, false, "App install ON");
                break;
            case "install_apps_off":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_INSTALL_APPS, true, "App install OFF");
                break;
            case "uninstall_apps_on":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_UNINSTALL_APPS, false, "App uninstall ON");
                break;
            case "uninstall_apps_off":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_UNINSTALL_APPS, true, "App uninstall OFF");
                break;
            case "factory_reset_on":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_FACTORY_RESET, false, "Factory reset allowed");
                break;
            case "factory_reset_off":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_FACTORY_RESET, true, "Factory reset blocked");
                break;
            case "datetime_on":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_CONFIG_DATE_TIME, false, "Date-Time config ON");
                break;
            case "datetime_off":
                setRestrictionCmd(commandId, android.os.UserManager.DISALLOW_CONFIG_DATE_TIME, true, "Date-Time config OFF");
                break;
            case "soft_reboot":
                handleSoftReboot(commandId);
                break;
            case "suspend_apps":
                handleSuspendApps(commandId, params, true);
                break;
            case "unsuspend_apps":
                handleSuspendApps(commandId, params, false);
                break;
            case "app_control":
                handleAppControl(commandId, params);
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

    private void handleSetFrpAccount(String commandId, Map<String, Object> params) {
        if (params == null) {
            writeCommandResult(commandId, "error", "params missing");
            return;
        }
        String accountId = params.get("account_id") != null
                ? String.valueOf(params.get("account_id")).trim() : "";
        String email = params.get("email") != null
                ? String.valueOf(params.get("email")).trim() : "";

        if (accountId.isEmpty()) {
            writeCommandResult(commandId, "error",
                    "frp_account_id missing — Dealer must Google Sign-In FRP first");
            return;
        }

        try {
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                writeCommandResult(commandId, "error",
                        "Not Device Owner — FRP only on owned phones");
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                writeCommandResult(commandId, "error", "FRP needs Android 11+");
                return;
            }

            ArrayList<String> accounts = new ArrayList<>();
            accounts.add(accountId);

            FactoryResetProtectionPolicy policy =
                    new FactoryResetProtectionPolicy.Builder()
                            .setFactoryResetProtectionAccounts(accounts)
                            .setFactoryResetProtectionEnabled(true)
                            .build();
            dpm.setFactoryResetProtectionPolicy(adminComponent, policy);

            Intent frp = new Intent("com.google.android.gms.auth.FRP_CONFIG_CHANGED");
            frp.setPackage("com.google.android.gms");
            frp.addFlags(0x10000000);
            sendBroadcast(frp);

            try {
                Bundle bundle = new Bundle();
                bundle.putStringArray("factoryResetProtectionAdmin",
                        new String[]{accountId});
                dpm.setApplicationRestrictions(adminComponent,
                        "com.google.android.gms", bundle);
            } catch (Exception ignored) {
            }

            prefs.edit()
                    .putString("frp_account_id", accountId)
                    .putString("frp_email", email)
                    .apply();

            writeCommandResult(commandId, "success",
                    "FRP set. Reset के बाद Gmail: " + (email.isEmpty() ? accountId : email));
        } catch (Exception e) {
            writeCommandResult(commandId, "error",
                    e.getMessage() != null ? e.getMessage() : "FRP failed");
        }
    }

    private void handleGetLocation(String commandId) {
        // Clear command IMMEDIATELY so later Lock/Unlock are never blocked if GPS hangs
        try {
            writeCommandResult(commandId, "pending", "Fetching location...");
        } catch (Exception ignored) {}

        try {
            grantPhoneAndLocationPermissions();

            // Temporarily allow user to open Location settings (policy often blocks it)
            try {
                if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                    dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_CONFIG_LOCATION);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try { dpm.setLocationEnabled(adminComponent, true); } catch (Exception ignored) {}
                    }
                    try {
                        dpm.setSecureSetting(adminComponent, Settings.Secure.LOCATION_MODE,
                                String.valueOf(Settings.Secure.LOCATION_MODE_HIGH_ACCURACY));
                    } catch (Exception ignored) {}
                    // Allow Settings UI while fetching
                    try {
                        dpm.setLockTaskPackages(adminComponent, new String[]{
                                getPackageName(),
                                "com.android.settings",
                                "com.google.android.gms",
                                "com.samsung.android.settings"
                        });
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) {
                writeCommandResult(commandId, "error", "LocationManager not available");
                return;
            }

            boolean gpsOn = false;
            try { gpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}

            // Auto-open Location settings so customer turns GPS on (old working behaviour)
            if (!gpsOn) {
                try {
                    Intent li = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(li);
                } catch (Exception ignored) {
                    try {
                        Intent li = new Intent(Settings.ACTION_SETTINGS);
                        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(li);
                    } catch (Exception ignored2) {}
                }
            }

            Location loc = null;
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            } catch (Exception ignored) {}
            try {
                if (loc == null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (Exception ignored) {}
            try {
                if (loc == null)
                    loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            } catch (Exception ignored) {}

            // Fresh enough (< 5 min)
            if (loc != null && (System.currentTimeMillis() - loc.getTime()) < 5 * 60 * 1000L) {
                Map<String, Object> extra = new HashMap<>();
                extra.put("latitude", loc.getLatitude());
                extra.put("longitude", loc.getLongitude());
                extra.put("accuracy", loc.getAccuracy());
                writeCommandResult(commandId, "success", "Location fetched", extra);
                return;
            }

            final boolean[] done = {false};
            final Handler timeoutHandler = new Handler(Looper.getMainLooper());
            final LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (done[0]) return;
                    done[0] = true;
                    try { lm.removeUpdates(this); } catch (Exception ignored) {}
                    timeoutHandler.removeCallbacksAndMessages(null);
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("latitude", location.getLatitude());
                    extra.put("longitude", location.getLongitude());
                    extra.put("accuracy", location.getAccuracy());
                    writeCommandResult(commandId, "success", "Location fetched", extra);
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };

            boolean requested = false;
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, listener, Looper.getMainLooper());
                    requested = true;
                }
            } catch (Exception ignored) {}
            try {
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, listener, Looper.getMainLooper());
                    requested = true;
                }
            } catch (Exception ignored) {}

            if (!requested) {
                if (loc != null) {
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("latitude", loc.getLatitude());
                    extra.put("longitude", loc.getLongitude());
                    extra.put("accuracy", loc.getAccuracy());
                    writeCommandResult(commandId, "success", "Location fetched (cached)", extra);
                } else {
                    writeCommandResult(commandId, "error", "Location OFF — settings opened on phone");
                }
                return;
            }

            // 12s timeout (was 25s) — always clear command
            timeoutHandler.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                try { lm.removeUpdates(listener); } catch (Exception ignored) {}
                Location last = null;
                try { last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
                if (last == null) {
                    try { last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
                }
                if (last != null) {
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("latitude", last.getLatitude());
                    extra.put("longitude", last.getLongitude());
                    extra.put("accuracy", last.getAccuracy());
                    writeCommandResult(commandId, "success", "Location fetched (timeout)", extra);
                } else {
                    writeCommandResult(commandId, "error", "Location timeout — turn on GPS on phone");
                }
            }, 12000);

        } catch (SecurityException se) {
            writeCommandResult(commandId, "error", "Location permission denied");
        } catch (Exception e) {
            writeCommandResult(commandId, "error",
                    e.getMessage() != null ? e.getMessage() : "Location error");
        }
    }

    private void handleGetPhoneNumber(String commandId) {
        try {
            grantPhoneAndLocationPermissions();

            List<String> found = new ArrayList<>();
            StringBuilder detail = new StringBuilder();

            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

            // 1) Default line
            try {
                if (tm != null) {
                    String n = tm.getLine1Number();
                    addPhoneIfValid(found, n);
                }
            } catch (Exception ignored) {
            }

            // 2) Every active subscription (dual SIM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    SubscriptionManager sm = (SubscriptionManager)
                            getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                    if (sm != null) {
                        List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
                        if (list != null) {
                            int slot = 1;
                            for (SubscriptionInfo info : list) {
                                int subId = info.getSubscriptionId();
                                String n = null;

                                // API 33+ getPhoneNumber
                                if (Build.VERSION.SDK_INT >= 33) {
                                    try {
                                        n = sm.getPhoneNumber(subId);
                                    } catch (Exception ignored) {
                                    }
                                }

                                // createForSubscriptionId.getLine1Number
                                if ((n == null || n.trim().isEmpty())
                                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                                        && tm != null) {
                                    try {
                                        TelephonyManager subTm = tm.createForSubscriptionId(subId);
                                        if (subTm != null) {
                                            n = subTm.getLine1Number();
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }

                                String carrier = null;
                                try {
                                    carrier = info.getCarrierName() != null
                                            ? info.getCarrierName().toString() : null;
                                } catch (Exception ignored) {
                                }

                                if (n != null && !n.trim().isEmpty()
                                        && !n.replaceAll("[^0-9+]", "").isEmpty()) {
                                    addPhoneIfValid(found, n.trim());
                                    detail.append("SIM").append(slot).append(": ")
                                            .append(n.trim());
                                    if (carrier != null) {
                                        detail.append(" (").append(carrier).append(")");
                                    }
                                    detail.append("\n");
                                } else {
                                    detail.append("SIM").append(slot).append(": Not on SIM");
                                    if (carrier != null) {
                                        detail.append(" (").append(carrier).append(")");
                                    }
                                    detail.append("\n");
                                }
                                slot++;
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            Map<String, Object> extra = new HashMap<>();
            if (!found.isEmpty()) {
                String primary = found.get(0);
                extra.put("phone_number", primary);
                if (found.size() > 1) {
                    extra.put("phone_numbers", found);
                }
                String msg = "Phone number fetched";
                if (detail.length() > 0) {
                    msg = msg + "\n" + detail.toString().trim();
                }
                writeCommandResult(commandId, "success", msg, extra);
            } else {
                extra.put("phone_number", "Not available");
                String msg = "Not available — operator ने SIM पर number store नहीं किया"
                        + (detail.length() > 0 ? ("\n" + detail.toString().trim()) : "");
                writeCommandResult(commandId, "success", msg, extra);
            }
        } catch (Exception e) {
            writeCommandResult(commandId, "error",
                    e.getMessage() != null ? e.getMessage() : "Phone fetch error");
        }
    }

    private void addPhoneIfValid(List<String> found, String n) {
        if (n == null) return;
        String t = n.trim();
        if (t.isEmpty()) return;
        // skip pure zeros / garbage
        String digits = t.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.matches("0+")) return;
        if (!found.contains(t)) found.add(t);
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

    /**
     * Android 11+ पर resetPassword() बंद है।
     * Try: resetPasswordWithToken. Fail पर clear message.
     * App-level PIN prefs में भी save (lock UI बाद में use कर सकती है).
     */
    private void handleSetPassword(String commandId, Map<String, Object> params) {
        if (params == null || !params.containsKey("password")) {
            writeCommandResult(commandId, "error", "password missing in command_params");
            return;
        }
        String password = String.valueOf(params.get("password")).trim();
        if (password.isEmpty()) {
            writeCommandResult(commandId, "error", "password empty");
            return;
        }

        try {
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                writeCommandResult(commandId, "error", "Not Device Owner");
                return;
            }

            // App-level backup PIN (system fail होने पर भी)
            prefs.edit().putString("app_lock_pin", password).apply();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Token-based API
                byte[] token = loadOrCreateResetToken();
                boolean tokenOk = false;
                try {
                    tokenOk = dpm.setResetPasswordToken(adminComponent, token);
                } catch (Exception e) {
                    writeCommandResult(commandId, "error",
                            "Token set failed: " + e.getMessage()
                                    + " | App PIN saved only (system lock n/a on this Android)");
                    return;
                }

                boolean active = false;
                try {
                    active = dpm.isResetPasswordTokenActive(adminComponent);
                } catch (Exception ignored) {
                }

                if (!active) {
                    // Token user confirm के बिना active नहीं — remote set अक्सर fail
                    writeCommandResult(commandId, "error",
                            "System password token not active. "
                                    + "Android 11+ remote set password limited. "
                                    + "App PIN saved. Customer must unlock once after policy, "
                                    + "or use EMI lock screen only.");
                    return;
                }

                try {
                    boolean ok = dpm.resetPasswordWithToken(adminComponent, password, token, 0);
                    if (ok) {
                        writeCommandResult(commandId, "success", "System password set");
                    } else {
                        writeCommandResult(commandId, "error",
                                "resetPasswordWithToken returned false. App PIN saved.");
                    }
                } catch (SecurityException se) {
                    writeCommandResult(commandId, "error",
                            "Android blocked system password: " + se.getMessage()
                                    + " | App PIN saved only");
                }
            } else {
                // Older Android — legacy API
                try {
                    dpm.setPasswordQuality(adminComponent,
                            DevicePolicyManager.PASSWORD_QUALITY_NUMERIC);
                    boolean success = dpm.resetPassword(password, 0);
                    writeCommandResult(commandId, success ? "success" : "error",
                            success ? "Password set" : "resetPassword returned false");
                } catch (SecurityException se) {
                    writeCommandResult(commandId, "error", se.getMessage());
                }
            }
        } catch (Exception e) {
            writeCommandResult(commandId, "error",
                    e.getMessage() != null ? e.getMessage() : "set password failed");
        }
    }

    private void handleRemovePassword(String commandId) {
        try {
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                writeCommandResult(commandId, "error", "Not Device Owner");
                return;
            }

            prefs.edit().remove("app_lock_pin").apply();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                byte[] token = loadOrCreateResetToken();
                boolean active = false;
                try {
                    active = dpm.isResetPasswordTokenActive(adminComponent);
                } catch (Exception ignored) {
                }
                if (!active) {
                    try {
                        dpm.setResetPasswordToken(adminComponent, token);
                        active = dpm.isResetPasswordTokenActive(adminComponent);
                    } catch (Exception ignored) {
                    }
                }
                if (active) {
                    try {
                        boolean ok = dpm.resetPasswordWithToken(adminComponent, "", token, 0);
                        writeCommandResult(commandId, ok ? "success" : "error",
                                ok ? "Password cleared" : "Clear returned false");
                        return;
                    } catch (SecurityException se) {
                        writeCommandResult(commandId, "error",
                                "Android blocked clear password: " + se.getMessage());
                        return;
                    }
                }
                writeCommandResult(commandId, "error",
                        "Cannot clear system password on this Android (token inactive). "
                                + "App PIN cleared.");
            } else {
                try {
                    boolean success = dpm.resetPassword("", 0);
                    dpm.setPasswordQuality(adminComponent,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
                    writeCommandResult(commandId, success ? "success" : "error",
                            success ? "Password removed" : "Failed to remove password");
                } catch (SecurityException se) {
                    writeCommandResult(commandId, "error", se.getMessage());
                }
            }
        } catch (Exception e) {
            writeCommandResult(commandId, "error",
                    e.getMessage() != null ? e.getMessage() : "remove password failed");
        }
    }

    private byte[] loadOrCreateResetToken() {
        String b64 = prefs.getString(PREF_PWD_TOKEN, null);
        if (b64 != null && !b64.isEmpty()) {
            try {
                return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            } catch (Exception ignored) {
            }
        }
        byte[] token = new byte[32];
        new SecureRandom().nextBytes(token);
        prefs.edit().putString(PREF_PWD_TOKEN,
                android.util.Base64.encodeToString(token, android.util.Base64.DEFAULT)).apply();
        return token;
    }

    private void handlePlayWarning(String commandId) {
        try {
            Intent audio = new Intent(this, WarningAudioService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(audio);
            } else {
                startService(audio);
            }
        } catch (Exception ignored) {}

        try {
            // Max alarm volume
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                    am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
                    int maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0);
                }
            } catch (Exception ignored) {
            }

            // Short alert tone first
            try {
                ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try { toneGen.release(); } catch (Exception ignored) {}
                }, 1000);
            } catch (Exception ignored) {
            }

            // Voice message: Hindi + English EMI due
            final TextToSpeech[] ttsHolder = new TextToSpeech[1];
            ttsHolder[0] = new TextToSpeech(getApplicationContext(), status -> {
                try {
                    if (status != TextToSpeech.SUCCESS || ttsHolder[0] == null) {
                        writeCommandResult(commandId, "success", "Tone played (TTS unavailable)");
                        return;
                    }
                    TextToSpeech tts = ttsHolder[0];
                    // Prefer Hindi, fallback English
                    int lang = tts.setLanguage(new java.util.Locale("hi", "IN"));
                    if (lang == TextToSpeech.LANG_MISSING_DATA
                            || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.setLanguage(java.util.Locale.ENGLISH);
                    }
                    try {
                        tts.setSpeechRate(0.9f);
                        tts.setPitch(1.0f);
                    } catch (Exception ignored) {
                    }

                    String msgHi =
                            "कृपया अपनी ई एम आई का भुगतान करिए। आपकी ई एम आई बकाया है।";
                    String msgEn =
                            "Please pay your EMI. Your EMI is due. Contact your dealer to unlock.";

                    Bundle params = new Bundle();
                    params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM,
                            String.valueOf(AudioManager.STREAM_ALARM));

                    tts.speak(msgHi, TextToSpeech.QUEUE_FLUSH, params, "emi_warn_hi");
                    tts.speak(msgEn, TextToSpeech.QUEUE_ADD, params, "emi_warn_en");

                    writeCommandResult(commandId, "success", "Warning voice played");

                    // Cleanup after speech
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            if (ttsHolder[0] != null) {
                                ttsHolder[0].stop();
                                ttsHolder[0].shutdown();
                                ttsHolder[0] = null;
                            }
                        } catch (Exception ignored) {
                        }
                    }, 12000);
                } catch (Exception e) {
                    writeCommandResult(commandId, "error",
                            e.getMessage() != null ? e.getMessage() : "TTS failed");
                }
            });
        } catch (Exception e) {
            writeCommandResult(commandId, "error",
                    e.getMessage() != null ? e.getMessage() : "Warning failed");
        }
    }

    private void showUnbreakableLock() {
        // === DEVICE OWNER: only LockActivity (kiosk). DO NOT fall through to overlay
        // (overlay without permission crashes service → open/close loop + no background commands)
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                try {
                    dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
                } catch (Exception ignored) {
                }
                // Start LockActivity FIRST — lockNow() before activity can hide UI on many OEMs
                Intent lockIntent = new Intent(this, LockActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                startActivity(lockIntent);
                return; // CRITICAL — never try overlay on DO devices
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // === RUNNING DEVICE (non-owner): SYSTEM_ALERT_WINDOW overlay only
        if (isOverlayShowing || windowManager == null) return;

        try {
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            lockViewOverlay = inflater.inflate(R.layout.activity_lock, null);

            android.widget.Button btnConnectWifi =
                    lockViewOverlay.findViewById(R.id.btn_connect_wifi);
            if (btnConnectWifi != null) {
                btnConnectWifi.setOnClickListener(v -> {
                    if (prefs != null) {
                        prefs.edit()
                                .putLong("wifi_session_until",
                                        System.currentTimeMillis() + WIFI_SESSION_MS)
                                .apply();
                    }
                    try {
                        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                            dpm.setLockTaskPackages(adminComponent, new String[]{
                                    getPackageName(),
                                    "com.android.settings",
                                    "com.android.settings.intelligence",
                                    "com.samsung.android.settings",
                                    "com.android.systemui",
                                    "com.android.phone"
                            });
                            try {
                                // Allow leaving kiosk to open Wi-Fi (same as LockActivity)
                                android.app.ActivityManager am =
                                        (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                                // stopLockTask only works from Activity; start LockActivity wifi path instead
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        // Prefer full Wi-Fi settings over Panel
                        startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    } catch (Exception ex) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startActivity(new Intent(Settings.Panel.ACTION_WIFI)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    watchdogHandler.postDelayed(() -> {
                        if (prefs != null) {
                            prefs.edit().putLong("wifi_session_until", 0L).apply();
                        }
                        if (prefs != null && prefs.getBoolean("is_offline_locked", false)) {
                            forceLockScreen(true);
                        }
                    }, WIFI_SESSION_MS);
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


    /** Load session_id and attach Firestore listener if not yet attached. */
    private void ensureSessionAndListen() {
        if (prefs == null) {
            try {
                Context deviceContext = this;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    deviceContext = createDeviceProtectedStorageContext();
                }
                prefs = deviceContext.getSharedPreferences("AppPrefs", MODE_PRIVATE);
            } catch (Exception e) {
                prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
            }
        }

        String sid = prefs != null ? prefs.getString("session_id", null) : null;
        if (sid == null || sid.trim().isEmpty()) {
            try {
                SharedPreferences reg = getSharedPreferences("AppPrefs", MODE_PRIVATE);
                sid = reg.getString("session_id", null);
                if (sid != null && !sid.trim().isEmpty() && prefs != null) {
                    prefs.edit().putString("session_id", sid).apply();
                }
            } catch (Exception ignored) {}
        }

        if (sid == null || sid.trim().isEmpty()) {
            android.util.Log.w("LockListener", "still no session_id");
            return;
        }

        if (firestoreAttached && sid.equals(mySessionId)) {
            return;
        }

        mySessionId = sid;

        if (dpm == null) {
            dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        }
        if (adminComponent == null) {
            adminComponent = new ComponentName(this, MyAdminReceiver.class);
        }
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        try {
            if (customersReg != null) {
                customersReg.remove();
                customersReg = null;
            }
        } catch (Exception ignored) {}

        firestoreAttached = true;
        android.util.Log.i("LockListener", "attaching Firestore for session=" + mySessionId);

        try { grantPhoneAndLocationPermissions(); } catch (Exception ignored) {}
        try { ensurePasswordTokenReady(); } catch (Exception ignored) {}
        try { applyDefaultOwnerProtections(); } catch (Exception ignored) {}

        boolean offlineLocked = prefs.getBoolean("is_offline_locked", false);
        if (offlineLocked) {
            showUnbreakableLock();
        }

        customersReg = FirebaseFirestore.getInstance().collection("Customers").document(mySessionId)
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
                            boolean alreadyLocked = prefs.getBoolean("is_offline_locked", false);
                            boolean lockUiVisible = prefs.getBoolean("lock_activity_visible", false);
                            prefs.edit().putBoolean("is_offline_locked", true).apply();
                            if (!alreadyLocked) {
                                forceNewUnlockCodesOnServer();
                                showUnbreakableLock();
                            } else {
                                ensureUnlockCodesOnServer();
                                if (!lockUiVisible) {
                                    showUnbreakableLock();
                                }
                            }
                        } else {
                            boolean wasOfflineLocked = prefs.getBoolean("is_offline_locked", false);
                            prefs.edit().putBoolean("is_offline_locked", false)
                                    .putLong("wifi_session_until", 0L)
                                    .remove("unlock_codes_display")
                                    .apply();
                            if (wasOfflineLocked) {
                                hideLock();
                            }
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
                        android.util.Log.e("LockListener", "snapshot: " + ex.getMessage());
                    }
                });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // CRITICAL: if service started before QR, onCreate had no session.
        // When MainActivity starts us again after QR, attach listener here.
        try {
            ensureSessionAndListen();
        } catch (Exception e) {
            android.util.Log.e("LockListener", "ensureSession: " + e.getMessage());
        }

        if (intent != null && intent.hasExtra("sms_unlock_code")) {
            final String code = intent.getStringExtra("sms_unlock_code");
            if (code != null && !code.isEmpty()) {
                try {
                    // Prefer existing offline unlock helpers if present
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(() -> {
                        try {
                            // Mirror LockActivity offline unlock: clear lock flags + stop lock task
                            if (prefs != null) {
                                java.util.Set<String> codes = prefs.getStringSet("unlock_codes_display", null);
                                boolean ok = codes != null && codes.contains(code);
                                if (!ok) {
                                    String one = prefs.getString("unlock_code", null);
                                    ok = code.equals(one);
                                }
                                if (ok) {
                                    prefs.edit()
                                        .putBoolean("is_offline_locked", false)
                                        .putBoolean("is_locked", false)
                                        .remove("unlock_codes_display")
                                        .apply();
                                    try {
                                        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                                            dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
                                        }
                                    } catch (Exception ignored) {}
                                    // Notify Firestore unlocked if session known
                                    try {
                                        String sid = prefs.getString("session_id", null);
                                        if (sid != null && !sid.isEmpty()) {
                                            java.util.Map<String, Object> u = new java.util.HashMap<>();
                                            u.put("is_locked", false);
                                            u.put("pending_command", "");
                                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                                .collection("Customers").document(sid).update(u);
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
            }
        }

        return START_STICKY;
    }


    /** Device Owner: provision password token early (no lockscreen → active immediately). */
    private void ensurePasswordTokenReady() {
        try {
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
            byte[] token = loadOrCreateResetToken();
            try {
                dpm.setResetPasswordToken(adminComponent, token);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Baseline protections while owned. */
    private void applyDefaultOwnerProtections() {
        try {
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;
            try {
                dpm.addUserRestriction(adminComponent, android.os.UserManager.DISALLOW_SAFE_BOOT);
            } catch (Exception ignored) {
            }
            try {
                dpm.setUninstallBlocked(adminComponent, getPackageName(), true);
            } catch (Exception ignored) {
            }
            try {
                dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Recovery only: if this lock cycle has no codes yet, create them.
     * Does NOT overwrite existing codes for the same lock cycle.
     */
    private void ensureUnlockCodesOnServer() {
        try {
            if (mySessionId == null) return;
            String existing = prefs.getString("unlock_codes_display", null);
            if (existing != null && existing.length() >= 6) {
                return;
            }
            forceNewUnlockCodesOnServer();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Always generate 6 NEW 6-digit codes → Firestore + local prefs.
     * Called on each fresh LOCK (false → true) so dealer always sees new codes
     * and old offline codes stop working after unlock + re-lock.
     */
    private void forceNewUnlockCodesOnServer() {
        try {
            if (mySessionId == null) return;
            java.util.ArrayList<String> codes = new java.util.ArrayList<>();
            SecureRandom r = new SecureRandom();
            StringBuilder display = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                int n = 100000 + r.nextInt(900000);
                String c = String.valueOf(n);
                codes.add(c);
                if (i > 0 && i % 3 == 0) display.append("\n");
                else if (i > 0) display.append("   ");
                display.append(c);
            }
            prefs.edit().putString("unlock_codes_display", display.toString()).apply();
            Map<String, Object> up = new HashMap<>();
            up.put("unlock_codes", codes);
            up.put("unlock_codes_updated_at", System.currentTimeMillis());
            FirebaseFirestore.getInstance().collection("Customers").document(mySessionId).update(up);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setCameraDisabledCmd(String commandId, boolean disabled) {
        try {
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                writeCommandResult(commandId, "error", "Not Device Owner");
                return;
            }
            dpm.setCameraDisabled(adminComponent, disabled);
            writeCommandResult(commandId, "success", disabled ? "Camera OFF" : "Camera ON");
        } catch (Exception e) {
            writeCommandResult(commandId, "error", e.getMessage());
        }
    }

    private void setRestrictionCmd(String commandId, String restriction, boolean enable, String okMsg) {
        try {
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                writeCommandResult(commandId, "error", "Not Device Owner");
                return;
            }
            if (enable) {
                dpm.addUserRestriction(adminComponent, restriction);
            } else {
                dpm.clearUserRestriction(adminComponent, restriction);
            }
            writeCommandResult(commandId, "success", okMsg);
        } catch (Exception e) {
            writeCommandResult(commandId, "error", e.getMessage());
        }
    }

    private void handleSoftReboot(String commandId) {
        try {
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                writeCommandResult(commandId, "error", "Not Device Owner");
                return;
            }
            writeCommandResult(commandId, "success", "Rebooting...");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        dpm.reboot(adminComponent);
                    } else {
                        // best-effort
                        Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 800);
        } catch (Exception e) {
            writeCommandResult(commandId, "error", e.getMessage());
        }
    }

    /**
     * params: packages = ["com.whatsapp", ...] OR package = "com.whatsapp"
     */
    private void handleSuspendApps(String commandId, Map<String, Object> params, boolean suspend) {
        try {
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                writeCommandResult(commandId, "error", "Not Device Owner");
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                writeCommandResult(commandId, "error", "Needs Android 7+");
                return;
            }
            java.util.ArrayList<String> pkgs = new java.util.ArrayList<>();
            if (params != null) {
                Object p = params.get("packages");
                if (p instanceof List) {
                    for (Object o : (List<?>) p) {
                        if (o != null) pkgs.add(String.valueOf(o).trim());
                    }
                }
                if (params.get("package") != null) {
                    pkgs.add(String.valueOf(params.get("package")).trim());
                }
            }
            if (pkgs.isEmpty()) {
                writeCommandResult(commandId, "error", "packages missing");
                return;
            }
            String[] arr = pkgs.toArray(new String[0]);
            dpm.setPackagesSuspended(adminComponent, arr, suspend);
            writeCommandResult(commandId, "success",
                    (suspend ? "Suspended: " : "Unsuspended: ") + pkgs);
        } catch (Exception e) {
            writeCommandResult(commandId, "error", e.getMessage());
        }
    }

    /**
     * Single app toggle from dealer grid.
     * params: package="com.whatsapp", enabled=true/false
     * enabled false → suspend; true → unsuspend
     */
    private void handleAppControl(String commandId, Map<String, Object> params) {
        if (params == null || params.get("package") == null) {
            writeCommandResult(commandId, "error", "package missing");
            return;
        }
        String pkg = String.valueOf(params.get("package")).trim();
        boolean enabled = true;
        Object en = params.get("enabled");
        if (en instanceof Boolean) enabled = (Boolean) en;
        else if (en != null) enabled = Boolean.parseBoolean(String.valueOf(en));
        Map<String, Object> p = new HashMap<>();
        p.put("package", pkg);
        handleSuspendApps(commandId, p, !enabled);
    }


    private void releaseOwnershipAndUninstall() {
        prefs.edit()
                .putBoolean("is_offline_locked", false)
                .putLong("wifi_session_until", 0L)
                .putBoolean("sim_lock_enabled", false)
                .remove(PREF_SIM_SERIALS)
                .apply();
        watchdogHandler.removeCallbacks(watchdogRunnable);
        try {
            if (simReceiver != null) {
                unregisterReceiver(simReceiver);
                simReceiver = null;
            }
        } catch (Exception ignored) {
        }
        hideLock();
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
    public void onDestroy() {
        watchdogHandler.removeCallbacks(watchdogRunnable);
        try {
            if (simReceiver != null) {
                unregisterReceiver(simReceiver);
                simReceiver = null;
            }
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
