package com.guest.switcher;

import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class ShizukuHelper {
    private static boolean initDone = false;
    private static boolean binderReady = false;
    private static boolean permGranted = false;

    public static void init() {
        if (initDone) return;
        initDone = true;

        Shizuku.addBinderReceivedListenerSticky(() -> {
            binderReady = true;
            try {
                permGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) {
                permGranted = false;
            }
        });

        Shizuku.addBinderDeadListener(() -> {
            binderReady = false;
            permGranted = false;
        });
    }

    public static boolean isReady() {
        return binderReady && permGranted;
    }

    public static boolean isRunning() {
        if (!binderReady) {
            try { binderReady = Shizuku.pingBinder(); } catch (Exception e) { }
        }
        return binderReady;
    }

    public static boolean isPermissionGranted() {
        if (binderReady && !permGranted) {
            try { permGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; } catch (Exception e) { }
        }
        return permGranted;
    }

    public static void requestPermission(int requestCode) {
        Shizuku.requestPermission(requestCode);
    }

    public static void addPermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        Shizuku.addRequestPermissionResultListener(listener);
    }

    public static void removePermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        Shizuku.removeRequestPermissionResultListener(listener);
    }

    public static void addBinderListener(Shizuku.OnBinderReceivedListener listener) {
        Shizuku.addBinderReceivedListenerSticky(listener);
    }

    public static void removeBinderListener(Shizuku.OnBinderReceivedListener listener) {
        Shizuku.removeBinderReceivedListener(listener);
    }

    public static String execForOutput(String command) {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            ShizukuRemoteProcess proc = (ShizukuRemoteProcess) m.invoke(null, new String[]{"sh", "-c", command}, null, null);
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
            proc.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }
}
