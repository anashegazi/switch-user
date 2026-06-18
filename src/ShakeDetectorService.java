package com.guest.switcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

public class ShakeDetectorService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "shake_channel";
    private static final int NOTIF_ID = 1001;

    // Shake detection parameters
    private static final float SHAKE_THRESHOLD = 12.0f;
    private static final int SHAKE_COUNT_THRESHOLD = 3;
    private static final long SHAKE_WINDOW_MS = 1000;
    private static final long COOLDOWN_MS = 3000;

    private static final int PORT_SHELL = 12345;
    private static final int PORT_PERSISTENT = 12347;
    private static final int TIMEOUT = 5000;

    private SensorManager sensorManager;
    private PowerManager.WakeLock wakeLock;

    private long lastShakeTime = 0;
    private int shakeCount = 0;
    private boolean cooldown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("🫨 Shake to Guest is active"));

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guest_switcher:shake_wakelock");
        wakeLock.acquire(10 * 60 * 1000L); // 10 minutes max, re-acquired on each shake
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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

        if (acceleration > SHAKE_THRESHOLD) {
            long now = System.currentTimeMillis();

            if (cooldown) return;

            if (now - lastShakeTime > SHAKE_WINDOW_MS) {
                shakeCount = 0;
            }

            lastShakeTime = now;
            shakeCount++;

            if (shakeCount >= SHAKE_COUNT_THRESHOLD) {
                shakeCount = 0;
                cooldown = true;
                triggerSwitch();

                // Reset cooldown after delay
                new Thread(() -> {
                    try { Thread.sleep(COOLDOWN_MS); } catch (InterruptedException e) {}
                    cooldown = false;
                }).start();
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
            if (ShizukuHelper.isReady()) {
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
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Guest Switcher")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true);

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
