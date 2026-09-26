package com.harshit.emilocker.customer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class PolicyComplianceActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_OK);

        Intent launch = new Intent(this, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launch.putExtra(MyAdminReceiver.EXTRA_OPEN_DEALER_QR, true);
        startActivity(launch);
        finish();
    }
}
