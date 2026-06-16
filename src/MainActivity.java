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
import android.widget.ScrollView;
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

    private static final int ACCENT = 0xFF1976D2;
    private static final int DANGER = 0xFFD32F2F;
    private static final int SUCCESS = 0xFF388E3C;
    private static final int WARNING = 0xFFF57F17;
    private static final int OFFLINE = 0xFFD32F2F;
    private static final int CARD_BG = 0xFFFFFFFF;
    private static final int PAGE_BG = 0xFFF5F5F5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final float dp = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAGE_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad16 = (int)(16 * dp);
        root.setPadding(pad16, pad16, pad16, pad16);

        // â”€â”€ Status Card â”€â”€
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        int pad12 = (int)(12 * dp);
        statusCard.setPadding(pad12, pad12, pad12, pad12);
        statusCard.setElevation(2 * dp);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(12 * dp);
        cardBg.setColor(CARD_BG);
        statusCard.setBackground(cardBg);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, pad16);
        statusCard.setLayoutParams(cardLp);

        indicator = new View(this);
        int dotSize = (int)(12 * dp);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotLp.setMargins(0, 0, (int)(8 * dp), 0);
        indicator.setLayoutParams(dotLp);
        setIndicatorColor(0xff888888);

        loading = new ProgressBar(this);
        int loadSize = (int)(18 * dp);
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(loadSize, loadSize);
        loadLp.setMargins(0, 0, (int)(8 * dp), 0);
        loading.setLayoutParams(loadLp);
        loading.setVisibility(View.GONE);

        statusText = new TextView(this);
        statusText.setText("Checking server...");
        statusText.setTextSize(14);
        statusText.setTextColor(0xFF333333);
        statusText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        statusCard.addView(indicator);
        statusCard.addView(loading);
        statusCard.addView(statusText);

        // â”€â”€ Section: Switch â”€â”€
        TextView switchHeader = new TextView(this);
        switchHeader.setText("SWITCH");
        switchHeader.setTextSize(11);
        switchHeader.setTextColor(0xFF999999);
        int pad8 = (int)(8 * dp);
        switchHeader.setPadding(0, 0, 0, pad8);

        guestBtn = makePrimary("ðŸ  Switch to Guest");
        guestBtn.setEnabled(false);
        guestBtn.setOnClickListener(v -> {
            setBtnLoading(guestBtn, true);
            statusText.setText("Switching to Guest...");
            setIndicatorColor(WARNING);
            execCommand("echo '===SWITCHING==='; nohup sh -c 'while true; do nc -l -p 12347 -4 sh; done' >/dev/null 2>&1 & sleep 1; am switch-user 10; echo 'SWITCH_EXIT='$?; nohup sh -c 'sleep 2 && am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS && sleep 1 && input keyevent 4 && cmd notification snooze --for 2147483647 \"10|com.android.systemui|70|null|10065\" && cmd notification snooze --for 2147483647 \"-1|android|62|null|1000\"' >/dev/null 2>&1 &", false, true, guestBtn);
        });

        ownerBtn = makePrimary("ðŸ  Switch to Owner");
        ownerBtn.setEnabled(false);
        ownerBtn.setOnClickListener(v -> {
            setBtnLoading(ownerBtn, true);
            statusText.setText("Switching to Owner...");
            setIndicatorColor(WARNING);
            execCommand("echo '===SWITCHING==='; am switch-user 0; echo 'SWITCH_EXIT='$?", false, true, ownerBtn);
        });

        // â”€â”€ Section: Tools â”€â”€
        TextView toolsHeader = new TextView(this);
        toolsHeader.setText("TOOLS");
        toolsHeader.setTextSize(11);
        toolsHeader.setTextColor(0xFF999999);
        toolsHeader.setPadding(0, pad16, 0, pad8);

        testBtn = makeSecondary("ðŸ”§ Test Server");
        testBtn.setEnabled(false);
        testBtn.setOnClickListener(v -> {
            setBtnLoading(testBtn, true);
            statusText.setText("Testing...");
            setIndicatorColor(WARNING);
            execCommand("echo OK", true, true, testBtn);
        });

        Button ladbBtn = makeSecondary("ðŸ“¡ Open LADB");
        ladbBtn.setOnClickListener(v -> openLadb());

        Button retryBtn = makeSecondary("ðŸ”„ Refresh");
        retryBtn.setOnClickListener(v -> checkServer());

        // â”€â”€ Divider â”€â”€
        View divider = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.setMargins(0, pad16, 0, pad16);
        divider.setLayoutParams(divLp);
        divider.setBackgroundColor(0xFFE0E0E0);

        // â”€â”€ Emergency â”€â”€
        Button emergencyBtn = makeDanger("âš ï¸ Emergency");
        emergencyBtn.setOnClickListener(v -> emergencySwitch());

        // â”€â”€ Footer â”€â”€
        TextView footer = new TextView(this);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        String timeStr = sdf.format(new java.util.Date());
        footer.setText("Last updated: " + timeStr);
        footer.setTextSize(11);
        footer.setTextColor(0xFFBBBBBB);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, pad16, 0, 0);

        // â”€â”€ Assemble â”€â”€
        root.addView(statusCard);
        root.addView(switchHeader);
        root.addView(guestBtn);
        root.addView(ownerBtn);
        root.addView(toolsHeader);
        root.addView(testBtn);
        root.addView(ladbBtn);
        root.addView(retryBtn);
        root.addView(divider);
        root.addView(emergencyBtn);
        root.addView(footer);

        scroll.addView(root);
        setContentView(scroll);

        embeddedServer = new EmbeddedServer();
        embeddedServer.start();
        registerTile();
    }

    private Button makePrimary(String text) {
        final float dp = getResources().getDisplayMetrics().density;
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTextColor(0xFFFFFFFF);
        btn.setAllCaps(false);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(10 * dp);
        bg.setColor(ACCENT);
        btn.setBackground(bg);
        btn.setElevation(3 * dp);
        int pv = (int)(14 * dp);
        int ph = (int)(20 * dp);
        btn.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, (int)(8 * dp));
        btn.setLayoutParams(lp);
        return btn;
    }

    private Button makeSecondary(String text) {
        final float dp = getResources().getDisplayMetrics().density;
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTextColor(0xFF666666);
        btn.setAllCaps(false);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(10 * dp);
        bg.setColor(CARD_BG);
        bg.setStroke((int)(1 * dp + 0.5f), 0xFFD0D0D0);
        btn.setBackground(bg);
        int pv = (int)(12 * dp);
        int ph = (int)(20 * dp);
        btn.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, (int)(8 * dp));
        btn.setLayoutParams(lp);
        return btn;
    }

    private Button makeDanger(String text) {
        final float dp = getResources().getDisplayMetrics().density;
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTextColor(0xFFFFFFFF);
        btn.setAllCaps(false);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(10 * dp);
        bg.setColor(DANGER);
        btn.setBackground(bg);
        int pv = (int)(14 * dp);
        int ph = (int)(20 * dp);
        btn.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void setBtnLoading(Button btn, boolean loading) {
        btn.setEnabled(!loading);
        if (loading) {
            btn.setTag(btn.getText().toString());
            btn.setText("◌ " + btn.getText().toString().replaceAll("[^\\p{L} ]", "").trim());
        } else {
            Object orig = btn.getTag();
            if (orig != null) btn.setText((String) orig);
        }
    }
    private void restoreBtn(Button btn) {
        if (btn != null) setBtnLoading(btn, false);
    }

    private void emergencySwitch() {
        polling = true;
        loading.setVisibility(View.VISIBLE);
        statusText.setText("Switching to Guest mode...");
        setIndicatorColor(WARNING);
        new Thread(() -> {
            boolean online = checkServerSync(PORT_SHELL, PORT_PERSISTENT);
            runOnUiThread(() -> {
                if (online) {
                    polling = false;
                    loading.setVisibility(View.GONE);
                    setIndicatorColor(SUCCESS);
                    doSwitch();
                } else {
                    openLadb();
                    statusText.setText("Waiting Server");
                    setIndicatorColor(WARNING);
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
        setIndicatorColor(WARNING);
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
                        setIndicatorColor(SUCCESS);
                        statusText.setText("Switching to Guest mode...");
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
        execCommand("echo '===SWITCHING==='; nohup sh -c 'while true; do nc -l -p 12347 -4 sh; done' >/dev/null 2>&1 & sleep 1; am switch-user 10; echo 'SWITCH_EXIT='$?; nohup sh -c 'sleep 2 && am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS && sleep 1 && input keyevent 4 && cmd notification snooze --for 2147483647 \"10|com.android.systemui|70|null|10065\" && cmd notification snooze --for 2147483647 \"-1|android|62|null|1000\"' >/dev/null 2>&1 &", false, true, null);
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
                statusText.setText("LADB is not installed");
                loading.setVisibility(View.GONE);
                setIndicatorColor(OFFLINE);
                polling = false;
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            statusText.setText("Could not open LADB");
            loading.setVisibility(View.GONE);
            setIndicatorColor(OFFLINE);
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
            statusText.setText("Waiting Server");
            setIndicatorColor(WARNING);
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
        float dp = getResources().getDisplayMetrics().density;
        circle.setSize((int)(12 * dp), (int)(12 * dp));
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
                int[] colors = {OFFLINE, WARNING, SUCCESS};
                String[] texts = {"Disconnected", "Limited", "Connected"};
                setIndicatorColor(colors[fLevel]);
                statusText.setText(texts[fLevel]);
                boolean enabled = fLevel == 2;
                guestBtn.setEnabled(enabled);
                ownerBtn.setEnabled(enabled);
                testBtn.setEnabled(enabled);
                loading.setVisibility(View.GONE);
                restoreBtn(guestBtn);
                restoreBtn(ownerBtn);
                restoreBtn(testBtn);
            });
        }).start();
    }

    private void execCommand(final String cmd) {
        execCommand(cmd, false, false, null);
    }

    private void execCommand(final String cmd, final boolean simple, final boolean shellOnly, final Button triggerBtn) {
        final int[] tryPorts = shellOnly ? new int[]{PORT_SHELL, PORT_PERSISTENT} : new int[]{PORT_SHELL, PORT_PERSISTENT, PORT_EMBEDDED};
        loading.setVisibility(View.VISIBLE);
        new Thread(() -> {
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
                } finally {
                    if (s != null) try { s.close(); } catch (Exception e) {}
                }
            }
            final String finalResult = result;
            runOnUiThread(() -> {
                loading.setVisibility(View.GONE);
                restoreBtn(triggerBtn);
                if (finalResult != null) {
                    statusText.setText(finalResult);
                    if (!"OK".equals(finalResult))
                        setIndicatorColor(SUCCESS);
                } else {
                    statusText.setText("Connection failed");
                    setIndicatorColor(OFFLINE);
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

