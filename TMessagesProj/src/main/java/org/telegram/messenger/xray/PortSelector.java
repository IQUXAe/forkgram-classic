package org.telegram.messenger.xray;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Random;
import org.telegram.messenger.FileLog;

public class PortSelector {
    private static final int MIN_PORT = 20000;
    private static final int MAX_PORT = 65535;

    public static int findRandomFreePort() {
        Random random = new Random();
        int attempts = 100;
        for (int i = 0; i < attempts; i++) {
            int port = random.nextInt(MAX_PORT - MIN_PORT + 1) + MIN_PORT;
            if (isPortFree(port)) {
                return port;
            }
        }
        FileLog.e("PortSelector: Failed to find a free port in range 20000-65535 after 100 attempts, returning fallback 10808");
        return 10808; // Fallback default port
    }

    private static boolean isPortFree(int port) {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            // Port is in use
            return false;
        } finally {
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }
}
