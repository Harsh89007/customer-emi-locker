package com.harshit.emilocker.customer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;

public class GetProvisioningModeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int mode = DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ArrayList<Integer> allowed = getIntent().getIntegerArrayListExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES);
            if (allowed != null && !allowed.isEmpty() && !allowed.contains(mode)) {
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
        }

        Intent result = new Intent();
        result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, mode);
        setResult(RESULT_OK, result);
        finish();
    }
}
