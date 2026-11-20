package com.example.autodelete

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppPrefs {
    private const val PREFS_NAME = "autodelete_prefs"
    private const val KEY_TARGET_CONTACTS = "target_contact"
    private const val KEY_AUTO_DELETE_ENABLED = "auto_delete_enabled"
    private const val KEY_DEFER_WHEN_LOCKED = "defer_when_locked"
    private const val KEY_HIDE_NOTIF_WHEN_LOCKED = "hide_notif_when_locked"
    private const val KEY_RETURN_HOME = "return_home_after_delete"
    private const val KEY_LAN_UPLOAD_ENABLED = "lan_upload_enabled"
    private const val KEY_LAN_SERVER_URL = "lan_server_url"
    private const val KEY_LAN_API_TOKEN = "lan_api_token"
    private const val KEY_LOGS = "event_logs_json"
    private const val MAX_LOG_ITEMS = 25

    private val logLock = Any()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTargetContactsRaw(ctx: Context): String =
        prefs(ctx).getString(KEY_TARGET_CONTACTS, "") ?: ""

    fun setTargetContactsRaw(ctx: Context, raw: String) {
        prefs(ctx).edit().putString(KEY_TARGET_CONTACTS, raw.trim()).apply()
    }

    fun getTargetContacts(ctx: Context): List<String> {
        return getTargetContactsRaw(ctx)
            .split(",", "،", "\n", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun isAutoDeleteEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_DELETE_ENABLED, true)

    fun setAutoDeleteEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_DELETE_ENABLED, enabled).apply()
    }

    fun isDeferWhenLockedEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DEFER_WHEN_LOCKED, true)

    fun setDeferWhenLockedEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DEFER_WHEN_LOCKED, enabled).apply()
    }

    fun isHideNotifWhenLockedEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_HIDE_NOTIF_WHEN_LOCKED, false)

    fun setHideNotifWhenLockedEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HIDE_NOTIF_WHEN_LOCKED, enabled).apply()
    }

    fun isReturnHomeAfterDelete(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RETURN_HOME, true)

    fun setReturnHomeAfterDelete(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RETURN_HOME, enabled).apply()
    }

    fun isLanUploadEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LAN_UPLOAD_ENABLED, false)

    fun setLanUploadEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LAN_UPLOAD_ENABLED, enabled).apply()
    }

    fun getLanServerUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_LAN_SERVER_URL, "http://192.168.1.100:8765/api/messages") ?: ""

    fun setLanServerUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_LAN_SERVER_URL, url.trim()).apply()
    }

    fun getLanApiToken(ctx: Context): String =
        prefs(ctx).getString(KEY_LAN_API_TOKEN, "") ?: ""

    fun setLanApiToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_LAN_API_TOKEN, token.trim()).apply()
    }

    fun appendLog(ctx: Context, message: String) {
        synchronized(logLock) {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
            val entry = "[${sdf.format(Date())}] $message"
            val list = getLogsInternal(ctx).toMutableList()
            list.add(0, entry)
            while (list.size > MAX_LOG_ITEMS) {
                list.removeAt(list.lastIndex)
            }
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            prefs(ctx).edit().putString(KEY_LOGS, arr.toString()).apply()
        }
    }

    fun getLogs(ctx: Context): List<String> {
        synchronized(logLock) {
            return getLogsInternal(ctx)
        }
    }

    fun clearLogs(ctx: Context) {
        synchronized(logLock) {
            prefs(ctx).edit().remove(KEY_LOGS).apply()
        }
    }

    private fun getLogsInternal(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_LOGS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
