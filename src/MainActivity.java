package com.guest.switcher;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends Activity {

    private static final int PORT_SHELL = 12345;
    private static final int PORT_EMBEDDED = 12346;
    private static final int PORT_PERSISTENT = 12347;
    private static final int TIMEOUT = 5000;

    private TextView statusText;
    private Button guestBtn;
    private Button ownerBtn;
    private Button testBtn;
    private boolean embeddedRunning = false;
    private EmbeddedServer embeddedServer;
    private View indicator;
    private ProgressBar loading;
    private boolean polling = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        statusText = new TextView(this);
        statusText.setText("Checking server...");
        statusText.setTextSize(16);
        statusText.setPadding(0, 0, 0, 24);

        guestBtn = new Button(this);
        guestBtn.setText("Switch to Guest");
        guestBtn.setEnabled(false);
        guestBtn.setOnClickListener(v -> {
            statusText.setText("Switching to Guest...");
            execCommand("echo '===SWITCHING==='; nohup sh -c 'while true; do nc -l -p 12347 -4 sh; done' >/dev/null 2>&1 & sleep 1; am switch-user 10; echo 'SWITCH_EXIT='$?; nohup sh -c 'sleep 2 && am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS && sleep 1 && input keyevent 4 && cmd notification snooze --for 2147483647 \"10|com.android.systemui|70|null|10065\" && cmd notification snooze --for 2147483647 \"-1|android|62|null|1000\"' >/dev/null 2>&1 &", false, true);
        });

        ownerBtn = new Button(this);
        ownerBtn.setText("Switch to Owner");
        ownerBtn.setEnabled(false);
        ownerBtn.setOnClickListener(v -> {
            statusText.setText("Switching to Owner...");
            execCommand("echo '===SWITCHING==='; am switch-user 0; echo 'SWITCH_EXIT='$?", false, true);
        });

        testBtn = new Button(this);
        testBtn.setText("Test Server");
        testBtn.setEnabled(false);
        testBtn.setOnClickListener(v -> {
            statusText.setText("Testing...");
            execCommand("echo OK", true, true);
        });

        Button ladbBtn = new Button(this);
        ladbBtn.setText("Open LADB");
        ladbBtn.setOnClickListener(v -> openLadb());

        Button retryBtn = new Button(this);
        retryBtn.setText("Refresh");
        retryBtn.setOnClickListener(v -> checkServer());

        Button emergencyBtn = new Button(this);
        emergencyBtn.setText("Emergency");
        emergencyBtn.setOnClickListener(v -> emergencySwitch());

        layout.addView(statusText);
        layout.addView(guestBtn);
        layout.addView(ownerBtn);
        layout.addView(testBtn);
        layout.addView(ladbBtn);
        layout.addView(retryBtn);
        layout.addView(emergencyBtn);

        indicator = new View(this);
        int size = (int) (20 * getResources().getDisplayMetrics().density);
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.setMargins(0, margin, margin, 0);
        indicator.setLayoutParams(lp);
        setIndicatorColor(0xff888888);

        loading = new ProgressBar(this);
        FrameLayout.LayoutParams lpLoad = new FrameLayout.LayoutParams(
            (int) (48 * getResources().getDisplayMetrics().density),
            (int) (48 * getResources().getDisplayMetrics().density));
        lpLoad.gravity = Gravity.CENTER;
        loading.setLayoutParams(lpLoad);
        loading.setVisibility(View.GONE);

        root.addView(layout);
        root.addView(indicator);
        root.addView(loading);
        setContentView(root);

        embeddedServer = new EmbeddedServer();
        embeddedServer.start();
        registerTile();
    }

    private void emergencySwitch() {
        polling = true;
        loading.setVisibility(View.VISIBLE);
        statusText.setText("Switching to Guest mode...");
        new Thread(() -> {
            boolean online = checkServerSync(PORT_SHELL, PORT_PERSISTENT);
            runOnUiThread(() -> {
                if (online) {
                    polling = false;
                    loading.setVisibility(View.GONE);
                    setIndicatorColor(0xff00cc00);
                    doSwitch();
                } else {
                    openLadb();
                    statusText.setText("Waiting Server");
                    scheduleAlarmPoll();
                    handler.postDelayed(() -> {
                        try {
                            Intent bring = new Intent(MainActivity.this, MainActivity.class);
                            bring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(bring);
                        } catch (Exception e) {}
                    }, 500);
                }
            });
        }).start();
    }

    private void startPolling() {
        if (!polling) return;
        statusText.setText("Waiting Server");
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    private Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            new Thread(() -> {
                boolean online = checkServerSync(PORT_SHELL, PORT_PERSISTENT);
                runOnUiThread(() -> {
                    if (online) {
                        polling = false;
                        loading.setVisibility(View.GONE);
                        setIndicatorColor(0xff00cc00);
                        statusText.setText("Server found, waiting for restart...");
                        handler.removeCallbacks(pollRunnable);
                        handler.postDelayed(() -> {
                            statusText.setText("Switching to Guest mode...");
                            doSwitch();
                        }, 1500);
                    } else {
                        handler.removeCallbacks(pollRunnable);
                        handler.postDelayed(pollRunnable, 1000);
                    }
                });
            }).start();
        }
    };

    private void doSwitch() {
        statusText.setText("Switching to Guest...");
        execCommand("echo '===SWITCHING==='; nohup sh -c 'while true; do nc -l -p 12347 -4 sh; done' >/dev/null 2>&1 & sleep 1; am switch-user 10; echo 'SWITCH_EXIT='$?; nohup sh -c 'sleep 2 && am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS && sleep 1 && input keyevent 4 && cmd notification snooze --for 2147483647 \"10|com.android.systemui|70|null|10065\" && cmd notification snooze --for 2147483647 \"-1|android|62|null|1000\"' >/dev/null 2>&1 &", false, true);
    }

    private boolean checkServerSync(int... ports) {
        for (int port : ports) {
            Socket s = null;
            try {
                s = new Socket("127.0.0.1", port);
                s.setSoTimeout(2000);
                OutputStream out = s.getOutputStream();
                out.write("echo OK\n".getBytes("UTF-8"));
                out.flush();
                s.shutdownOutput();
                BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                String line = reader.readLine();
                reader.close();
                s.close();
                s = null;
                if (line != null && line.equals("OK")) return true;
            } catch (Exception e) {
            } finally {
                if (s != null) try { s.close(); } catch (Exception e) {}
            }
        }
        return false;
    }

    private void openLadb() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.draco.ladb");
            if (intent == null) {
                statusText.setText("LADB not installed");
                loading.setVisibility(View.GONE);
                polling = false;
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            statusText.setText("Error: " + e.getClass().getSimpleName());
            loading.setVisibility(View.GONE);
            polling = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (polling) {
            scheduleAlarmPoll();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("emergency_poll", false)) {
            intent.removeExtra("emergency_poll");
            polling = true;
            statusText.setText("Waiting for server...");
            loading.setVisibility(View.VISIBLE);
            startPolling();
        } else if (!polling) {
            checkServer();
        }
    }

    private void scheduleAlarmPoll() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent check = new Intent(this, PollReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, check, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 2000, pi), pi);
    }

    private void setIndicatorColor(int color) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setSize(20, 20);
        circle.setColor(color);
        indicator.setBackground(circle);
    }

    private void registerTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            TileService.requestListeningState(this, new ComponentName(this, SwitchTileService.class));
        }
    }

    private void checkServer() {
        new Thread(() -> {
            int level;
            if (checkServerSync(PORT_SHELL, PORT_PERSISTENT)) {
                level = 2;
            } else if (checkServerSync(PORT_EMBEDDED)) {
                level = 1;
            } else {
                level = 0;
            }
            final int fLevel = level;
            runOnUiThread(() -> {
                int[] colors = {0xffcc0000, 0xffff8800, 0xff00cc00};
                String[] texts = {"server is offline", "embedded only", "server is online"};
                setIndicatorColor(colors[fLevel]);
                statusText.setText(texts[fLevel]);
                boolean enabled = fLevel == 2;
                guestBtn.setEnabled(enabled);
                ownerBtn.setEnabled(enabled);
                testBtn.setEnabled(enabled);
                loading.setVisibility(View.GONE);
            });
        }).start();
    }

    private void execCommand(final String cmd) {
        execCommand(cmd, false, false);
    }

    private void execCommand(final String cmd, final boolean simple) {
        execCommand(cmd, simple, false);
    }

    private void execCommand(final String cmd, final boolean simple, final boolean shellOnly) {
        final int[] tryPorts = shellOnly ? new int[]{PORT_SHELL, PORT_PERSISTENT} : new int[]{PORT_SHELL, PORT_PERSISTENT, PORT_EMBEDDED};
        loading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String lastError = "";
            String result = null;
            for (int port : tryPorts) {
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

                    String outText = sb.toString().trim();
                    if (outText.isEmpty() || simple) {
                        result = "OK";
                    } else {
                        result = outText;
                    }
                    break;
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    if (s != null) try { s.close(); } catch (Exception e) {}
                }
            }
            final String finalResult = result;
            final String finalError = simple ? "Error" : "Error: " + lastError;
            runOnUiThread(() -> {
                loading.setVisibility(View.GONE);
                if (finalResult != null) {
                    statusText.setText(finalResult);
                    setIndicatorColor(0xff00cc00);
                } else {
                    statusText.setText(finalError);
                    setIndicatorColor(0xffcc0000);
                    guestBtn.setEnabled(false);
                    ownerBtn.setEnabled(false);
                }
            });
        }).start();
    }

    private class EmbeddedServer extends Thread {
        private ServerSocket serverSocket;
        private boolean running = true;

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(PORT_EMBEDDED);
                embeddedRunning = true;
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        client.setSoTimeout(TIMEOUT);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
                        String cmd = reader.readLine();
                        reader.close();

                        StringBuilder output = new StringBuilder();
                        if (cmd != null && !cmd.isEmpty()) {
                            try {
                                Process proc = Runtime.getRuntime().exec(cmd);
                                BufferedReader procOut = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
                                String line;
                                while ((line = procOut.readLine()) != null) {
                                    if (output.length() > 0) output.append("\n");
                                    output.append(line);
                                }
                                proc.waitFor();
                                int exit = proc.exitValue();
                                if (output.length() == 0) output.append("exit=").append(exit);
                            } catch (Exception e) {
                                output.append("Error: ").append(e.getMessage());
                            }
                        }

                        OutputStream out = client.getOutputStream();
                        out.write((output.toString() + "\n").getBytes("UTF-8"));
                        out.flush();
                        client.close();
                    } catch (Exception e) {
                        // connection error, continue
                    }
                }
            } catch (Exception e) {
                embeddedRunning = false;
            }
        }

        public void shutdown() {
            running = false;
            try { serverSocket.close(); } catch (Exception e) {}
        }
    }
}
