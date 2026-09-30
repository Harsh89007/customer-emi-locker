package com.harshit.emilocker.customer;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.os.UserManager;
import android.provider.Settings;
import android.widget.Toast;

public class MyAdminReceiver extends DeviceAdminReceiver {

    public static final String EXTRA_OPEN_DEALER_QR = "open_dealer_qr";
    public static final String EXTRA_FROM_TRANSFER = "from_ownership_transfer";

    public static ComponentName getComponentName(Context context) {
        return new ComponentName(context, MyAdminReceiver.class);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = getComponentName(context);

            // 1. कस्टमर के फोन का GPS (लोकेशन) ज़बरदस्ती ON करना
            dpm.setSecureSetting(adminComponent, Settings.Secure.LOCATION_MODE, String.valueOf(Settings.Secure.LOCATION_MODE_HIGH_ACCURACY));
            
            // 2. कस्टमर को सेटिंग्स में जाकर लोकेशन बंद करने से रोकना
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_LOCATION);

            // 3. डेवलपर ऑप्शन (Developer Options) और USB Debugging को परमानेंट ब्लॉक करना
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
    }

    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        super.onProfileProvisioningComplete(context, intent);
        openMainForDealerQr(context, false);
    }

    @Override
    public void onTransferOwnershipComplete(Context context, PersistableBundle bundle) {
        super.onTransferOwnershipComplete(context, bundle);
        Toast.makeText(context, "Kings EMI Locker Active!", Toast.LENGTH_LONG).show();
        openMainForDealerQr(context, true);
    }

    private void openMainForDealerQr(Context context, boolean fromTransfer) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(EXTRA_OPEN_DEALER_QR, true);
        launchIntent.putExtra(EXTRA_FROM_TRANSFER, fromTransfer);
        context.startActivity(launchIntent);
    }
}
