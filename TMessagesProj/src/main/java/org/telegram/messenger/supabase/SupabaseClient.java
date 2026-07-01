package org.telegram.messenger.supabase;

import org.telegram.messenger.FileLog;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SupabaseClient {
    private static String getSupabaseUrl() {
        return decodeBytes(SupabaseSecrets.URL_BYTES);
    }

    private static String getSupabaseAnonKey() {
        return decodeBytes(SupabaseSecrets.KEY_BYTES);
    }

    private static String decodeBytes(byte[] bytes) {
        byte[] decoded = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            decoded[i] = (byte) (bytes[i] ^ SupabaseSecrets.XOR_MASK);
        }
        return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
    }

    public interface HttpCallback<T> {
        void onResponse(T result, int responseCode, Throwable error);
    }

    public static void post(final String path, final String jsonBody, final HttpCallback<String> callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(getSupabaseUrl() + path);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("apikey", getSupabaseAnonKey());
                conn.setRequestProperty("Authorization", "Bearer " + getSupabaseAnonKey());

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String response = readStream(is);
                
                if (code >= 200 && code < 300) {
                    callback.onResponse(response, code, null);
                } else {
                    callback.onResponse(response, code, new Exception("HTTP error code: " + code));
                }
            } catch (Throwable t) {
                FileLog.e("SupabaseClient: POST error", t);
                callback.onResponse(null, -1, t);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    public static void get(final String path, final HttpCallback<String> callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(getSupabaseUrl() + path);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("apikey", getSupabaseAnonKey());
                conn.setRequestProperty("Authorization", "Bearer " + getSupabaseAnonKey());

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String response = readStream(is);
                
                if (code >= 200 && code < 300) {
                    callback.onResponse(response, code, null);
                } else {
                    callback.onResponse(response, code, new Exception("HTTP error code: " + code));
                }
            } catch (Throwable t) {
                FileLog.e("SupabaseClient: GET error", t);
                callback.onResponse(null, -1, t);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            return response.toString();
        }
    }
}
