package com.guest.switcher;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

public class MainActivity extends Activity {

    private static final int PORT = 12345;
    private static final int TIMEOUT = 5000;

    private TextView statusText;
    private Button guestBtn;
    private Button ownerBtn;

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
                execCommand("am switch-user 10 && sleep 3 && cmd notification snooze --for 3600000 '10|com.android.systemui|70|null|10065'");
            }
        });

        ownerBtn = new Button(this);
        ownerBtn.setText("Switch to Owner");
        ownerBtn.setEnabled(false);
        ownerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                execCommand("am switch-user 0");
            }
        });

        Button retryBtn = new Button(this);
        retryBtn.setText("Check Server");
        retryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkServer();
            }
        });

        layout.addView(statusText);
        layout.addView(guestBtn);
        layout.addView(ownerBtn);
        layout.addView(retryBtn);
        setContentView(layout);

        checkServer();
    }

    private void checkServer() {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                Socket s = null;
                try {
                    s = new Socket("127.0.0.1", PORT);
                    s.setSoTimeout(TIMEOUT);
                    return true;
                } catch (Exception e) {
                    return false;
                } finally {
                    if (s != null) try { s.close(); } catch (Exception e) {}
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (ok) {
                    statusText.setText("Server OK - ready");
                    guestBtn.setEnabled(true);
                    ownerBtn.setEnabled(true);
                } else {
                    statusText.setText("Server not running\nOpen LADB and run:\n/data/local/tmp/gsw/server.sh");
                    guestBtn.setEnabled(false);
                    ownerBtn.setEnabled(false);
                }
            }
        }.execute();
    }

    private void execCommand(final String cmd) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                Socket s = null;
                try {
                    s = new Socket("127.0.0.1", PORT);
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
                    return outText;
                } catch (Exception e) {
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
}
