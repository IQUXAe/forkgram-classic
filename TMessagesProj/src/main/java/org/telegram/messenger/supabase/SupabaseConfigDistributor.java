package org.telegram.messenger.supabase;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.xray.XrayNode;
import org.telegram.messenger.xray.XrayUriParser;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SupabaseConfigDistributor {
    private static volatile SupabaseConfigDistributor instance;

    public interface OnResultListener<T> {
        void onResult(T result);
    }

    private SharedPreferences securePrefs;
    private final Gson gson = new Gson();

    public static SupabaseConfigDistributor getInstance() {
        if (instance == null) {
            synchronized (SupabaseConfigDistributor.class) {
                if (instance == null) {
                    instance = new SupabaseConfigDistributor();
                }
            }
        }
        return instance;
    }

    private SupabaseConfigDistributor() {
        initSecurePrefs(ApplicationLoader.applicationContext);
    }

    private void initSecurePrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            securePrefs = EncryptedSharedPreferences.create(
                    context,
                    "secure_config_distributor_prefs_supabase",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Throwable e) {
            FileLog.e("SupabaseConfigDistributor: Failed to initialize EncryptedSharedPreferences. Tink failure.", e);
            throw new IllegalStateException("Security error: Cannot initialize EncryptedSharedPreferences. Fallback to unencrypted storage is disabled.", e);
        }
    }

    public void fetchConfigs(OnResultListener<List<XrayNode>> callback) {
        if (!verifyAppSignature(org.telegram.messenger.ApplicationLoader.applicationContext)) {
            FileLog.e("SupabaseConfigDistributor: ANTI-TAMPER TRIGGERED! APK Signature does not match expected. Refusing to fetch configs.");
            callback.onResult(getCachedConfigs());
            return;
        }

        String deviceUid = SupabaseAuthManager.getInstance().getDeviceUid();
        String inviteCode = SupabaseAuthManager.getInstance().getSavedInviteCode();
        String jsonBody = "{\"p_device_uid\":\"" + deviceUid + "\", \"p_code\":\"" + inviteCode + "\"}";
        SupabaseClient.post("/rest/v1/rpc/get_xray_configs", jsonBody, (result, responseCode, error) -> {
            if (error != null) {
                FileLog.e("SupabaseConfigDistributor: Failed to fetch xray configs from Supabase", error);
                if (error.getMessage() != null && error.getMessage().contains("Access denied")) {
                    FileLog.e("SupabaseConfigDistributor: Authorization revoked by server. Logging out.");
                    SupabaseAuthManager.getInstance().logout();
                    return;
                }
                callback.onResult(getCachedConfigs());
                return;
            }

            try {
                JsonArray array = gson.fromJson(result, JsonArray.class);
                List<XrayNode> nodes = new ArrayList<>();
                if (array != null) {
                    for (JsonElement elem : array) {
                        JsonObject obj = elem.getAsJsonObject();
                        String uriStr = obj.has("uri") && !obj.get("uri").isJsonNull() ? obj.get("uri").getAsString() : "";
                        uriStr = decrypt(uriStr, org.telegram.messenger.ApplicationLoader.applicationContext);
                        int priority = obj.has("priority") && !obj.get("priority").isJsonNull() ? obj.get("priority").getAsInt() : 0;
                        String id = obj.has("id") && !obj.get("id").isJsonNull() ? obj.get("id").getAsString() : "";

                        if (!uriStr.isEmpty()) {
                            try {
                                XrayNode node = XrayUriParser.parseUri(uriStr);
                                node.id = id;
                                node.priority = priority;
                                nodes.add(node);
                            } catch (Exception e) {
                                FileLog.e("SupabaseConfigDistributor: Failed to parse URI: " + uriStr, e);
                            }
                        }
                    }
                }

                // Sort nodes by priority ascending (0 is highest priority)
                Collections.sort(nodes, (o1, o2) -> Integer.compare(o1.priority, o2.priority));

                if (!nodes.isEmpty()) {
                    cacheConfigs(nodes);
                }
                FileLog.d("SupabaseConfigDistributor: Fetched " + nodes.size() + " active xray configs");
                callback.onResult(nodes);
            } catch (Exception e) {
                FileLog.e("SupabaseConfigDistributor: Error parsing configs: " + result, e);
                callback.onResult(getCachedConfigs());
            }
        });
    }

    private String decrypt(String encryptedText, Context context) {
        try {
            if (encryptedText == null || encryptedText.isEmpty()) {
                return "";
            }
            if (encryptedText.startsWith("vless://") || encryptedText.startsWith("trojan://") || encryptedText.startsWith("{")) {
                return encryptedText;
            }
            
            byte[] certHash = getAppSignatureHash(context);
            if (certHash == null) {
                FileLog.e("SupabaseConfigDistributor: Could not retrieve app signature hash for decryption");
                return "";
            }
            
            // Generate secret salt using XORShift PRNG (obfuscated)
            byte[] salt = new byte[32];
            long state = SupabaseSecrets.XORSHIFT_SEED;
            for (int i = 0; i < 32; i++) {
                state ^= (state << 21);
                state ^= (state >>> 35);
                state ^= (state << 4);
                salt[i] = (byte) (state & 0xFF);
            }
            
            // Combine signature hash and secret salt using SHA-256
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            sha256.update(certHash);
            sha256.update(salt);
            byte[] finalKeyBytes = sha256.digest();
            
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(finalKeyBytes, "AES");
            byte[] cipherBytes = android.util.Base64.decode(encryptedText, android.util.Base64.DEFAULT);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey);
            byte[] decryptedBytes = cipher.doFinal(cipherBytes);
            return new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            FileLog.e("SupabaseConfigDistributor: Failed to decrypt URI: " + encryptedText, e);
            return encryptedText;
        }
    }

    private byte[] getAppSignatureHash(Context context) {
        try {
            android.content.pm.Signature[] signatures;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                if (packageInfo.signingInfo == null) return null;
                if (packageInfo.signingInfo.hasMultipleSigners()) {
                    signatures = packageInfo.signingInfo.getApkContentsSigners();
                } else {
                    signatures = packageInfo.signingInfo.getSigningCertificateHistory();
                }
            } else {
                android.content.pm.PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
                signatures = packageInfo.signatures;
            }
            if (signatures != null && signatures.length > 0) {
                byte[] cert = signatures[0].toByteArray();
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                return md.digest(cert);
            }
        } catch (Exception e) {
            FileLog.e("SupabaseConfigDistributor: Failed to get signature SHA-256", e);
        }
        return null;
    }

    public void cacheConfigs(List<XrayNode> configs) {
        if (securePrefs == null || configs == null) {
            return;
        }
        try {
            String json = gson.toJson(configs);
            securePrefs.edit().putString("xray_nodes_json", json).commit();
            FileLog.d("SupabaseConfigDistributor: Successfully cached " + configs.size() + " configs");
        } catch (Exception e) {
            FileLog.e("SupabaseConfigDistributor: Failed to cache configs", e);
        }
    }

    public void clearConfigs() {
        if (securePrefs != null) {
            try {
                securePrefs.edit().remove("xray_nodes_json").commit();
                FileLog.d("SupabaseConfigDistributor: Cleared cached configs");
            } catch (Exception e) {
                FileLog.e("SupabaseConfigDistributor: Failed to clear cached configs", e);
            }
        }
    }

    public List<XrayNode> getCachedConfigs() {
        if (securePrefs == null) {
            return new ArrayList<>();
        }
        try {
            String json = securePrefs.getString("xray_nodes_json", null);
            if (json == null || json.isEmpty()) {
                return new ArrayList<>();
            }
            Type listType = new TypeToken<ArrayList<XrayNode>>() {}.getType();
            return gson.fromJson(json, listType);
        } catch (Exception e) {
            FileLog.e("SupabaseConfigDistributor: Failed to read cached configs", e);
            return new ArrayList<>();
        }
    }

    public XrayNode getFirstNode() {
        List<XrayNode> list = getCachedConfigs();
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    public XrayNode getNextNode(XrayNode currentFailed) {
        List<XrayNode> list = getCachedConfigs();
        if (list == null || list.isEmpty()) {
            return null;
        }
        if (currentFailed == null) {
            return list.get(0);
        }

        int index = -1;
        for (int i = 0; i < list.size(); i++) {
            if (currentFailed.id != null && currentFailed.id.equals(list.get(i).id)) {
                index = i;
                break;
            } else if (currentFailed.address.equals(list.get(i).address) && currentFailed.port == list.get(i).port) {
                index = i;
                break;
            }
        }

        if (index == -1 || index == list.size() - 1) {
            return list.get(0);
        }

        return list.get(index + 1);
    }

    private boolean verifyAppSignature(Context context) {
        try {
            android.content.pm.Signature[] sigs;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = packageInfo.signingInfo.getApkContentsSigners();
            } else {
                android.content.pm.PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
                sigs = packageInfo.signatures;
            }

            for (android.content.pm.Signature sig : sigs) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                md.update(sig.toByteArray());
                String currentSignature = android.util.Base64.encodeToString(md.digest(), android.util.Base64.NO_WRAP);

                // DEBUG Keystore signature base64 hash: "nik88HLK1/gJ9na26cx1n+rwWi6d8O2ee89SAXAJY44="
                // RELEASE Keystore signature base64 hash (CN=IQUXAe): "sEAb/ig1zJ2XHCkn2oFcrp/wWNKss7sSR7fo+BG+BeU="
                if ("sEAb/ig1zJ2XHCkn2oFcrp/wWNKss7sSR7fo+BG+BeU=".equals(currentSignature)) {
                    return true;
                }
                if (org.telegram.messenger.BuildConfig.DEBUG && "nik88HLK1/gJ9na26cx1n+rwWi6d8O2ee89SAXAJY44=".equals(currentSignature)) {
                    return true;
                }
                
                // Print the current signature hash so you can copy it for your release build
                FileLog.e("SupabaseConfigDistributor: UNKNOWN APP SIGNATURE HASH: " + currentSignature);
            }
        } catch (Exception e) {
            FileLog.e("SupabaseConfigDistributor: Failed to verify app signature", e);
        }
        return false;
    }
}
