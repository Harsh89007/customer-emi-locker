package com.harshit.emilocker.customer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * Reference-style WarningAudioService.
 * Plays Hindi + English EMI due voice (TTS) + short tone.
 * Started by dealer command play_warning or PreventiveService.
 */
public class WarningAudioService extends Service {

    private static final String TAG = "WarningAudio";
    private static final String CH = "emi_warning_audio";
    private TextToSpeech tts;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(4401, buildNotification());
        playWarning();
        // Auto-stop after ~25s
        handler.postDelayed(this::stopSelf, 25_000L);
        return START_NOT_STICKY;
    }

    private void playWarning() {
        try {
            // Loud tone first
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800);
            handler.postDelayed(tone::release, 1200);
        } catch (Exception e) {
            Log.e(TAG, "tone: " + e.getMessage());
        }

        try {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    Log.e(TAG, "TTS init failed");
                    return;
                }
                try {
                    int lang = tts.setLanguage(new Locale("hi", "IN"));
                    if (lang == TextToSpeech.LANG_MISSING_DATA
                            || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.setLanguage(Locale.ENGLISH);
                    }
                    tts.setSpeechRate(0.9f);
                    tts.setPitch(1.0f);

                    String msgHi = "कृपया अपनी ई एम आई का भुगतान करिए। आपकी ई एम आई बकाया है।";
                    String msgEn = "Please pay your EMI. Your EMI is due.";

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        tts.speak(msgHi, TextToSpeech.QUEUE_FLUSH, null, "emi_hi");
                        tts.speak(msgEn, TextToSpeech.QUEUE_ADD, null, "emi_en");
                    } else {
                        tts.speak(msgHi, TextToSpeech.QUEUE_FLUSH, null);
                        tts.speak(msgEn, TextToSpeech.QUEUE_ADD, null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "speak: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "tts create: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "EMI Warning", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Payment warning audio");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, CH)
                .setContentTitle("Kings EMI Locker")
                .setContentText("Payment reminder playing…")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
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
