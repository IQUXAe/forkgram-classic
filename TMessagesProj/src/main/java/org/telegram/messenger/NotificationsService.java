/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import org.telegram.ui.LauncherIconController;

public class NotificationsService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int pendingIntentFlags;
            if (Build.VERSION.SDK_INT >= 34) {
                pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE;
            } else {
                pendingIntentFlags = PendingIntent.FLAG_MUTABLE;
            }
            String CHANNEL_ID = "push_service_channel_v2";
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString("ForkBackgroundServiceTitle", R.string.ForkBackgroundServiceTitle), NotificationManager.IMPORTANCE_MIN);
            notificationManager.createNotificationChannel(channel);
            // Delete the old channel so the user doesn't see duplicates in Android settings
            notificationManager.deleteNotificationChannel("push_service_channel");
            Intent explainIntent = new Intent("android.intent.action.VIEW");
            explainIntent.setData(Uri.parse("https://github.com/forkgram/TelegramAndroid"));
            try {
            PendingIntent explainPendingIntent = PendingIntent.getActivity(this, 0, explainIntent, pendingIntentFlags);
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentIntent(explainPendingIntent)
                    .setShowWhen(false)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setSmallIcon(LauncherIconController.getNotificationIcon()) // [classic] #54: follow selected app icon
                    .setContentText(LocaleController.getString("ForkBackgroundServiceActive", R.string.ForkBackgroundServiceActive)).build();
            if (Build.VERSION.SDK_INT >= 34) {
                // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                startForeground(9999, notification, 1073741824);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(9999, notification, 0); // 0 = none
            } else {
                startForeground(9999, notification);
            }
            } catch (Throwable e) {
                Log.e("Forkgram Classic", "Failed to start foreground service", e);
            }
        }
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // postInitApplication() защищена флагом applicationInited и не запустит Xray/Watchdog повторно.
        // Поэтому явно перезапускаем их здесь — на случай если сервис был убит Android и поднялся снова.
        if (org.telegram.messenger.BuildVars.EDITION_WITH_VPN && org.telegram.messenger.supabase.SupabaseAuthManager.getInstance().isLocallyAuthorized()) {
            org.telegram.messenger.xray.XrayManager xrayManager = org.telegram.messenger.xray.XrayManager.getInstance();
            if (!xrayManager.isRunning()) {
                org.telegram.messenger.xray.XrayNode cachedNode = org.telegram.messenger.supabase.SupabaseConfigDistributor.getInstance().getFirstNode();
                if (cachedNode != null) {
                    FileLog.d("NotificationsService: Restarting Xray after service restart");
                    xrayManager.start(cachedNode);
                }
            }
            org.telegram.messenger.watchdog.ConnectionWatchdog watchdog = org.telegram.messenger.watchdog.ConnectionWatchdog.getInstance();
            if (!watchdog.isRunning()) {
                FileLog.d("NotificationsService: Restarting ConnectionWatchdog after service restart");
                watchdog.start();
            }
        }
        return START_STICKY;
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        if (org.telegram.messenger.BuildVars.EDITION_WITH_VPN) {
            org.telegram.messenger.xray.XrayManager.getInstance().stop();
            org.telegram.messenger.watchdog.ConnectionWatchdog.getInstance().stop();
        }
        super.onDestroy();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
