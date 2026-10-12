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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Reference-style PreventiveService:
 * - Requests battery optimization ignore
 * - Keeps LockListenerService alive
 * - Re-shows lock if offline-locked and lock not visible
 * Does NOT remove overlay / running-device path.
 */
public class PreventiveService extends Service {

    private static final String TAG = "PreventiveService";
    private static final String CH = "emi_preventive";
    private static final long TICK_MS = 12_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private DevicePolicyManager dpm;
    private ComponentName admin;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                ensureLockListener();
                ensureLockIfNeeded();
            } catch (Exception e) {
                Log.e(TAG, "tick: " + e.getMessage());
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Context deviceCtx = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            deviceCtx = createDeviceProtectedStorageContext();
        }
        prefs = deviceCtx.getSharedPreferences("AppPrefs", MODE_PRIVATE);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        admin = MyAdminReceiver.getComponentName(this);
        requestBatteryOptimizationIgnore();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(4402, buildNotification());
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private void ensureLockListener() {
        try {
            Intent i = new Intent(this, LockListenerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "start LockListener: " + e.getMessage());
        }
    }

    private void ensureLockIfNeeded() {
        if (prefs == null) return;
        boolean locked = prefs.getBoolean("is_offline_locked", false)
                || prefs.getBoolean("is_locked", false);
        if (!locked) return;

        // If lock UI not marked visible, bring it back
        boolean visible = prefs.getBoolean("lock_visible", false);
        if (!visible) {
            try {
                Intent lock = new Intent(this, LockActivity.class);
                lock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(lock);
            } catch (Exception e) {
                Log.e(TAG, "relock: " + e.getMessage());
            }
            try {
                if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                    dpm.lockNow();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void requestBatteryOptimizationIgnore() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            // Under DO we cannot always open settings UI silently; request when possible
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Only if we are not in tight kiosk — still safe to try once
            // Commented auto-start to avoid stealing lock UI; Boot/Main can call.
            // startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "battery: " + e.getMessage());
        }
    }

    /** Call from MainActivity when device is secured (once). */
    public static void requestBatteryIgnoreFromActivity(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            PowerManager pm = (PowerManager) ctx.getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) return;
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, PreventiveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "start: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "EMI Protection", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, CH)
                .setContentTitle("Kings EMI Locker")
                .setContentText("Protection active")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
        // Do NOT auto-restart here — caused service storm / app flicker
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
