package com.example.bluelightfilter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

/**
 * Foreground service that draws a full-screen, warm-tinted, non-interactive
 * overlay on top of every other app to filter blue light.
 */
public class FilterService extends Service {

    public static final String ACTION_START = "com.example.bluelightfilter.action.START";
    public static final String ACTION_UPDATE = "com.example.bluelightfilter.action.UPDATE";
    public static final String ACTION_STOP = "com.example.bluelightfilter.action.STOP";

    private static final String CHANNEL_ID = "blue_light_filter_channel";
    private static final int NOTIFICATION_ID = 1;

    /** Maximum tint opacity (0..255). ~70% keeps the screen readable. */
    private static final int MAX_ALPHA = 180;

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams overlayParams;

    // ---------------------------------------------------------------------
    // Static control helpers used by MainActivity and the notification.
    // ---------------------------------------------------------------------

    public static void start(Context context) {
        Intent intent = new Intent(context, FilterService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void pushUpdate(Context context) {
        Intent intent = new Intent(context, FilterService.class).setAction(ACTION_UPDATE);
        try {
            // Plain startService is fine here: the app is in the foreground while
            // the user drags a slider, and the service is already running.
            context.startService(intent);
        } catch (IllegalStateException ignored) {
            // The app is in the background; nothing to update right now.
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, FilterService.class));
    }

    // ---------------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            removeOverlay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_UPDATE.equals(action)) {
            // A slider changed while the filter is running: just re-apply the
            // color, never touch the foreground notification or window flags.
            if (overlayView != null) {
                applyFilterFromPrefs();
            }
            return START_STICKY;
        }

        // ACTION_START, or a sticky restart after the process was killed
        // (the intent is null then). We were started with
        // startForegroundService(), so startForeground() must run first;
        // stopping again afterwards is safe.
        startForeground(NOTIFICATION_ID, buildNotification());

        if (!Settings.canDrawOverlays(this)) {
            // The overlay permission was revoked while we were not running.
            removeOverlay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        showOverlay();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // After rotation or folding the window may keep its old size;
        // force a relayout so the overlay still covers the whole screen.
        if (overlayView != null && overlayParams != null) {
            try {
                windowManager.updateViewLayout(overlayView, overlayParams);
            } catch (IllegalArgumentException ignored) {
                // The view is no longer attached to the window manager.
            }
        }
    }

    // ---------------------------------------------------------------------
    // Overlay handling
    // ---------------------------------------------------------------------

    private void showOverlay() {
        if (overlayView == null) {
            overlayView = new View(this);

            overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    // TYPE_APPLICATION_OVERLAY is mandatory on API 26+;
                    // older devices must use TYPE_PHONE.
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);

            // FLAG_LAYOUT_NO_LIMITS lets the overlay extend over the status bar
            // and the navigation bar as well.
            overlayParams.gravity = Gravity.TOP | Gravity.START;

            try {
                windowManager.addView(overlayView, overlayParams);
            } catch (Exception e) {
                // The overlay permission was revoked or the window could not
                // be added - shut down cleanly.
                overlayView = null;
                overlayParams = null;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
        }
        applyFilterFromPrefs();
    }

    private void applyFilterFromPrefs() {
        SharedPreferences prefs =
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        int intensity = prefs.getInt(MainActivity.KEY_INTENSITY, MainActivity.DEFAULT_INTENSITY);
        int warmth = prefs.getInt(MainActivity.KEY_WARMTH, MainActivity.DEFAULT_WARMTH);

        // Intensity 0..100 maps to an alpha of 0..MAX_ALPHA (out of 255).
        int alpha = Math.round((intensity / 100f) * MAX_ALPHA);

        // Warmth 0..100 interpolates the tint from a pale yellow (255, 255, 90)
        // to a deep orange-red (255, 75, 0).
        int red = 255;
        int green = 255 - Math.round((warmth / 100f) * 180f);
        int blue = 90 - Math.round((warmth / 100f) * 90f);

        overlayView.setBackgroundColor(Color.argb(alpha, red, green, blue));
    }

    private void removeOverlay() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (IllegalArgumentException ignored) {
                // The view was not attached to the window manager any more.
            }
            overlayView = null;
            overlayParams = null;
        }
    }

    // ---------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_description));
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent turnOffIntent = PendingIntent.getService(
                this,
                1,
                new Intent(this, FilterService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder =
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? new Notification.Builder(this, CHANNEL_ID)
                        : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(contentIntent)
                .addAction(new Notification.Action.Builder(
                        null,
                        getString(R.string.notification_action_turn_off),
                        turnOffIntent)
                        .build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
