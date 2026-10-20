package sa.urtv.screensaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the phone page's server running: started at boot, by the screensaver and by the settings screen.
 * A foreground service, so Android does not stop it while the TV sits on another app.
 */
public class ControlService extends Service {
    private static final String CHANNEL = "control";
    private ControlServer server;

    static void start(Context c) {
        Intent i = new Intent(c, ControlService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }   // a TV that refuses: the page is simply unavailable until the next try
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "التحكم من الجوال", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("التحكم من الجوال")
            .setContentText(ControlStore.url() == null ? "" : ControlStore.url()).build();
        startForeground(1, n);
        ControlStore.pin(this);   // made once, shown in the settings
        server = new ControlServer(this);
        server.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
