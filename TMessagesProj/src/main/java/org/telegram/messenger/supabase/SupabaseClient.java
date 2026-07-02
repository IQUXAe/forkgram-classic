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

                if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                    ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(getSSLSocketFactory());
                }
                
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
                    callback.onResponse(response, code, new Exception("HTTP error code: " + code + ", body: " + response));
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

                if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                    ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(getSSLSocketFactory());
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String response = readStream(is);
                
                if (code >= 200 && code < 300) {
                    callback.onResponse(response, code, null);
                } else {
                    callback.onResponse(response, code, new Exception("HTTP error code: " + code + ", body: " + response));
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

    private static javax.net.ssl.SSLSocketFactory sslSocketFactory;
    
    // Pinned SHA-256 hashes for the Supabase (Cloudflare / Google / Let's Encrypt / Sectigo CAs)
    private static final java.util.List<String> ACCEPTED_PINS = java.util.Arrays.asList(
        "mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=", // GTS Root R4
        "kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=", // WE1 (GTS Intermediate)
        "hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=", // GTS Root R1
        "Vfd95BwDeSQo+NUZXDzF7HO4glNvNeHrMjm5xAuhVwc=", // GTS Root R2
        "QX7oKcgMuvHNgRXyqr3AWZJhbJj/FboqL/qQ3b8FqG4=", // GTS Root R3
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // ISRG Root X1 (Let's Encrypt)
        "diO7ZzO+Gu648356E8lW9r9T9qWq1g80g0aR8+V080c=", // ISRG Root X2 (Let's Encrypt)
        "5iR/qW0cT5L8bZk8qE6nUq9f4fP8O9wY3wF8x2M3dO0=", // USERTrust RSA (Sectigo)
        "ZcJbApTb7wyllleAjHw2vYAskqdT+DhMY9aPDFwAtf4="  // Leaf fallback
    );

    private static synchronized javax.net.ssl.SSLSocketFactory getSSLSocketFactory() throws Exception {
        if (sslSocketFactory == null) {
            javax.net.ssl.TrustManager[] trustManagers = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {}

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                        // First, perform standard validation
                        try {
                            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                            tmf.init((java.security.KeyStore) null);
                            boolean valid = false;
                            for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                                if (tm instanceof javax.net.ssl.X509TrustManager) {
                                    ((javax.net.ssl.X509TrustManager) tm).checkServerTrusted(chain, authType);
                                    valid = true;
                                    break;
                                }
                            }
                            if (!valid) throw new java.security.cert.CertificateException("No valid TrustManager found");
                        } catch (Exception e) {
                            throw new java.security.cert.CertificateException("System trust manager failure", e);
                        }

                        // Then, perform SSL pinning
                        boolean pinned = false;
                        for (java.security.cert.X509Certificate cert : chain) {
                            try {
                                byte[] pubKey = cert.getPublicKey().getEncoded();
                                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                                byte[] hash = md.digest(pubKey);
                                String hashStr = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);

                                if (ACCEPTED_PINS.contains(hashStr)) {
                                    pinned = true;
                                    break;
                                }
                            } catch (Exception e) {
                                FileLog.e("SupabaseClient: Pinning hash error", e);
                            }
                        }

                        if (!pinned) {
                            throw new java.security.cert.CertificateException("Certificate pinning failed! Possible MITM attack.");
                        }
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
            };
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, new java.security.SecureRandom());
            sslSocketFactory = sslContext.getSocketFactory();
        }
        return sslSocketFactory;
    }

}
