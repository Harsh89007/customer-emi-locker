package com.harshit.emilocker.customer;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.UserManager;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Device Owner / Admin receiver.
 * Stronger policies aligned with reference Ai Locker (without removing
 * running-device overlay path elsewhere).
 */
public class MyAdminReceiver extends DeviceAdminReceiver {

    public static final String EXTRA_OPEN_DEALER_QR = "open_dealer_qr";
    public static final String EXTRA_FROM_TRANSFER = "from_ownership_transfer";

    public static ComponentName getComponentName(Context context) {
        return new ComponentName(context, MyAdminReceiver.class);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        applyOwnerPolicies(context);
        startProtectionServices(context);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
    }

    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        super.onProfileProvisioningComplete(context, intent);
        applyOwnerPolicies(context);
        startProtectionServices(context);
        openMainForDealerQr(context, false);
    }

    @Override
    public void onTransferOwnershipComplete(Context context, PersistableBundle bundle) {
        super.onTransferOwnershipComplete(context, bundle);
        applyOwnerPolicies(context);
        startProtectionServices(context);
        Toast.makeText(context, "Kings EMI Locker Active!", Toast.LENGTH_LONG).show();
        openMainForDealerQr(context, true);
    }

    private void applyOwnerPolicies(Context context) {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = getComponentName(context);
            if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
                return;
            }

            // Location always on + user cannot turn off
            try {
                dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE,
                        String.valueOf(Settings.Secure.LOCATION_MODE_HIGH_ACCURACY));
            } catch (Exception ignored) {
            }
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SHARE_LOCATION);

            // Block developer options / ADB
            dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES);
            try {
                dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "0");
            } catch (Exception ignored) {
            }

            // Factory reset / safe boot / add user
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_REMOVE_USER);

            // Uninstall of this app blocked via DO; also restrict apps control lightly
            try {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            } catch (Exception ignored) {
            }

            // Lock task packages — our app only by default (LockActivity expands for Wi‑Fi)
            try {
                dpm.setLockTaskPackages(admin, new String[]{context.getPackageName()});
            } catch (Exception ignored) {
            }

            // Keyguard features disable when locked (reference-style)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setKeyguardDisabledFeatures(admin,
                            DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL);
                }
            } catch (Exception ignored) {
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startProtectionServices(Context context) {
        try {
            Intent svc = new Intent(context, LockListenerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {
        }
        try {
            PreventiveService.start(context);
        } catch (Exception ignored) {
        }
    }

    private void openMainForDealerQr(Context context, boolean fromTransfer) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(EXTRA_OPEN_DEALER_QR, true);
        launchIntent.putExtra(EXTRA_FROM_TRANSFER, fromTransfer);
        context.startActivity(launchIntent);
    }
}
