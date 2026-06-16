package com.guest.switcher;

import android.app.Activity;
import android.content.ComponentName;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.service.quicksettings.TileService;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends Activity {

    private static final int PORT_SHELL = 12345;
    private static final int PORT_EMBEDDED = 12346;
    private static final int TIMEOUT = 5000;

    private TextView statusText;
    private Button guestBtn;
    private Button ownerBtn;
    private Button testBtn;
    private boolean embeddedRunning = false;
    private EmbeddedServer embeddedServer;
    private int activePort = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        guestBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                statusText.setText("Switching to Guest...");
                execCommand("echo '===SWITCHING==='; am switch-user 10; echo 'SWITCH_EXIT='$?; nohup sh -c 'sleep 2 && am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS && sleep 1 && input keyevent 4 && cmd notification snooze --for 2147483647 \"10|com.android.systemui|70|null|10065\" && cmd notification snooze --for 2147483647 \"-1|android|62|null|1000\"' >/dev/null 2>&1 &");
            }
        });

        ownerBtn = new Button(this);
        ownerBtn.setText("Switch to Owner");
        ownerBtn.setEnabled(false);
        ownerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                statusText.setText("Switching to Owner...");
                execCommand("echo '===SWITCHING==='; am switch-user 0; echo 'SWITCH_EXIT='$?");
            }
        });

        testBtn = new Button(this);
        testBtn.setText("Test Server");
        testBtn.setEnabled(false);
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                statusText.setText("Testing...");
                execCommand("echo OK", true);
            }
        });

        Button retryBtn = new Button(this);
        retryBtn.setText("Refresh");
        retryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkServer();
            }
        });

        layout.addView(statusText);
        layout.addView(guestBtn);
        layout.addView(ownerBtn);
        layout.addView(testBtn);
        layout.addView(retryBtn);
        setContentView(layout);

        embeddedServer = new EmbeddedServer();
        embeddedServer.start();
        checkServer();
        registerTile();
    }

    private void registerTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            TileService.requestListeningState(this, new ComponentName(this, SwitchTileService.class));
        }
    }

    private void checkServer() {
        new AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... params) {
                Socket s = null;
                try {
                    s = new Socket("127.0.0.1", PORT_SHELL);
                    s.setSoTimeout(TIMEOUT);
                    s.close();
                    activePort = PORT_SHELL;
                    return 2;
                } catch (Exception e) {
                    try {
                        s = new Socket("127.0.0.1", PORT_EMBEDDED);
                        s.setSoTimeout(TIMEOUT);
                        s.close();
                        activePort = PORT_EMBEDDED;
                        return 1;
                    } catch (Exception e2) {
                        activePort = 0;
                        return 0;
                    }
                }
            }

            @Override
            protected void onPostExecute(Integer mode) {
                boolean isShell = (mode == 2);
                String modeStr = isShell ? "Shell server OK" : (mode == 1 ? "Embedded server" : "No server");
                statusText.setText(modeStr + (isShell ? " - ready" : mode == 1 ? " (limited)" : "\nTap 'Start Server via LADB'"));
                guestBtn.setEnabled(mode > 0);
                ownerBtn.setEnabled(mode > 0);
                testBtn.setEnabled(isShell);
            }
        }.execute();
    }

    private void execCommand(final String cmd) {
        execCommand(cmd, false);
    }

    private void execCommand(final String cmd, final boolean simple) {
        final int port = activePort;
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                if (port == 0) return "Server not running";
                Socket s = null;
                try {
                    s = new Socket("127.0.0.1", port);
                    s.setSoTimeout(TIMEOUT);
                    OutputStream out = s.getOutputStream();
                    out.write((cmd + "\n").getBytes("UTF-8"));
                    out.flush();
                    s.shutdownOutput();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (result.length() > 0) result.append("\n");
                        result.append(line);
                    }
                    reader.close();
                    s.close();
                    s = null;

                    String outText = result.toString().trim();
                    if (outText.isEmpty()) return "OK";
                    if (simple) return "OK";
                    return outText;
                } catch (Exception e) {
                    if (simple) return "Error";
                    return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    if (s != null) try { s.close(); } catch (Exception e) {}
                }
            }

            @Override
            protected void onPostExecute(String result) {
                statusText.setText(result);
                Toast.makeText(MainActivity.this, result, Toast.LENGTH_SHORT).show();
                if (result.startsWith("Error") || result.equals("Server not running")) {
                    guestBtn.setEnabled(false);
                    ownerBtn.setEnabled(false);
                }
            }
        }.execute();
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
