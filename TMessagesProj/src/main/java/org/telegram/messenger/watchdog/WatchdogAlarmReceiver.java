package org.telegram.messenger.watchdog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

/**
 * BroadcastReceiver для AlarmManager — пробуждает приложение из Doze Mode
 * и заставляет ConnectionWatchdog проверить состояние соединения.
 */
public class WatchdogAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        FileLog.d("WatchdogAlarmReceiver: Alarm received — checking connection");

        ConnectionWatchdog watchdog = ConnectionWatchdog.getInstance();

        // Если watchdog уже запущен — просто проверяем состояние
        int selectedAccount = UserConfig.selectedAccount;
        int state = ConnectionsManager.getInstance(selectedAccount).getConnectionState();

        if (state == ConnectionsManager.ConnectionStateConnecting ||
            state == ConnectionsManager.ConnectionStateConnectingToProxy) {
            FileLog.d("WatchdogAlarmReceiver: Stuck in connecting state — triggering probe");
            watchdog.forceProbe();
        }

        // Перепланировать следующий будильник
        watchdog.rescheduleAlarm();
    }
}
