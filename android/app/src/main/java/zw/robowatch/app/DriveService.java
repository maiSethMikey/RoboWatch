package zw.robowatch.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Runs while a drive is active. Keeps GPS going with the screen off and speaks the alerts.
 * The alert rules mirror onFix() in the web page (index.html); keep the two in step.
 */
public class DriveService extends Service implements LocationListener {
    static final String ACTION_STOP = "zw.robowatch.app.STOP";
    static final String PREFS = "robowatch";
    private static final String CHANNEL = "drive";
    private static final String ALERT_CHANNEL = "alerts";
    private static final int NOTE_ID = 1;
    private static final int ALERT_ID = 2;

    interface Listener {
        void onFix(double lat, double lon, float speed);

        void onStopped();
    }

    static volatile Listener listener;
    static volatile DriveService instance;
    static volatile boolean running = false;

    private static final class Cam {
        String name;
        double lat, lon;
        boolean in, passed, at250, at25, slowed;
        double min, max;
    }

    private final ArrayList<Cam> cams = new ArrayList<>();
    private int radius = 400;
    private int limit = 60;
    private boolean voice = true;
    private boolean sound = true;
    private boolean watch = true;

    private LocationManager locationManager;
    private Speaker speaker;
    private PowerManager.WakeLock wakeLock;
    private boolean listening = false;

    private boolean hasAnchor = false;
    private double anchorLat, anchorLon;
    private double heading = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        speaker = new Speaker(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        loadConfig();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification note = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTE_ID, note);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        loadConfig();
        if (!startLocation()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            if (voice) speaker.say("RoboWatch is watching.");
            else if (sound) speaker.beep(1);
        }
        return START_STICKY;
    }

    private boolean startLocation() {
        if (listening) return true;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
        } catch (Exception e) {
            return false;
        }
        listening = true;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "robowatch:drive");
            wakeLock.acquire(8 * 60 * 60 * 1000L);
        } catch (Exception ignored) {
        }
        return true;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Drive in progress", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while RoboWatch is watching for camera robots.");
        nm.createNotificationChannel(channel);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, DriveService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("RoboWatch is watching")
                .setContentText("Spoken alerts before each camera robot. Tap Stop when you arrive.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(R.drawable.ic_stat, "Stop", stop)
                .build();
    }

    /** Reads the camera list and settings the page saved. Called again whenever they change. */
    void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        radius = prefs.getInt("radius", 400);
        limit = prefs.getInt("limit", 60);
        voice = prefs.getBoolean("voice", true);
        sound = prefs.getBoolean("sound", true);
        watch = prefs.getBoolean("watch", true);
        cams.clear();
        try {
            JSONArray list = new JSONArray(prefs.getString("cams", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Cam c = new Cam();
                c.name = o.optString("n", "Camera robot");
                c.lat = o.getDouble("lat");
                c.lon = o.getDouble("lon");
                cams.add(c);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        float speed = location.hasSpeed() ? location.getSpeed() : -1f;
        check(lat, lon, speed);
        Listener l = listener;
        if (l != null) l.onFix(lat, lon, speed);
    }

    /** One GPS fix: decide whether to speak. Mirrors onFix() in index.html. */
    private void check(double lat, double lon, float speed) {
        if (!hasAnchor) {
            anchorLat = lat;
            anchorLon = lon;
            hasAnchor = true;
        } else if (dist(anchorLat, anchorLon, lat, lon) >= 20) {
            heading = bearing(anchorLat, anchorLon, lat, lon);
            anchorLat = lat;
            anchorLon = lon;
        }
        int kmh = speed >= 0 ? Math.round(speed * 3.6f) : -1;
        for (Cam c : cams) {
            double d = dist(lat, lon, c.lat, c.lon);
            if (c.passed) {
                if (d > c.max) c.max = d;
                // forget "passed" once it is far behind, or if we turn round and head back to it
                if (d > 2000 || d < c.max - 150) {
                    c.passed = false;
                    c.in = false;
                }
            }
            if (c.passed) continue;
            boolean inCone = heading < 0 || angleBetween(bearing(lat, lon, c.lat, c.lon), heading) <= 60;
            // Beeps count down to the camera: one at the first warning, two at 250 m, three at 25 m.
            // The 25 m beeps are sent one GPS reading early, so they sound at about 25 m.
            double last = 25 + (speed > 0 ? speed : 0);
            if (d <= radius && !c.in && inCone) {
                c.in = true;
                c.min = d;
                c.at25 = d <= last;
                c.at250 = c.at25 || d <= 250;
                c.slowed = false;
                if (sound) speaker.beep(c.at25 ? 3 : (c.at250 ? 2 : 1));
                say("Camera robot ahead. " + c.name.replace("&", "and") + ". Phone down, belts on, stop behind the line.");
                buzz("Camera robot ahead", c.name + ", " + (Math.round(d / 10) * 10) + " m");
            }
            if (c.in) {
                if (d < c.min) c.min = d;
                if (d >= c.min + 100) {
                    // 100 m beyond the closest point: this one is done
                    c.passed = true;
                    c.in = false;
                    c.max = d;
                } else {
                    if (!c.at25 && d <= last) {
                        c.at25 = true;
                        c.at250 = true;
                        if (sound) speaker.beep(3);
                        else say("Camera robot now.");
                        buzz("Camera robot now", c.name);
                    } else if (!c.at250 && d <= 250) {
                        c.at250 = true;
                        if (sound) speaker.beep(2);
                        say("Camera robot in 250 metres.");
                        buzz("Camera robot in 250 m", c.name);
                    }
                    if (limit > 0 && kmh > limit && !c.slowed) {
                        c.slowed = true;
                        say("Slow down. You are over " + limit + ".");
                    }
                }
            }
        }
    }

    private void say(String text) {
        if (voice) speaker.say(text);
    }

    /**
     * Posts a short vibrating notification. The phone buzzes, and a paired smartwatch that
     * mirrors this app's notifications buzzes too. No sound: voice and beeps cover that.
     */
    private void buzz(String title, String text) {
        if (!watch) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(ALERT_CHANNEL, "Camera robot alerts", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("A vibration on your phone and smartwatch when a camera robot is close.");
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0, 350, 150, 350});
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
            Notification note = new Notification.Builder(this, ALERT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setCategory(Notification.CATEGORY_NAVIGATION)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setTimeoutAfter(20000L)
                    .build();
            nm.notify(ALERT_ID, note);
        } catch (Exception ignored) {
        }
    }

    private static double dist(double aLat, double aLon, double bLat, double bLon) {
        double r = Math.PI / 180;
        double dLat = (bLat - aLat) * r;
        double dLon = (bLon - aLon) * r;
        double x = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(aLat * r) * Math.cos(bLat * r) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(x));
    }

    private static double bearing(double aLat, double aLon, double bLat, double bLon) {
        double r = Math.PI / 180;
        double y = Math.sin((bLon - aLon) * r) * Math.cos(bLat * r);
        double x = Math.cos(aLat * r) * Math.sin(bLat * r)
                - Math.sin(aLat * r) * Math.cos(bLat * r) * Math.cos((bLon - aLon) * r);
        return (Math.atan2(y, x) / r + 360) % 360;
    }

    private static double angleBetween(double a, double b) {
        return Math.abs(((a - b + 540) % 360) - 180);
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (listening) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {
            }
            listening = false;
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        if (speaker != null) speaker.shutdown();
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ALERT_ID);
        } catch (Exception ignored) {
        }
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        Listener l = listener;
        if (l != null) l.onStopped();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
