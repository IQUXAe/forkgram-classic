package org.telegram.messenger.watchdog;

import android.os.Handler;
import android.os.Looper;

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

    private static final int CONNECTING_TIMEOUT_MS = 5000;
    private static final int PROBE_TIMEOUT_MS = 2000;
    private static final String PROBE_URL = "https://vk.com/favicon.ico";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private boolean isProbing = false;

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
            handler.postDelayed(this, 3600000); // 1 hour
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

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        FileLog.d("ConnectionWatchdog: Watchdog started");

        // Listen to didUpdateConnectionState on all active accounts
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        
        // Initial state check
        checkCurrentConnectionState();

        // Start periodic configuration updates (initial run after 30 seconds)
        handler.postDelayed(refreshConfigsRunnable, 30000);
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        handler.removeCallbacks(checkConnectionRunnable);
        handler.removeCallbacks(refreshConfigsRunnable);
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        FileLog.d("ConnectionWatchdog: Watchdog stopped");
    }

    private void checkCurrentConnectionState() {
        int selectedAccount = UserConfig.selectedAccount;
        int state = ConnectionsManager.getInstance(selectedAccount).getConnectionState();
        handleStateChange(state);
    }

    private void handleStateChange(int state) {
        handler.removeCallbacks(checkConnectionRunnable);
        if (state == ConnectionsManager.ConnectionStateConnecting || 
            state == ConnectionsManager.ConnectionStateConnectingToProxy) {
            FileLog.d("ConnectionWatchdog: Connection state is Connecting (" + state + "). Scheduling check in 5s.");
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
                handleStateChange(state);
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

            AndroidUtilities.runOnUIThread(() -> {
                isProbing = false;
                if (!isRunning) return;

                if (finalSuccess) {
                    FileLog.d("ConnectionWatchdog: Probe was successful. Proxy is working, Telegram servers might be throttling/updating. Waiting.");
                } else if (finalNetworkUnreachable || !ApplicationLoader.isNetworkOnline()) {
                    FileLog.d("ConnectionWatchdog: Device has no network (ENETUNREACH). Deferring node rotation.");
                    // Schedule check again because network might come back
                    handler.postDelayed(checkConnectionRunnable, CONNECTING_TIMEOUT_MS);
                } else {
                    FileLog.e("ConnectionWatchdog: Probe failed but internet is online. Current Xray node is dead. Rotating.");
                    rotateXrayNode();
                }
            });
        }).start();
    }

    private void rotateXrayNode() {
        // Fetch new configurations in the background to ensure list is fresh
        if (org.telegram.messenger.supabase.SupabaseAuthManager.getInstance().isLocallyAuthorized()) {
            SupabaseConfigDistributor.getInstance().fetchConfigs(nodes -> {
                // Background refresh completed
            });
        }

        XrayNode currentNode = XrayManager.getInstance().getCurrentNode();
        XrayNode nextNode = SupabaseConfigDistributor.getInstance().getNextNode(currentNode);
        if (nextNode != null) {
            FileLog.d("ConnectionWatchdog: Rotating node to: " + nextNode.remark + " (" + nextNode.address + ":" + nextNode.port + ")");
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.xrayNodeRotated, nextNode);
            XrayManager.getInstance().restart(nextNode);
        } else {
            FileLog.e("ConnectionWatchdog: No backup nodes found for rotation!");
        }
    }

    public void forceProbe() {
        handler.removeCallbacks(checkConnectionRunnable);
        startActiveProbe();
    }
}
