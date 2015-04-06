package edu.rit.jitternetreceiver;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class MainActivity extends Activity {
    TextView textServerIP;
    EditText editServerPort;
    Button buttonStart;
    TextView textLog;

    String ip;
    int port;

    JitterNetReceiver jnr;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // initialize layout variables
        textServerIP = (TextView) findViewById(R.id.textServerIP);
        editServerPort = (EditText) findViewById(R.id.editServerPort);
        buttonStart = (Button) findViewById(R.id.buttonStart);
        textLog = (TextView) findViewById(R.id.textLog);

        ip = getLocalIpAddress();
        port = Integer.valueOf(editServerPort.getText().toString());
        textLog.append("Server IP: " + ip + ": " + port + "\n");
        buttonStart.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if(buttonStart.getText().toString().equals("Start")) {
                    buttonStart.setText("Stop");

                    textLog.append("Starting server...\n");
                    jnr = new JitterNetReceiver(MainActivity.this, port);
                } else if(buttonStart.getText().toString().equals("Stop")) {
                    buttonStart.setText("Start");
                    if(jnr!=null) {
                        textLog.append("Stopping server...\n");
                        jnr.stop();
                        jnr = null;
                    }
                }
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            System.exit(0);
        }
        return super.onKeyDown(keyCode, event);
    }

    public String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress() && inetAddress.isSiteLocalAddress()) {
                        return inetAddress.getHostAddress().toString();
                    }

                }
            }
        } catch (SocketException e) {
            Log.e("LOG_TAG", e.toString());
        }
        return null;
    }
}
