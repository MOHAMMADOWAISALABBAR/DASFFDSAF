package com.example.autodelete

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class PendingLanMessage(
    val id: String = UUID.randomUUID().toString(),
    val contact: String,
    val phone: String = "",
    val sender: String,
    val message: String,
    val packageName: String,
    val isMedia: Boolean,
    val timestampMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("contact", contact)
        put("phone", phone)
        put("sender", sender)
        put("message", message)
        put("app", packageName)
        put("is_media", isMedia)
        put("timestamp_ms", timestampMs)
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
    }

    companion object {
        fun fromJson(obj: JSONObject): PendingLanMessage = PendingLanMessage(
            id = obj.optString("id", UUID.randomUUID().toString()),
            contact = obj.optString("contact", ""),
            phone = obj.optString("phone", ""),
            sender = obj.optString("sender", ""),
            message = obj.optString("message", ""),
            packageName = obj.optString("app", "com.whatsapp"),
            isMedia = obj.optBoolean("is_media", false),
            timestampMs = obj.optLong("timestamp_ms", System.currentTimeMillis())
        )
    }
}

data class FlushResult(
    val uploadedCount: Int,
    val remainingCount: Int,
    val lastError: String?
)

object LanUploader {
    private const val TAG = "WALanUploader"
    private const val OUTBOX_FILE = "lan_outbox.json"
    private const val WORK_NAME = "lan_outbox_sync"
    private const val MAX_QUEUE_SIZE = 500

    private val fileLock = Any()
    private val flushLock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun normalizeServerUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
        val clean = withScheme.trimEnd('/')
        return try {
            val uri = URI(clean)
            if (uri.path.isNullOrEmpty() || uri.path == "/") {
                "$clean/api/messages"
            } else {
                clean
            }
        } catch (_: Exception) {
            clean
        }
    }

    fun enqueueMessage(ctx: Context, item: PendingLanMessage) {
        val appCtx = ctx.applicationContext
        if (!AppPrefs.isLanUploadEnabled(appCtx)) return
        val serverUrl = normalizeServerUrl(AppPrefs.getLanServerUrl(appCtx))
        if (serverUrl.isBlank()) {
            AppPrefs.appendLog(appCtx, "الرفع المحلي مفعّل لكن عنوان الخادم فارغ")
            return
        }

        synchronized(fileLock) {
            val list = readQueueLocked(appCtx).toMutableList()
            list.add(item)
            while (list.size > MAX_QUEUE_SIZE) {
                list.removeAt(0)
            }
            writeQueueLocked(appCtx, list)
        }

        executor.execute {
            val res = flushQueueSync(appCtx)
            if (res.remainingCount > 0) {
                scheduleBackgroundRetry(appCtx)
            }
        }
    }

    fun getPendingCount(ctx: Context): Int {
        synchronized(fileLock) {
            return readQueueLocked(ctx.applicationContext).size
        }
    }

    fun scheduleBackgroundRetry(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<LanUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun flushQueueSync(ctx: Context): FlushResult {
        synchronized(flushLock) {
            val appCtx = ctx.applicationContext
            val serverUrl = normalizeServerUrl(AppPrefs.getLanServerUrl(appCtx))
            val token = AppPrefs.getLanApiToken(appCtx)

            if (serverUrl.isBlank()) {
                return FlushResult(0, getPendingCount(appCtx), "عنوان الخادم فارغ")
            }

            val snapshot = synchronized(fileLock) { readQueueLocked(appCtx) }
            if (snapshot.isEmpty()) {
                return FlushResult(0, 0, null)
            }

            var uploaded = 0
            var lastError: String? = null
            val uploadedIds = mutableSetOf<String>()

            for (item in snapshot) {
                val err = postSingleMessage(serverUrl, token, item)
                if (err == null) {
                    uploaded++
                    uploadedIds.add(item.id)
                } else {
                    lastError = err
                    Log.w(TAG, "LAN upload failed: $err")
                    break
                }
            }

            val remainingCount = synchronized(fileLock) {
                val current = readQueueLocked(appCtx).filterNot { it.id in uploadedIds }
                writeQueueLocked(appCtx, current)
                current.size
            }

            if (uploaded > 0) {
                AppPrefs.appendLog(appCtx, "تم رفع $uploaded رسالة للخادم المحلي (المتبقية: $remainingCount)")
            } else if (lastError != null) {
                AppPrefs.appendLog(appCtx, "تعذّر الرفع المحلي (ستُعاد المحاولة تلقائياً): $lastError")
            }

            return FlushResult(uploaded, remainingCount, lastError)
        }
    }

    fun testAndSync(ctx: Context, onComplete: (Boolean, String) -> Unit) {
        val appCtx = ctx.applicationContext
        executor.execute {
            val normalizedUrl = normalizeServerUrl(AppPrefs.getLanServerUrl(appCtx))
            val token = AppPrefs.getLanApiToken(appCtx)
            if (normalizedUrl.isBlank()) {
                postResult(onComplete, false, "أدخل عنوان الخادم المحلي أولاً")
                return@execute
            }

            val healthResult = checkServerHealth(normalizedUrl, token)
            if (!healthResult.first) {
                AppPrefs.appendLog(appCtx, "فشل اختبار الخادم المحلي: ${healthResult.second}")
                postResult(onComplete, false, healthResult.second)
                return@execute
            }

            val flush = flushQueueSync(appCtx)
            if (flush.lastError != null && flush.remainingCount > 0) {
                scheduleBackgroundRetry(appCtx)
                postResult(
                    onComplete,
                    false,
                    "الخادم متصل لكن فشل رفع الرسائل: ${flush.lastError}"
                )
            } else {
                val msg = if (flush.uploadedCount > 0) {
                    "تم الاتصال بنجاح ومزامنة ${flush.uploadedCount} رسالة معلّقة"
                } else {
                    "تم الاتصال بالخادم المحلي بنجاح (لا توجد رسائل معلّقة)"
                }
                AppPrefs.appendLog(appCtx, msg)
                postResult(onComplete, true, msg)
            }
        }
    }

    private fun checkServerHealth(normalizedUrl: String, token: String): Pair<Boolean, String> {
        val healthUrl = if (normalizedUrl.endsWith("/api/messages")) {
            normalizedUrl.removeSuffix("/api/messages") + "/health"
        } else {
            normalizedUrl
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                if (token.isNotBlank()) {
                    setRequestProperty("X-API-Key", token)
                }
            }
            val code = conn.responseCode
            if (code in 200..299 || code == 405) {
                true to "HTTP $code"
            } else if (code == 401) {
                false to "مفتاح الأمان (X-API-Key) غير صحيح (HTTP 401)"
            } else {
                false to "استجابة غير متوقعة HTTP $code"
            }
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun postSingleMessage(
        serverUrl: String,
        token: String,
        item: PendingLanMessage
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val payload = item.toJson().toString()
            conn = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token.isNotBlank()) {
                    setRequestProperty("X-API-Key", token)
                }
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }
            val code = conn.responseCode
            if (code in 200..299) {
                null
            } else {
                "HTTP $code"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            conn?.disconnect()
        }
    }

    private fun readQueueLocked(ctx: Context): List<PendingLanMessage> {
        val file = File(ctx.filesDir, OUTBOX_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            val arr = JSONArray(text)
            (0 until arr.length()).map { PendingLanMessage.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse outbox file: ${e.message}")
            emptyList()
        }
    }

    private fun writeQueueLocked(ctx: Context, items: List<PendingLanMessage>) {
        val file = File(ctx.filesDir, OUTBOX_FILE)
        val tmpFile = File(ctx.filesDir, "$OUTBOX_FILE.tmp")
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        tmpFile.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmpFile.renameTo(file)) {
            file.writeText(arr.toString(), Charsets.UTF_8)
            tmpFile.delete()
        }
    }

    private fun postResult(cb: (Boolean, String) -> Unit, ok: Boolean, msg: String) {
        mainHandler.post { cb(ok, msg) }
    }
}

class LanUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        if (!AppPrefs.isLanUploadEnabled(applicationContext)) {
            return Result.success()
        }
        val res = LanUploader.flushQueueSync(applicationContext)
        return if (res.remainingCount == 0) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
