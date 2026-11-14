package zw.robowatch.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/**
 * The short vibrating notification sent with each alert. The phone buzzes, and a paired
 * smartwatch that mirrors RoboWatch's notifications buzzes too. It is silent: voice and
 * beeps are handled by Speaker.
 */
final class Alerts {
    static final String CHANNEL = "alerts";
    static final int ID = 2;

    private Alerts() {
    }

    static void buzz(Context context, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Camera robot alerts", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("A vibration on your phone and smartwatch when a camera robot is close.");
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0, 350, 150, 350});
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
            Notification note = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setCategory(Notification.CATEGORY_NAVIGATION)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setTimeoutAfter(20000L)
                    .build();
            nm.notify(ID, note);
        } catch (Exception ignored) {
        }
    }

    static void cancel(Context context) {
        try {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ID);
        } catch (Exception ignored) {
        }
    }
}
