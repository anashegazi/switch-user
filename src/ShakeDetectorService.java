package com.guest.switcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

public class ShakeDetectorService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "shake_channel";
    private static final int NOTIF_ID = 1001;
    private static final String ACTION_SWITCH_GUEST = "com.guest.switcher.action.SWITCH_GUEST";

    // Shake detection parameters
    // User must shake continuously for this long to trigger
    private static final long SHAKE_HOLD_MS = 600;
    // How long between movements before we consider the shake "broken"
    private static final long SHAKE_GAP_MS = 400;
    private static final float SHAKE_THRESHOLD = 7.0f;
    private static final long COOLDOWN_MS = 3000;

    private static final int PORT_SHELL = 12345;
    private static final int PORT_PERSISTENT = 12347;
    private static final int TIMEOUT = 5000;

    private SensorManager sensorManager;
    private PowerManager.WakeLock wakeLock;

    private long shakeStart = 0;   // when continuous shaking began
    private long lastMove = 0;     // time of last detected movement
    private boolean cooldown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("ShakeDetector", "Service onCreate - starting foreground service");
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("🫨 Shake to Guest is active"));

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            Log.d("ShakeDetector", "Accelerometer listener registered");
        } else {
            Log.e("ShakeDetector", "No accelerometer available on device!");
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guest_switcher:shake_wakelock");
        wakeLock.acquire(10 * 60 * 1000L); // 10 minutes max, re-acquired on each shake
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SWITCH_GUEST.equals(intent.getAction())) {
            triggerSwitch();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        double acceleration = Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH;

        long now = System.currentTimeMillis();

        if (acceleration > SHAKE_THRESHOLD) {
            // A movement was detected
            if (now - lastMove > SHAKE_GAP_MS) {
                // Gap too big -> start a fresh continuous shake
                shakeStart = now;
                Log.d("ShakeDetector", "Shake started");
            }
            lastMove = now;

            long held = now - shakeStart;
            if (held >= SHAKE_HOLD_MS) {
                if (!cooldown) {
                    Log.d("ShakeDetector", "Shake held for " + held + "ms -> TRIGGERING SWITCH");
                    cooldown = true;
                    triggerSwitch();

                    // Reset cooldown after delay
                    new Thread(() -> {
                        try { Thread.sleep(COOLDOWN_MS); } catch (InterruptedException e) {}
                        cooldown = false;
                    }).start();
                }
                // Reset after trigger so a new hold can begin later
                shakeStart = now;
                lastMove = now;
            }
        } else {
            // No movement beyond threshold - if the gap has grown too long, reset
            if (lastMove != 0 && (now - lastMove > SHAKE_GAP_MS)) {
                if (shakeStart != 0) {
                    Log.d("ShakeDetector", "Shake broken before reaching hold time");
                }
                shakeStart = 0;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed
    }

    private void triggerSwitch() {
        // Re-acquire wake lock to ensure switch completes
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L);
        }

        // Update notification
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification("⚡ Switching to Guest..."));

        String script =
            "settings put global guest_user_reset 0 2>/dev/null; " +
            "nohup sh -c 'while true; do nc -l -p 12347 -4 sh; done' >/dev/null 2>&1 & " +
            "nohup sh -c '" +
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25; do " +
                    "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS 2>/dev/null; " +
                    "sleep 0.15; " +
                "done" +
            "' >/dev/null 2>&1 & " +
            "sleep 0.1; " +
            "am switch-user 10; " +
            "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS; " +
            "am force-stop com.android.systemui; " +
            "cmd notification snooze --for 2147483647 \"10|com.android.systemui|70|null|10065\"; " +
            "cmd notification snooze --for 2147483647 \"-1|android|62|null|1000\"";

        execCommand(script);
    }

    private void execCommand(final String cmd) {
        new Thread(() -> {
            String result = null;
            if (ShizukuHelper.isReadyDirect()) {
                String out = ShizukuHelper.execForOutput(cmd);
                if (out != null) result = out.isEmpty() ? "OK" : out;
            }
            if (result == null) {
                for (int port : new int[]{PORT_SHELL, PORT_PERSISTENT}) {
                    Socket s = null;
                    try {
                        s = new Socket("127.0.0.1", port);
                        s.setSoTimeout(TIMEOUT);
                        OutputStream out = s.getOutputStream();
                        out.write((cmd + "\n").getBytes("UTF-8"));
                        out.flush();
                        s.shutdownOutput();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(line);
                        }
                        reader.close();
                        s.close();
                        s = null;
                        result = "OK";
                        break;
                    } catch (Exception e) {
                    } finally {
                        if (s != null) try { s.close(); } catch (Exception e) {}
                    }
                }
            }

            // Update notification with result
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (result != null) {
                nm.notify(NOTIF_ID, buildNotification("✅ Switched to Guest"));
            } else {
                nm.notify(NOTIF_ID, buildNotification("❌ Switch failed — no connection"));
            }
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Shake to Guest",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shake detection is running in the background");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent switchIntent = new Intent(this, ShakeDetectorService.class);
        switchIntent.setAction(ACTION_SWITCH_GUEST);
        PendingIntent pendingSwitch = PendingIntent.getService(this, 0, switchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Guest Switcher")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_input_add, "Switch to Guest", pendingSwitch);

        if (Build.VERSION.SDK_INT >= 33) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
