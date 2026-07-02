package org.telegram.messenger.supabase;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

public class SupabaseAuthManager {
    private static volatile SupabaseAuthManager instance;
    
    public interface OnResultListener<T> {
        void onResult(T result);
    }

    private SharedPreferences securePrefs;
    private final Gson gson = new Gson();
    private String deviceUid;

    public static SupabaseAuthManager getInstance() {
        if (instance == null) {
            synchronized (SupabaseAuthManager.class) {
                if (instance == null) {
                    instance = new SupabaseAuthManager();
                }
            }
        }
        return instance;
    }

    private SupabaseAuthManager() {
        initSecurePrefs(ApplicationLoader.applicationContext);
        initDeviceUid(ApplicationLoader.applicationContext);
    }

    private void initSecurePrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            securePrefs = EncryptedSharedPreferences.create(
                    context,
                    "secure_auth_prefs_supabase",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Throwable e) {
            FileLog.e("SupabaseAuthManager: Failed to initialize EncryptedSharedPreferences. Tink failure.", e);
            throw new IllegalStateException("Security error: Cannot initialize EncryptedSharedPreferences. Fallback to unencrypted storage is disabled.", e);
        }
    }

    private void initDeviceUid(Context context) {
        try {
            deviceUid = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            FileLog.e("SupabaseAuthManager: Failed to get ANDROID_ID", e);
            deviceUid = "fallback-device-uid-" + System.currentTimeMillis();
        }
    }

    public void validateInviteCode(String code, OnResultListener<Boolean> callback) {
        String jsonBody = "{\"p_code\":\"" + code + "\",\"p_device_uid\":\"" + deviceUid + "\"}";
        
        SupabaseClient.post("/rest/v1/rpc/validate_invite_code", jsonBody, (result, responseCode, error) -> {
            if (error != null) {
                FileLog.e("SupabaseAuthManager: Failed to validate invite code", error);
                callback.onResult(false);
                return;
            }

            try {
                // Supabase RPC returns true or false as a plain boolean or inside JSON
                boolean isValid = Boolean.parseBoolean(result);
                if (isValid) {
                    setLocallyAuthorized(true);
                    setSavedInviteCode(code);
                }
                callback.onResult(isValid);
            } catch (Exception e) {
                FileLog.e("SupabaseAuthManager: Error parsing validate response: " + result, e);
                callback.onResult(false);
            }
        });
    }

    public void checkDeviceAuthorized(OnResultListener<Boolean> callback) {
        if (isLocallyAuthorized()) {
            callback.onResult(true);
            return;
        }

        SupabaseClient.get("/rest/v1/allowed_devices?device_uid=eq." + deviceUid + "&select=device_uid", (result, responseCode, error) -> {
            if (error != null) {
                FileLog.e("SupabaseAuthManager: Failed to check device auth", error);
                callback.onResult(false);
                return;
            }

            try {
                JsonArray array = gson.fromJson(result, JsonArray.class);
                boolean isAuthorized = array != null && array.size() > 0;
                if (isAuthorized) {
                    setLocallyAuthorized(true);
                }
                callback.onResult(isAuthorized);
            } catch (Exception e) {
                FileLog.e("SupabaseAuthManager: Error parsing device auth response: " + result, e);
                callback.onResult(false);
            }
        });
    }

    public boolean isLocallyAuthorized() {
        if (securePrefs == null) {
            return false;
        }
        return securePrefs.getBoolean("is_authorized", false);
    }

    public void setLocallyAuthorized(boolean authorized) {
        if (securePrefs != null) {
            securePrefs.edit().putBoolean("is_authorized", authorized).apply();
        }
    }

    public void setSavedInviteCode(String code) {
        if (securePrefs != null) {
            securePrefs.edit().putString("saved_invite_code", code).apply();
        }
    }

    public String getSavedInviteCode() {
        if (securePrefs != null) {
            return securePrefs.getString("saved_invite_code", "");
        }
        return "";
    }

    public String getDeviceUid() {
        return deviceUid;
    }
}
