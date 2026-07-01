package org.telegram.messenger.xray;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import libxray.Libxray;

public class XrayManager {
    private static volatile XrayManager instance;

    public enum State { STOPPED, STARTING, RUNNING, ERROR }

    private int localPort = -1;
    private State currentState = State.STOPPED;
    private XrayNode currentNode;
    private boolean pausedForVpn = false;

    public static XrayManager getInstance() {
        if (instance == null) {
            synchronized (XrayManager.class) {
                if (instance == null) {
                    instance = new XrayManager();
                }
            }
        }
        return instance;
    }

    private XrayManager() {
    }

    public synchronized void start(XrayNode node) {
        if (node == null) {
            FileLog.e("XrayManager: Cannot start with null node");
            return;
        }

        if (isVpnActive(org.telegram.messenger.ApplicationLoader.applicationContext)) {
            FileLog.d("XrayManager: VPN is active, bypassing Xray and using direct connection");
            synchronized (this) {
                currentState = State.STOPPED;
                currentNode = node;
                localPort = -1;
                pausedForVpn = true;
            }
            org.telegram.tgnet.ConnectionsManager.onXrayPortChanged(-1);
            return;
        }

        if (currentState == State.RUNNING) {
            stop();
        }

        currentState = State.STARTING;
        currentNode = node;
        localPort = PortSelector.findRandomFreePort();
        
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.xrayStateChanged, currentState, localPort);

        final String configJson = XrayConfigBuilder.buildConfig(node, localPort);
        FileLog.d("XrayManager: Starting Xray on port " + localPort + " with config:\n" + configJson);

        new Thread(() -> {
            try {
                // Start the Go Xray core in a nested thread (it blocks until stopped)
                final int[] runXrayError = {0};
                Thread xrayCoreThread = new Thread(() -> {
                    try {
                        String result = Libxray.runXray(configJson);
                        if (result != null && !result.isEmpty()) {
                            FileLog.e("XrayManager: Xray stopped or failed with error: " + result);
                            runXrayError[0] = 1;
                        }
                    } catch (Exception e) {
                        FileLog.e("XrayManager: Exception while running Xray core", e);
                        runXrayError[0] = 1;
                    }
                });
                xrayCoreThread.setDaemon(true);
                xrayCoreThread.start();

                // Wait for the port to actually start listening (up to 3 seconds)
                boolean portReady = waitForPort(localPort, 3000);

                if (runXrayError[0] != 0 || !portReady) {
                    FileLog.e("XrayManager: Xray port " + localPort + " did not become ready in time (portReady=" + portReady + ")");
                    handleError();
                    return;
                }

                synchronized (XrayManager.this) {
                    currentState = State.RUNNING;
                }
                FileLog.d("XrayManager: Xray successfully started and port " + localPort + " is ready");
                org.telegram.tgnet.ConnectionsManager.onXrayPortChanged(localPort);
                NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.xrayStateChanged, State.RUNNING, localPort);

                // Wait for core thread to finish (i.e. Xray stops or errors)
                xrayCoreThread.join();
                if (runXrayError[0] != 0) {
                    handleError();
                }
            } catch (Exception e) {
                FileLog.e("XrayManager: Exception while running Xray core", e);
                handleError();
            }
        }).start();
    }

    private static boolean waitForPort(int port, int maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 100);
                socket.close();
                return true;
            } catch (Exception ignored) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private synchronized void handleError() {
        currentState = State.ERROR;
        localPort = -1;
        org.telegram.tgnet.ConnectionsManager.onXrayPortChanged(-1);
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.xrayStateChanged, State.ERROR, -1);
    }

    public synchronized void stop() {
        if (currentState == State.STOPPED) {
            return;
        }
        FileLog.d("XrayManager: Stopping Xray");
        try {
            Libxray.stopXray();
        } catch (Exception e) {
            FileLog.e("XrayManager: Exception while stopping Xray core", e);
        }
        currentState = State.STOPPED;
        localPort = -1;
        currentNode = null;
        org.telegram.tgnet.ConnectionsManager.onXrayPortChanged(-1);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.xrayStateChanged, State.STOPPED, -1);
    }

    public synchronized void stopForVpn() {
        if (currentState == State.RUNNING || currentState == State.STARTING) {
            FileLog.d("XrayManager: VPN detected, pausing Xray");
            XrayNode savedNode = currentNode;
            pausedForVpn = true;
            stop();
            currentNode = savedNode;
        }
    }

    public synchronized void resumeAfterVpn() {
        if (pausedForVpn && currentNode != null) {
            FileLog.d("XrayManager: VPN disconnected, resuming Xray");
            pausedForVpn = false;
            start(currentNode);
        }
    }

    public static boolean isVpnActive(android.content.Context context) {
        if (context == null) {
            return false;
        }
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                android.net.Network[] networks = cm.getAllNetworks();
                for (android.net.Network network : networks) {
                    android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("XrayManager: Failed to check VPN via ConnectivityManager", e);
        }
        return false;
    }

    public synchronized void restart(XrayNode node) {
        stop();
        start(node);
    }

    public synchronized int getLocalPort() {
        return localPort;
    }

    public synchronized State getState() {
        return currentState;
    }

    public synchronized boolean isRunning() {
        return currentState == State.RUNNING;
    }

    public synchronized XrayNode getCurrentNode() {
        return currentNode;
    }
}
