package com.harshit.emilocker.customer;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Reference-style NetworkService — periodically ensures LockListener is running.
 */
public class NetworkService extends Service {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                Intent i = new Intent(NetworkService.this, LockListenerService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Exception e) {
                Log.e("NetworkService", e.getMessage() != null ? e.getMessage() : "");
            }
            h.postDelayed(this, 30_000L);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
