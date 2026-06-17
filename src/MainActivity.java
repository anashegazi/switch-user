package com.guest.switcher;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import android.content.pm.PackageManager;

public class MainActivity extends Activity {

    private static final int PORT_SHELL = 12345;
    private static final int PORT_EMBEDDED = 12346;
    private static final int PORT_PERSISTENT = 12347;
    private static final int TIMEOUT = 5000;

    private Button guestBtn;
    private Button ownerBtn;
    private Button testBtn;
    private View indicator;
    private TextView statusText;
    private boolean embeddedRunning = false;
    private EmbeddedServer embeddedServer;
    private boolean polling = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    private static final int ACCENT = 0xFF6366F1;
    private static final int DANGER = 0xFFEF4444;
    private static final int PAGE_BG = 0xFFF8FAFC;
    private static final int TEXT_PRIMARY = 0xFF0F172A;
    private static final int TEXT_MUTED = 0xFF475569;
    private static final int SUCCESS = 0xFF10B981;
    private static final int WARNING = 0xFFF59E0B;
    private static final int OFFLINE = 0xFFEF4444;
    private static final int BORDER = 0xFF0F172A;
    private static final int SEC_HOVER = 0xFFFBBF24;
    private static final int SHADOW_OFF = 4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final float dp = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAGE_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad16 = (int)(16 * dp);
        int shadowOff = dpToPx(SHADOW_OFF);
        int borderPx = dpToPx(2);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int btnWidth = Math.min((int)(screenW * 0.85f), dpToPx(320));

        // ── Header Banner ──
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setGravity(Gravity.CENTER);
        int bannerH = (int)(56 * dp);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(-1, bannerH);
        bannerLp.setMargins(0, 0, 0, pad16);
        banner.setLayoutParams(bannerLp);

        GlitchTextView bannerText = new GlitchTextView(this, "Guest Switcher");
        banner.addView(bannerText, new LinearLayout.LayoutParams(-1, -1));
        banner.setBackground(createBannerBg());

        // Bouncy entrance
        banner.setScaleX(0.85f);
        banner.setScaleY(0.85f);
        banner.setAlpha(0f);
        banner.postDelayed(() -> {
            banner.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(400)
                .setInterpolator(new android.view.animation.BounceInterpolator())
                .start();
        }, 100);

        // ── Status Chip ──
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int chipPad = (int)(10 * dp);
        chip.setPadding(chipPad, chipPad, chipPad, chipPad);
        chip.setBackground(createHardShadowBg(dpToPx(999), 0xFFFFFFFF, shadowOff));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-1, -2);
        chipLp.setMargins(0, 0, 0, pad16);
        chip.setLayoutParams(chipLp);

        indicator = new View(this);
        int dotSize = (int)(12 * dp);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotLp.setMargins(0, 0, (int)(8 * dp), 0);
        indicator.setLayoutParams(dotLp);

        statusText = new TextView(this);
        statusText.setText("Checking server...");
        statusText.setTextSize(13);
        statusText.setTextColor(TEXT_PRIMARY);
        statusText.setTypeface(null, android.graphics.Typeface.BOLD);

        chip.addView(indicator);
        chip.addView(statusText);

        guestBtn = makePrimary("Switch to Guest", btnWidth);
        guestBtn.setEnabled(false);
        guestBtn.setOnClickListener(v -> {
            setBtnLoading(guestBtn, true);
            doSwitch();
        });

        ownerBtn = makePrimary("Switch to Owner", btnWidth);
        ownerBtn.setEnabled(false);
        ownerBtn.setOnClickListener(v -> {
            setBtnLoading(ownerBtn, true);
            execCommand("echo '===SWITCHING==='; am switch-user 0; echo 'SWITCH_EXIT='$?", false, true, ownerBtn);
        });

        testBtn = makeSecondary("Test Server", btnWidth);
        testBtn.setEnabled(false);
        testBtn.setOnClickListener(v -> {
            setBtnLoading(testBtn, true);
            execCommand("echo OK", true, true, testBtn);
        });

        Button shizukuBtn = makeSecondary("Open Shizuku", btnWidth);
        shizukuBtn.setOnClickListener(v -> openShizuku());

        Button retryBtn = makeSecondary("Refresh", btnWidth);
        retryBtn.setOnClickListener(v -> checkServer());

        Button emergencyBtn = makeDanger("Emergency", btnWidth);
        emergencyBtn.setOnClickListener(v -> emergencySwitch());

        root.addView(banner);
        root.addView(chip);

        root.addView(makeDivider());
        root.addView(guestBtn);
        root.addView(ownerBtn);

        root.addView(makeDivider());
        root.addView(testBtn);
        root.addView(shizukuBtn);
        root.addView(retryBtn);

        View emergDiv = makeDivider();
        LinearLayout.LayoutParams emergDivLp = (LinearLayout.LayoutParams) emergDiv.getLayoutParams();
        emergDivLp.topMargin = dpToPx(12);
        emergDivLp.bottomMargin = dpToPx(12);
        root.addView(emergDiv);
        root.addView(emergencyBtn);

        scroll.addView(root);
        setContentView(scroll);

        embeddedServer = new EmbeddedServer();
        embeddedServer.start();
        registerTile();

        ShizukuHelper.init();
        ShizukuHelper.addPermissionListener((requestCode, grantResult) -> {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                checkServer();
            }
        });
    }

    private Button makePrimary(String text, int width) {
        final float dp = getResources().getDisplayMetrics().density;
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(text);
        btn.setTextSize(15);
        btn.setTextColor(0xFFFFFFFF);
        btn.setAllCaps(false);
        btn.setMinHeight(52);
        btn.setMinimumHeight(52);
        int shadowOff = dpToPx(SHADOW_OFF);
        btn.setBackground(createHardShadowBg(dpToPx(999), ACCENT, shadowOff));
        setGeometricTouch(btn, ACCENT, false);
        int pv = (int)(14 * dp);
        int ph = (int)(28 * dp);
        btn.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, -2);
        lp.setMargins(0, 0, 0, (int)(12 * dp));
        btn.setLayoutParams(lp);
        return btn;
    }

    private Button makeSecondary(String text, int width) {
        final float dp = getResources().getDisplayMetrics().density;
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(text);
        btn.setTextSize(15);
        btn.setTextColor(TEXT_PRIMARY);
        btn.setAllCaps(false);
        btn.setMinHeight(52);
        btn.setMinimumHeight(52);
        int shadowOff = dpToPx(SHADOW_OFF);
        btn.setBackground(createHardShadowBg(dpToPx(999), 0xFFFFFFFF, shadowOff));
        setGeometricTouch(btn, 0xFFFFFFFF, true);
        int pv = (int)(12 * dp);
        int ph = (int)(28 * dp);
        btn.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, -2);
        lp.setMargins(0, 0, 0, (int)(12 * dp));
        btn.setLayoutParams(lp);
        return btn;
    }

    private Button makeDanger(String text, int width) {
        final float dp = getResources().getDisplayMetrics().density;
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(text);
        btn.setTextSize(15);
        btn.setTextColor(0xFFFFFFFF);
        btn.setAllCaps(true);
        btn.setMinHeight(52);
        btn.setMinimumHeight(52);
        int shadowOff = dpToPx(SHADOW_OFF);
        btn.setBackground(createHardShadowBg(dpToPx(999), DANGER, shadowOff));
        setGeometricTouch(btn, DANGER, false);
        int pv = (int)(14 * dp);
        int ph = (int)(28 * dp);
        btn.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, -2);
        lp.setMargins(0, 0, 0, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void setBtnLoading(Button btn, boolean loading) {
        btn.setEnabled(!loading);
        if (loading) {
            btn.setTag(btn.getText().toString());
            LoaderDrawable d = new LoaderDrawable(getResources().getDisplayMetrics().density);
            d.setBounds(0, 0, dpToPx(48), dpToPx(48));
            btn.setCompoundDrawables(d, null, null, null);
            d.start();
        } else {
            btn.setCompoundDrawables(null, null, null, null);
        }
    }
    private void restoreBtn(Button btn) {
        if (btn != null) setBtnLoading(btn, false);
    }

    private View makeDivider() {
        float dp = getResources().getDisplayMetrics().density;
        View div = new View(this);
        div.setBackgroundColor(BORDER);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int divW = (int)(screenW * 0.75f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(divW, dpToPx(2));
        lp.setMargins(0, dpToPx(20), 0, dpToPx(20));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        div.setLayoutParams(lp);
        return div;
    }

    private int dpToPx(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Drawable createHardShadowBg(int radius, int bgColor, int shadowOff) {
        GradientDrawable shadow = new GradientDrawable();
        shadow.setShape(GradientDrawable.RECTANGLE);
        shadow.setCornerRadius(radius);
        shadow.setColor(BORDER);

        GradientDrawable main = new GradientDrawable();
        main.setShape(GradientDrawable.RECTANGLE);
        main.setCornerRadius(radius);
        main.setColor(bgColor);
        main.setStroke(dpToPx(2), BORDER);

        LayerDrawable ld = new LayerDrawable(new Drawable[]{shadow, main});
        ld.setLayerInset(1, 0, 0, shadowOff, shadowOff);
        return ld;
    }

    private Drawable createPlainBg(int radius, int bgColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radius);
        gd.setColor(bgColor);
        gd.setStroke(dpToPx(2), BORDER);
        return gd;
    }

    private Drawable createBannerBg() {
        int sh = dpToPx(SHADOW_OFF);
        int bp = dpToPx(2);
        GradientDrawable shadow = new GradientDrawable();
        shadow.setShape(GradientDrawable.RECTANGLE);
        shadow.setColor(BORDER);

        GradientDrawable main = new GradientDrawable();
        main.setShape(GradientDrawable.RECTANGLE);
        main.setColor(ACCENT);
        main.setStroke(bp, BORDER);

        LayerDrawable ld = new LayerDrawable(new Drawable[]{shadow, main});
        ld.setLayerInset(1, 0, 0, 0, sh);
        return ld;
    }

    private void setGeometricTouch(Button btn, int bgColor, boolean isSecondary) {
        int shadowOff = dpToPx(SHADOW_OFF);
        int radius = dpToPx(999);
        btn.setOnTouchListener((v, event) -> {
            if (!v.isEnabled()) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                int pressBg = isSecondary ? SEC_HOVER : bgColor;
                v.setBackground(createPlainBg(radius, pressBg));
                v.setTranslationX(shadowOff);
                v.setTranslationY(shadowOff);
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setBackground(createHardShadowBg(radius, bgColor, shadowOff));
                v.setTranslationX(0);
                v.setTranslationY(0);
            }
            return false;
        });
    }

    private void setChip(int color, String text) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        float d = getResources().getDisplayMetrics().density;
        circle.setSize((int)(10 * d), (int)(10 * d));
        circle.setColor(color);
        indicator.setBackground(circle);
        statusText.setText(text);
    }

    private void emergencySwitch() {
        polling = true;
        setChip(WARNING, "Waiting Server\u2026");
        new Thread(() -> {
            boolean online = checkServerSync(PORT_SHELL, PORT_PERSISTENT);
            runOnUiThread(() -> {
                if (online) {
                    polling = false;
                    setChip(SUCCESS, "Connected");
                    doSwitch();
                } else {
                    openShizuku();
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
        setChip(WARNING, "Waiting Server\u2026");
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
                        handler.removeCallbacks(pollRunnable);
                        handler.postDelayed(() -> {
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
        restoreBtn(guestBtn);
        restoreBtn(ownerBtn);
        String script =
            "settings put global guest_user_reset 0 2>/dev/null; " +
            "nohup sh -c 'while true; do nc -l -p 12347 -4 sh; done' >/dev/null 2>&1 & " +
            "nohup sh -c 'am switch-user 10' >/dev/null 2>&1 & " +
            "for i in 1 2 3 4 5 6 7 8; do " +
                "input tap 540 2100; input tap 300 2050; input tap 780 2050; " +
                "sleep 0.08; " +
            "done; " +
            "input keyevent 4; " +
            "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS; " +
            "cmd notification snooze --for 2147483647 \"10|com.android.systemui|70|null|10065\"; " +
            "cmd notification snooze --for 2147483647 \"-1|android|62|null|1000\"";
        execCommand(script, false, true, null);
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

    private void openShizuku() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setPackage("moe.shizuku.manager");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://shizuku.rikka.app/download/"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e2) {}
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
            setChip(WARNING, "Waiting Server\u2026");
            startPolling();
        } else if (!polling) {
            if (ShizukuHelper.isRunning() && !ShizukuHelper.isPermissionGranted()) {
                setChip(WARNING, "Grant Shizuku permission\u2026");
                ShizukuHelper.requestPermission(1001);
            }
            checkServer();
        }
    }

    private void scheduleAlarmPoll() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent check = new Intent(this, PollReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, check, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 2000, pi), pi);
    }

    private void registerTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            TileService.requestListeningState(this, new ComponentName(this, SwitchTileService.class));
        }
    }

    private void checkServer() {
        new Thread(() -> {
            int level;
            if (ShizukuHelper.isReady()) {
                level = 3;
            } else if (checkServerSync(PORT_SHELL, PORT_PERSISTENT)) {
                level = 2;
            } else if (checkServerSync(PORT_EMBEDDED)) {
                level = 1;
            } else {
                level = 0;
            }
            final int fLevel = level;
            runOnUiThread(() -> {
                boolean enabled = fLevel == 3 || fLevel == 2;
                guestBtn.setEnabled(enabled);
                ownerBtn.setEnabled(enabled);
                testBtn.setEnabled(enabled);
                restoreBtn(guestBtn);
                restoreBtn(ownerBtn);
                restoreBtn(testBtn);
                if (fLevel == 3) setChip(ACCENT, "Shizuku");
                else if (fLevel == 2) setChip(SUCCESS, "Connected");
                else if (fLevel == 1) setChip(WARNING, "Limited");
                else setChip(OFFLINE, "Disconnected");
            });
        }).start();
    }

    private void execCommand(final String cmd) {
        execCommand(cmd, false, false, null);
    }

    private void execCommand(final String cmd, final boolean simple, final boolean shellOnly, final Button triggerBtn) {
        new Thread(() -> {
            String result = null;
            if (ShizukuHelper.isReady()) {
                String out = ShizukuHelper.execForOutput(cmd);
                if (out != null) result = simple ? "OK" : (out.isEmpty() ? "OK" : out);
            }
            if (result == null) {
                final int[] tryPorts = shellOnly ? new int[]{PORT_SHELL, PORT_PERSISTENT} : new int[]{PORT_SHELL, PORT_PERSISTENT, PORT_EMBEDDED};
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
            }
            final String finalResult = result;
            runOnUiThread(() -> {
                restoreBtn(triggerBtn);
                if (finalResult == null) {
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

    // ── Glitch Text View ──
    private class GlitchTextView extends android.view.View {
        private final String text;
        private final android.graphics.Paint basePaint;
        private final android.graphics.Paint magentaPaint;
        private final android.graphics.Paint cyanPaint;
        private boolean glitching;
        private float offsetX, offsetY;

        GlitchTextView(android.content.Context context, String txt) {
            super(context);
            this.text = txt.toUpperCase();
            float density = context.getResources().getDisplayMetrics().density;
            float textSize = 20 * density;

            basePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            basePaint.setColor(0xFFFFFFFF);
            basePaint.setTextSize(textSize);
            basePaint.setFakeBoldText(true);
            basePaint.setTextAlign(android.graphics.Paint.Align.CENTER);

            magentaPaint = new android.graphics.Paint(basePaint);
            magentaPaint.setColor(0xFFFF00FF);
            magentaPaint.setAlpha(204);

            cyanPaint = new android.graphics.Paint(basePaint);
            cyanPaint.setColor(0xFF00D4FF);
            cyanPaint.setAlpha(204);

            startGlitchCycle();
        }

        private void startGlitchCycle() {
            final int[] phases = {
                -3, 1,   // 0
                 3, -1,  // 1
                -2, 3,   // 2
                 2, -2,  // 3
                -4, -2,  // 4
                 1, -1,  // 5
                -3, 1,   // 6
                 3, -1,  // 7
                 0, 0    // 8 — brief pause
            };
            glitching = true;
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofInt(0, 2000);
            anim.setDuration(2000);
            anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            anim.addUpdateListener(a -> {
                int idx = ((int) a.getAnimatedValue() / 60) % 9;
                offsetX = phases[idx * 2];
                offsetY = phases[idx * 2 + 1];
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            android.graphics.Paint.FontMetrics fm = basePaint.getFontMetrics();
            float textY = cy - (fm.ascent + fm.descent) / 2f;

            if (glitching) {
                float dp = getResources().getDisplayMetrics().density;
                canvas.drawText(text, cx + offsetX * dp * 0.6f, textY + offsetY * dp * 0.6f, cyanPaint);
                canvas.drawText(text, cx - offsetX * dp * 0.6f, textY - offsetY * dp * 0.6f, magentaPaint);
            }

            canvas.drawText(text, cx, textY, basePaint);
        }
    }

    // ── CSS-style Loader Drawable ──
    private static class LoaderDrawable extends android.graphics.drawable.Drawable implements android.graphics.drawable.Animatable {
        private final android.graphics.Paint ringPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint arcPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private float rotation;
        private android.animation.ValueAnimator animator;

        LoaderDrawable(float density) {
            this.density = density;
            ringPaint.setStyle(android.graphics.Paint.Style.STROKE);
            ringPaint.setStrokeWidth(3 * density);
            ringPaint.setColor(0xFFFFFFFF);

            arcPaint.setStyle(android.graphics.Paint.Style.STROKE);
            arcPaint.setStrokeWidth(3 * density);
            arcPaint.setColor(0xFFFF3D00);
            arcPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) / 2f - 3 * density;

            canvas.drawCircle(cx, cy, r, ringPaint);

            canvas.save();
            canvas.rotate(rotation, cx, cy);
            float afterR = r + 4 * density;
            android.graphics.RectF arcBounds = new android.graphics.RectF(cx - afterR, cy - afterR, cx + afterR, cy + afterR);
            canvas.drawArc(arcBounds, 90, 180, false, arcPaint);
            canvas.restore();
        }

        @Override
        public void start() {
            if (animator == null) {
                animator = android.animation.ValueAnimator.ofFloat(0, 360);
                animator.setDuration(1000);
                animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                animator.setInterpolator(null);
                animator.addUpdateListener(a -> { rotation = (float) a.getAnimatedValue(); invalidateSelf(); });
            }
            animator.start();
        }

        @Override public void stop() { if (animator != null) animator.cancel(); }
        @Override public boolean isRunning() { return animator != null && animator.isRunning(); }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
