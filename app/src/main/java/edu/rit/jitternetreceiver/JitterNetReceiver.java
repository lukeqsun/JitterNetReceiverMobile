package edu.rit.jitternetreceiver;

import android.content.Context;

import java.net.ServerSocket;
import java.net.Socket;

/**
 * Created by lukesun on 4/3/15.
 */
public class JitterNetReceiver {
    ServerSocket sockfd = null;
    Socket connfd = null;

    public JitterNetReceiver(final Context ctx, final int port) {

        try {
            sockfd = new ServerSocket(port);
        } catch (Exception e) {
            e.printStackTrace();
        }

        new Thread() {
            public void run() {
                try {
                    connfd = sockfd.accept();

                    ServerAsyncTask serverAsyncTask = new ServerAsyncTask();
                    serverAsyncTask.execute(new Socket[] {connfd});
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
            }
        }.start();
    }

    public void stop() {
        try { sockfd.close(); }
        catch (Exception e) { e.printStackTrace(); }
    }
}
