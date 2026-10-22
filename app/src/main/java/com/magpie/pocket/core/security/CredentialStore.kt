package com.magpie.pocket.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.magpie.pocket.core.model.ConnectionConfig
import com.magpie.pocket.core.model.DataMode

class CredentialStorageException : IllegalStateException(
    "无法使用设备加密存储，连接未保存。请检查设备锁屏与安全设置后重试。"
)

class CredentialStore internal constructor(
    createEncryptedPreferences: () -> SharedPreferences,
    eraseLegacyPlaintext: () -> Unit
) {
    constructor(context: Context) : this(
        createEncryptedPreferences = {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "magpie_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        },
        eraseLegacyPlaintext = {
            // Erase the old fallback; never read or migrate its credentials.
            val legacy = context.getSharedPreferences("magpie_fallback_prefs", Context.MODE_PRIVATE)
            if (!legacy.edit().clear().commit()) throw CredentialStorageException()
            context.deleteSharedPreferences("magpie_fallback_prefs")
        }
    )

    private var prefs: SharedPreferences? = try {
        eraseLegacyPlaintext()
        createEncryptedPreferences()
    } catch (_: Exception) {
        // Demo remains available, but live credentials cannot be saved without encryption.
        null
    }

    fun saveConnection(config: ConnectionConfig) {
        val secure = prefs ?: throw CredentialStorageException()
        try {
            val saved = secure.edit()
                .putString(KEY_CONNECTION_ID, config.id)
                .putString(KEY_NAME, config.name)
                .putString(KEY_MODE, config.mode.name)
                .putString(KEY_BASE_URL, config.baseUrl)
                .putString(KEY_WEB_KEY, config.webKey)
                .putBoolean(KEY_READ_ONLY_CONFIRMED, config.serverReadOnlyPermissionConfirmed)
                .putString(KEY_CF_CLIENT_ID, config.cfAccessClientId)
                .putString(KEY_CF_CLIENT_SECRET, config.cfAccessClientSecret)
                .commit()
            if (!saved) throw CredentialStorageException()
        } catch (_: Exception) {
            throw CredentialStorageException()
        }
    }

    fun getConnection(): ConnectionConfig {
        val secure = prefs ?: return demoConnection()
        return try {
            readConnection(secure)
        } catch (_: Exception) {
            prefs = null
            demoConnection()
        }
    }

    private fun readConnection(secure: SharedPreferences): ConnectionConfig {
        val id = secure.getString(KEY_CONNECTION_ID, "demo-main") ?: "demo-main"
        val name = secure.getString(KEY_NAME, "主网关") ?: "主网关"
        val modeStr = secure.getString(KEY_MODE, DataMode.DEMO.name) ?: DataMode.DEMO.name
        val mode = try { DataMode.valueOf(modeStr) } catch (_: Exception) { DataMode.DEMO }
        val baseUrl = secure.getString(KEY_BASE_URL, null)
        val webKey = secure.getString(KEY_WEB_KEY, null)
        val readOnly = secure.getBoolean(KEY_READ_ONLY_CONFIRMED, false)
        val cfClientId = secure.getString(KEY_CF_CLIENT_ID, null)
        val cfClientSecret = secure.getString(KEY_CF_CLIENT_SECRET, null)

        return ConnectionConfig(
            id = id,
            name = name,
            mode = mode,
            baseUrl = baseUrl,
            webKey = webKey,
            serverReadOnlyPermissionConfirmed = readOnly,
            cfAccessClientId = cfClientId,
            cfAccessClientSecret = cfClientSecret
        )
    }

    fun clearCredentials() {
        val secure = prefs ?: return
        try {
            if (!secure.edit().clear().commit()) throw CredentialStorageException()
        } catch (_: Exception) {
            throw CredentialStorageException()
        }
    }

    private fun demoConnection() = ConnectionConfig("demo-main", "主网关", DataMode.DEMO)

    companion object {
        private const val KEY_CONNECTION_ID = "conn_id"
        private const val KEY_NAME = "conn_name"
        private const val KEY_MODE = "conn_mode"
        private const val KEY_BASE_URL = "conn_base_url"
        private const val KEY_WEB_KEY = "conn_web_key"
        private const val KEY_READ_ONLY_CONFIRMED = "conn_read_only"
        private const val KEY_CF_CLIENT_ID = "conn_cf_client_id"
        private const val KEY_CF_CLIENT_SECRET = "conn_cf_client_secret"
    }
}
