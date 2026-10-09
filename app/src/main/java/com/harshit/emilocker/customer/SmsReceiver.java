package com.harshit.emilocker.customer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Reference-style SmsReceiver.
 * If incoming SMS body contains a valid 6-digit offline unlock code
 * stored in AppPrefs, triggers unlock via LockListenerService.
 * Does not replace dealer panel unlock — adds SMS channel like reference.
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getExtras() == null) return;
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        try {
            Bundle bundle = intent.getExtras();
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null) return;
            String format = bundle.getString("format");

            StringBuilder body = new StringBuilder();
            for (Object pdu : pdus) {
                SmsMessage msg;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    msg = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else {
                    msg = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (msg != null && msg.getMessageBody() != null) {
                    body.append(msg.getMessageBody());
                }
            }

            String text = body.toString();
            if (text.isEmpty()) return;

            Context deviceCtx = context;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                deviceCtx = context.createDeviceProtectedStorageContext();
            }
            SharedPreferences prefs = deviceCtx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE);

            // Collect known codes
            Set<String> codes = new HashSet<>();
            Set<String> display = prefs.getStringSet("unlock_codes_display", null);
            if (display != null) codes.addAll(display);
            String single = prefs.getString("unlock_code", null);
            if (single != null) codes.add(single);

            String matched = null;
            for (String c : codes) {
                if (c != null && c.length() >= 4 && text.contains(c)) {
                    matched = c;
                    break;
                }
            }
            // Also accept bare 6-digit if matches
            if (matched == null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\b(\\d{6})\\b").matcher(text);
                while (m.find()) {
                    String dig = m.group(1);
                    if (codes.contains(dig)) {
                        matched = dig;
                        break;
                    }
                }
            }

            if (matched == null) return;

            Log.i(TAG, "Valid unlock code via SMS");
            Intent svc = new Intent(context, LockListenerService.class);
            svc.putExtra("sms_unlock_code", matched);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "sms: " + e.getMessage());
        }
    }
}
