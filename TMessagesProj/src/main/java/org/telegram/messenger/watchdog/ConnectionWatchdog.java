package org.telegram.messenger.watchdog;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.supabase.SupabaseConfigDistributor;
import org.telegram.messenger.xray.XrayManager;
import org.telegram.messenger.xray.XrayNode;
import org.telegram.tgnet.ConnectionsManager;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

public class ConnectionWatchdog implements NotificationCenter.NotificationCenterDelegate {
    private static volatile ConnectionWatchdog instance;

    private static final int CONNECTING_TIMEOUT_MS = 8000;
    private static final int PROBE_TIMEOUT_MS = 4000;
    private static final String PROBE_URL = "https://core.telegram.org/favicon.ico";
    private static final int BACKGROUND_POLL_INTERVAL_MS = 10_000;

    // AlarmManager action для пробуждения из Doze
    private static final String ACTION_WATCHDOG_ALARM = "org.iquxae.forkgram.WATCHDOG_ALARM";
    private static final int ALARM_INTERVAL_MS = 15_000;

    // Используем HandlerThread — отдельный поток с собственным Looper.
    // В отличие от Main Looper, Android НЕ дросселирует фоновый HandlerThread.
    private HandlerThread handlerThread;
    private Handler handler;
    private boolean isRunning = false;
    private boolean isProbing = false;

    private AlarmManager alarmManager;
    private PendingIntent alarmPendingIntent;
    private BroadcastReceiver alarmReceiver;

    private final Runnable checkConnectionRunnable = () -> {
        if (!isRunning) return;
        FileLog.d("ConnectionWatchdog: Connecting timeout reached. Triggering Active Probe.");
        startActiveProbe();
    };

    private final Runnable refreshConfigsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            if (org.telegram.messenger.supabase.SupabaseAuthManager.getInstance().isLocallyAuthorized()) {
                FileLog.d("ConnectionWatchdog: Periodic background fetch of xray configs started.");
                SupabaseConfigDistributor.getInstance().fetchConfigs(nodes -> {
                    // Refreshed in background
                });
            }
            handler.postDelayed(this, 300000); // 5 minutes
        }
    };

    private final Runnable backgroundPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            int selectedAccount = UserConfig.selectedAccount;
            int state = ConnectionsManager.getInstance(selectedAccount).getConnectionState();
            FileLog.d("ConnectionWatchdog: Background poll — state=" + state);
            if (state == ConnectionsManager.ConnectionStateConnecting ||
                state == ConnectionsManager.ConnectionStateConnectingToProxy) {
                handler.removeCallbacks(checkConnectionRunnable);
                startActiveProbe();
            }
            handler.postDelayed(this, BACKGROUND_POLL_INTERVAL_MS);
        }
    };

    public static ConnectionWatchdog getInstance() {
        if (instance == null) {
            synchronized (ConnectionWatchdog.class) {
                if (instance == null) {
                    instance = new ConnectionWatchdog();
                }
            }
        }
        return instance;
    }

    private ConnectionWatchdog() {
    }

    public synchronized boolean isRunning() {
        return isRunning;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        // Запускаем HandlerThread — свой поток с Looper, не зависящий от UI
        handlerThread = new HandlerThread("ConnectionWatchdog", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        FileLog.d("ConnectionWatchdog: Watchdog started on background thread");

        // Подписываемся на события изменения состояния соединения
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }

        // Начальная проверка состояния
        handler.post(this::checkCurrentConnectionState);

        // Периодическое обновление конфигов
        handler.postDelayed(refreshConfigsRunnable, 30000);

        // Периодический опрос состояния соединения
        handler.postDelayed(backgroundPollRunnable, BACKGROUND_POLL_INTERVAL_MS);

        // AlarmManager как резервный механизм — пробуждает из Doze каждые 15с
        startAlarm();
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;

        stopAlarm();

        if (handler != null) {
            handler.removeCallbacks(checkConnectionRunnable);
            handler.removeCallbacks(refreshConfigsRunnable);
            handler.removeCallbacks(backgroundPollRunnable);
        }

        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        handler = null;

        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        FileLog.d("ConnectionWatchdog: Watchdog stopped");
    }

    private void startAlarm() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);

            alarmReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!isRunning) return;
                    FileLog.d("ConnectionWatchdog: AlarmManager fired — checking state");
                    int selectedAccount = UserConfig.selectedAccount;
                    int state = ConnectionsManager.getInstance(selectedAccount).getConnectionState();
                    if (state == ConnectionsManager.ConnectionStateConnecting ||
                        state == ConnectionsManager.ConnectionStateConnectingToProxy) {
                        FileLog.d("ConnectionWatchdog: AlarmManager detected stuck state, probing...");
                        if (handler != null) {
                            handler.removeCallbacks(checkConnectionRunnable);
                            handler.post(ConnectionWatchdog.this::startActiveProbe);
                        }
                    }
                    // Перезапланировать следующий будильник
                    scheduleNextAlarm();
                }
            };

            ctx.registerReceiver(alarmReceiver, new IntentFilter(ACTION_WATCHDOG_ALARM));
            scheduleNextAlarm();
        } catch (Exception e) {
            FileLog.e("ConnectionWatchdog: Failed to start AlarmManager", e);
        }
    }

    private void scheduleNextAlarm() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            Intent intent = new Intent(ACTION_WATCHDOG_ALARM);
            intent.setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            alarmPendingIntent = PendingIntent.getBroadcast(ctx, 0, intent, flags);

            long triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // setExactAndAllowWhileIdle срабатывает даже в Doze mode
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmPendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, alarmPendingIntent);
            }
        } catch (Exception e) {
            FileLog.e("ConnectionWatchdog: Failed to schedule alarm", e);
        }
    }

    private void stopAlarm() {
        try {
            if (alarmPendingIntent != null && alarmManager != null) {
                alarmManager.cancel(alarmPendingIntent);
            }
            if (alarmReceiver != null) {
                ApplicationLoader.applicationContext.unregisterReceiver(alarmReceiver);
                alarmReceiver = null;
            }
        } catch (Exception e) {
            FileLog.e("ConnectionWatchdog: Failed to stop AlarmManager", e);
        }
    }

    private void checkCurrentConnectionState() {
        int selectedAccount = UserConfig.selectedAccount;
        int state = ConnectionsManager.getInstance(selectedAccount).getConnectionState();
        handleStateChange(state);
    }

    private void handleStateChange(int state) {
        if (handler == null) return;
        handler.removeCallbacks(checkConnectionRunnable);
        if (state == ConnectionsManager.ConnectionStateConnecting ||
            state == ConnectionsManager.ConnectionStateConnectingToProxy) {
            FileLog.d("ConnectionWatchdog: Connection state is Connecting (" + state + "). Scheduling check in " + CONNECTING_TIMEOUT_MS + "ms.");
            handler.postDelayed(checkConnectionRunnable, CONNECTING_TIMEOUT_MS);
        } else if (state == ConnectionsManager.ConnectionStateConnected ||
                   state == ConnectionsManager.ConnectionStateUpdating) {
            FileLog.d("ConnectionWatchdog: Connected successfully (" + state + "). Cancelling active check.");
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (!isRunning) return;

        if (id == NotificationCenter.didUpdateConnectionState) {
            if (account == UserConfig.selectedAccount) {
                int state = ConnectionsManager.getInstance(account).getConnectionState();
                // Обрабатываем на нашем background thread, не на UI
                if (handler != null) {
                    handler.post(() -> handleStateChange(state));
                }
            }
        }
    }

    private void startActiveProbe() {
        if (isProbing) return;
        isProbing = true;

        final int xrayPort = XrayManager.getInstance().getLocalPort();
        if (xrayPort <= 0) {
            FileLog.e("ConnectionWatchdog: Xray port is not set. Cannot run probe.");
            isProbing = false;
            return;
        }

        new Thread(() -> {
            boolean success = false;
            boolean networkUnreachable = false;
            HttpURLConnection connection = null;
            try {
                Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", xrayPort));
                URL url = new URL(PROBE_URL);
                connection = (HttpURLConnection) url.openConnection(proxy);
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(PROBE_TIMEOUT_MS);
                connection.setReadTimeout(PROBE_TIMEOUT_MS);

                int responseCode = connection.getResponseCode();
                FileLog.d("ConnectionWatchdog: Probe returned response code: " + responseCode);
                success = (responseCode >= 200 && responseCode < 400);
            } catch (IOException e) {
                String msg = e.getMessage();
                FileLog.e("ConnectionWatchdog: Probe IOException: " + msg);
                if (msg != null && (msg.contains("ENETUNREACH") ||
                                    msg.contains("Network is unreachable") ||
                                    msg.contains("No route to host") ||
                                    msg.contains("ENETDOWN"))) {
                    networkUnreachable = true;
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            final boolean finalSuccess = success;
            final boolean finalNetworkUnreachable = networkUnreachable;

            // Результат обрабатываем на нашем background handler, не на Main Looper
            if (handler != null) {
                handler.post(() -> {
                    isProbing = false;
                    if (!isRunning) return;

                    if (finalSuccess) {
                        FileLog.d("ConnectionWatchdog: Probe successful — proxy is working.");
                    } else if (finalNetworkUnreachable || !ApplicationLoader.isNetworkOnline()) {
                        FileLog.d("ConnectionWatchdog: No network. Deferring node rotation.");
                        handler.postDelayed(checkConnectionRunnable, CONNECTING_TIMEOUT_MS);
                    } else {
                        FileLog.e("ConnectionWatchdog: Probe failed but internet is online. Rotating node.");
                        rotateXrayNode();
                    }
                });
            } else {
                isProbing = false;
            }
        }).start();
    }

    private void rotateXrayNode() {
        if (org.telegram.messenger.supabase.SupabaseAuthManager.getInstance().isLocallyAuthorized()) {
            SupabaseConfigDistributor.getInstance().fetchConfigs(nodes -> {
                // Background refresh completed
            });
        }

        XrayNode currentNode = XrayManager.getInstance().getCurrentNode();
        XrayNode nextNode = SupabaseConfigDistributor.getInstance().getNextNode(currentNode);
        if (nextNode != null) {
            FileLog.d("ConnectionWatchdog: Rotating node to: " + nextNode.remark + " (" + nextNode.address + ":" + nextNode.port + ")");
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.xrayNodeRotated, nextNode);
            });
            XrayManager.getInstance().restart(nextNode);
        } else {
            FileLog.e("ConnectionWatchdog: No backup nodes found for rotation!");
        }
    }

    public void forceProbe() {
        if (handler != null) {
            handler.removeCallbacks(checkConnectionRunnable);
            handler.post(this::startActiveProbe);
        }
    }

    /** Вызывается из WatchdogAlarmReceiver чтобы запланировать следующий будильник. */
    public void rescheduleAlarm() {
        if (isRunning) {
            scheduleNextAlarm();
        }
    }
}
