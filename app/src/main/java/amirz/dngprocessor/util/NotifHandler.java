package amirz.dngprocessor.util;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.work.ForegroundInfo;

import amirz.dngprocessor.R;

import static amirz.dngprocessor.util.Utilities.ATLEAST_OREO;

public class NotifHandler {
    private static final String TAG = "NotifHandler";
    private static final String CHANNEL = "default";
    private static final int FOREGROUND_ID = 1;
    private static final int MESSAGE_ID = 2;
    private static Notification.Builder mBuilder;

    public static void createChannel(Context context) {
        if (ATLEAST_OREO) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Default",
                    NotificationManager.IMPORTANCE_LOW);
            channel.enableLights(false);
            channel.enableVibration(false);
            manager(context).createNotificationChannel(channel);
        }
    }

    public static void create(Service service, String name) {
        PendingIntent pendingIntent = PendingIntent.getActivity(service, 0, new Intent(), FLAG_IMMUTABLE);
        if (ATLEAST_OREO) {
            mBuilder = new Notification.Builder(service, CHANNEL);
        } else {
            mBuilder = new Notification.Builder(service);
        }

        mBuilder.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Processing " + name)
                .setContentIntent(pendingIntent);

        Notification notification = mBuilder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(FOREGROUND_ID, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else {
            service.startForeground(FOREGROUND_ID, notification);
        }
    }

    public static void progress(Context context, int max, int progress) {
        if (mBuilder == null) {
            return; // Skip progress updates when not showing progress notification (UI mode)
        }
        Notification notif = mBuilder.setProgress(max, progress, false).build();
        manager(context).notify(FOREGROUND_ID, notif);
    }

    public static void done(Service service) {
        service.stopForeground(true);
    }

    public static ForegroundInfo createForWorker(Context context, String name) {
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, new Intent(), FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (ATLEAST_OREO) {
            builder = new Notification.Builder(context, CHANNEL);
        } else {
            builder = new Notification.Builder(context);
        }

        builder.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Processing " + name)
                .setContentIntent(pendingIntent);

        mBuilder = builder;
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return new ForegroundInfo(FOREGROUND_ID, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else {
            return new ForegroundInfo(FOREGROUND_ID, notification);
        }
    }

    public static void doneForWorker(Context context) {
        manager(context).cancel(FOREGROUND_ID);
    }

    public static void startProgress(Context context, String name) {
        Log.d(TAG, "startProgress: " + name);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, new Intent(), FLAG_IMMUTABLE);
        if (ATLEAST_OREO) {
            mBuilder = new Notification.Builder(context, CHANNEL);
        } else {
            mBuilder = new Notification.Builder(context);
        }

        mBuilder.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Processing " + name)
                .setContentIntent(pendingIntent)
                .setProgress(100, 0, false)
                .setOngoing(true);

        manager(context).notify(FOREGROUND_ID, mBuilder.build());
        Log.d(TAG, "startProgress notification posted");
    }

    public static void stopProgress(Context context) {
        mBuilder = null;
        manager(context).cancel(FOREGROUND_ID);
    }

    public static void showMessage(Context context, String title, String message) {
        Log.d(TAG, "showMessage: " + title + " - " + message);
        Notification.Builder builder;
        if (ATLEAST_OREO) {
            builder = new Notification.Builder(context, CHANNEL);
        } else {
            builder = new Notification.Builder(context);
        }

        builder.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true);

        manager(context).notify(MESSAGE_ID, builder.build());
        Log.d(TAG, "showMessage notification posted");
    }

    private static NotificationManager manager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }
}
