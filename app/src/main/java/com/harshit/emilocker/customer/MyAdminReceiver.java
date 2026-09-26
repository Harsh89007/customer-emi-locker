package com.harshit.emilocker.customer;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.widget.Toast;

public class MyAdminReceiver extends DeviceAdminReceiver {

    public static final String EXTRA_OPEN_DEALER_QR = "open_dealer_qr";
    public static final String EXTRA_FROM_TRANSFER = "from_ownership_transfer";

    public static ComponentName getComponentName(Context context) {
        return new ComponentName(context.getPackageName(),
                MyAdminReceiver.class.getName());
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
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
        // लॉक सर्विस यहाँ मत चलाओ — पहले डीलर QR लिंक होना चाहिए
        openMainForDealerQr(context, true);
    }

    @Override
    public void onTransferOwnershipFailed(Context context, PersistableBundle bundle) {
        super.onTransferOwnershipFailed(context, bundle);
        Toast.makeText(context, "Ownership transfer failed", Toast.LENGTH_LONG).show();
    }

    private void openMainForDealerQr(Context context, boolean fromTransfer) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(EXTRA_OPEN_DEALER_QR, true);
        launchIntent.putExtra(EXTRA_FROM_TRANSFER, fromTransfer);
        context.startActivity(launchIntent);
    }
}
