package de.polla.echoradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/** Haelt die Weboberflaeche im Hintergrund am Laufen. */
public class WebService extends Service {

    static final int PORT = 8080;

    private static final String CHANNEL = "web";
    private static final int NOTIFICATION_ID = 2;

    private WebServer server;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        VoiceWords.load(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Weboberfläche", NotificationManager.IMPORTANCE_MIN));
        String address = address();
        startForeground(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentTitle("Echo Radio – Weboberfläche")
                .setContentText(address == null ? "Port " + PORT : address)
                .setContentIntent(PendingIntent.getActivity(this, 2,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build());
        server = new WebServer(this, PORT);
        try {
            server.start();
        } catch (IOException e) {
            server = null;
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
        }
        super.onDestroy();
    }

    /** Adresse der Weboberflaeche im Heimnetz, z. B. http://192.168.1.21:8080, oder null. */
    static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return "http://" + a.getHostAddress() + ":" + PORT;
                    }
                }
            }
        } catch (SocketException ignored) {
            // kein Netz
        }
        return null;
    }
}
